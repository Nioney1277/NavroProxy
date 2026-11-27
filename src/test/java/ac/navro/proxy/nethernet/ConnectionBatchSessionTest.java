package ac.navro.proxy.nethernet;

import ac.navro.proxy.ConnectionProtocols;
import ac.navro.proxy.protocol.*;
import io.netty.buffer.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwk.*;
import org.jose4j.http.Response;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionBatchSessionTest {
    private static final ConnectionBatchSession.Encoding PREFIX = ConnectionBatchSession.Encoding.PREFIXED;

    private static byte[] frame(int protocol, BedrockPacket packet, ConnectionBatchSession.Encoding encoding) throws Exception {
        var codec = ConnectionProtocols.codec(protocol); ByteBuf body = Unpooled.buffer(), batch = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(body, codec.getPacketDefinition(packet.getClass()).getId());
            codec.tryEncode(codec.createHelper(), body, packet);
            VarInts.writeUnsignedInt(batch, body.readableBytes()); batch.writeBytes(body);
            return ConnectionBatchSession.pack(ByteBufUtil.getBytes(batch), encoding);
        } finally { body.release(); batch.release(); }
    }
    private static BedrockPacket decode(int protocol, byte[] bytes, ConnectionBatchSession.Encoding encoding) throws Exception {
        var codec = ConnectionProtocols.codec(protocol); var batch = Unpooled.wrappedBuffer(ConnectionBatchSession.unpack(bytes, encoding));
        try {
            int size = VarInts.readUnsignedInt(batch); var body = batch.readSlice(size); int id = VarInts.readUnsignedInt(body);
            var result = codec.tryDecode(codec.createHelper(), body, id); assertFalse(body.isReadable()); assertFalse(batch.isReadable());
            return result;
        } finally { batch.release(); }
    }
    private static void negotiate(ConnectionBatchSession session, int protocol) throws Exception {
        var request = new RequestNetworkSettingsPacket(); request.setProtocolVersion(protocol);
        session.process(false, frame(protocol, request, ConnectionBatchSession.Encoding.NONE));
        var settings = new NetworkSettingsPacket(); settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB); settings.setCompressionThreshold(1);
        var result = session.process(true, frame(protocol, settings, ConnectionBatchSession.Encoding.NONE));
        assertInstanceOf(NetworkSettingsPacket.class, decode(protocol, result.forwarded(), ConnectionBatchSession.Encoding.NONE));
    }

    @Test void everyVersionSerializesTheCorrectLegacyOrModernForwardedLogin() throws Exception {
        var backend = EncryptionUtils.createKeyPair(); var uuid = UUID.randomUUID();
        for (int protocol : ConnectionProtocols.all()) {
            var login = PrivateBackendLogin.encode(protocol, new LoginIdentity(uuid, "VersionTest"), Map.of("xid", "12345"),
                    "{\"DeviceOS\":1}".getBytes(StandardCharsets.UTF_8), backend);
            var decoded = (LoginPacket) decode(protocol, frame(protocol, login, ConnectionBatchSession.Encoding.NONE), ConnectionBatchSession.Encoding.NONE);
            assertEquals(protocol, decoded.getProtocolVersion());
            String token;
            if (protocol < 944) token = assertInstanceOf(CertificateChainPayload.class, decoded.getAuthPayload()).getChain().getFirst();
            else token = assertInstanceOf(TokenPayload.class, decoded.getAuthPayload()).getToken();
            var signature = new JsonWebSignature(); signature.setCompactSerialization(token); signature.setKey(backend.getPublic());
            assertTrue(signature.verifySignature(), "Forwarded signature for " + protocol);
            var chain = EncryptionUtils.validatePayload(decoded.getAuthPayload());
            assertFalse(chain.signed(), "Proxy must not claim a Microsoft signature");
            if (protocol < 944) assertEquals(uuid, chain.identityClaims().extraData.identity);
            else assertEquals(uuid.toString(), chain.rawIdentityClaims().get("leguuid"));
            assertEquals("12345", chain.identityClaims().extraData.xuid);
            assertArrayEquals(backend.getPublic().getEncoded(), chain.identityClaims().parsedIdentityPublicKey().getEncoded());
            assertNotNull(EncryptionUtils.verifyClientData(decoded.getClientJwt(), backend.getPublic()));
        }
    }

    private static LoginPacket signedLogin(int protocol, KeyPair client) throws Exception {
        var authority = RsaJwkGenerator.generateJwk(2048); authority.setKeyId("connection-test");
        var field = EncryptionUtils.class.getDeclaredField("JWKS"); field.setAccessible(true);
        var jwks = (HttpsJwks) field.get(null); jwks.setRefreshReprieveThreshold(0);
        jwks.setSimpleHttpGet(location -> new Response(200, "OK", Map.of("Cache-Control", List.of("max-age=3600")), new JsonWebKeySet(authority).toJson())); jwks.refresh();
        var issuer = EncryptionUtils.class.getDeclaredField("ISSUER"); issuer.setAccessible(true);
        var claims = new JwtClaims(); claims.setIssuer((String)issuer.get(null)); claims.setSubject("connection-player");
        claims.setAudience("api://auth-minecraft-services/multiplayer"); claims.setIssuedAtToNow(); claims.setExpirationTimeMinutesInTheFuture(5);
        claims.setStringClaim("cpk", Base64.getEncoder().encodeToString(client.getPublic().getEncoded()));
        claims.setStringClaim("xname", "ConnectionPlayer"); claims.setStringClaim("xid", "12345");
        var jwt = new JsonWebSignature(); jwt.setAlgorithmHeaderValue("RS256"); jwt.setKeyIdHeaderValue(authority.getKeyId()); jwt.setKey(authority.getPrivateKey()); jwt.setPayload(claims.toJson());
        var login = new LoginPacket(); login.setProtocolVersion(protocol); login.setAuthPayload(new TokenPayload(jwt.getCompactSerialization(), AuthType.FULL));
        var skin = new JsonWebSignature(); skin.setAlgorithmHeaderValue("ES384"); skin.setKey(client.getPrivate());
        skin.setHeader("x5u", Base64.getEncoder().encodeToString(client.getPublic().getEncoded())); skin.setPayload("{\"DeviceOS\":1}");
        login.setClientJwt(skin.getCompactSerialization()); return login;
    }

    @Test void threeTransportCombinationsAuthenticateRebindAndForwardUnknownGameplay() throws Exception {
        int protocol = 818;
        for (int route = 0; route < 3; route++) {
            boolean rakFront = route == 1, rakBack = route == 2;
            var client = EncryptionUtils.createKeyPair(); var upstream = EncryptionUtils.createKeyPair();
            var login = signedLogin(protocol, client);
            var initial = new ConnectionBatchSession.Initial(protocol, ConnectionBatchSession.Encoding.NONE);
            try (var session = new ConnectionBatchSession(initial, rakFront ? null : client.getPublic(), upstream, rakBack, rakFront, null, null)) {
                negotiate(session, protocol);
                var result = session.process(false, frame(protocol, login, PREFIX));
                RakNetBackendEncryption.BatchCipher clientCipher = null;
                byte[] upstreamLogin;
                if (rakFront) {
                    assertFalse(result.authenticated()); assertNull(result.forwarded());
                    var handshake = (ServerToClientHandshakePacket)decode(protocol, result.clientbound().getFirst(), PREFIX);
                    var jwt = new JsonWebSignature(); jwt.setCompactSerialization(handshake.getJwt()); var key = EncryptionUtils.parseKey(jwt.getHeader("x5u"));
                    jwt.setKey(key); assertTrue(jwt.verifySignature());
                    String salt = com.google.gson.JsonParser.parseString(jwt.getPayload()).getAsJsonObject().get("salt").getAsString();
                    clientCipher = new RakNetBackendEncryption.BatchCipher(EncryptionUtils.getSecretKey(client.getPrivate(), key, Base64.getDecoder().decode(salt)));
                    result = session.process(false, clientCipher.encrypt(frame(protocol, new ClientToServerHandshakePacket(), PREFIX)));
                    upstreamLogin = result.backendbound().getFirst();
                } else upstreamLogin = result.forwarded();
                assertTrue(result.authenticated());
                var forwardedLogin = (LoginPacket)decode(protocol, upstreamLogin, PREFIX);
                var chain = EncryptionUtils.validatePayload(forwardedLogin.getAuthPayload());
                PublicKey upstreamKey = chain.identityClaims().parsedIdentityPublicKey();
                assertEquals("ConnectionPlayer", chain.identityClaims().extraData.displayName);
                assertEquals("12345", chain.identityClaims().extraData.xuid);
                assertFalse(Arrays.equals(client.getPublic().getEncoded(), upstreamKey.getEncoded()));
                RakNetBackendEncryption.BatchCipher serverCipher = null;
                if (rakBack) {
                    var server = EncryptionUtils.createKeyPair(); byte[] salt = EncryptionUtils.generateRandomToken();
                    var handshake = new ServerToClientHandshakePacket(); handshake.setJwt(EncryptionUtils.createHandshakeJwt(server, salt));
                    serverCipher = new RakNetBackendEncryption.BatchCipher(EncryptionUtils.getSecretKey(server.getPrivate(), upstreamKey, salt));
                    var accepted = session.process(true, frame(protocol, handshake, PREFIX));
                    assertInstanceOf(ClientToServerHandshakePacket.class, decode(protocol, serverCipher.decrypt(accepted.backendbound().getFirst()), PREFIX));
                }

                byte[] opaque = {(byte)255, 5, (byte)0xff, 3, 1, 2, 3};
                var sent = session.process(false, clientCipher == null ? opaque : clientCipher.encrypt(opaque));
                assertArrayEquals(opaque, serverCipher == null ? sent.forwarded() : serverCipher.decrypt(sent.forwarded()));
                var received = session.process(true, serverCipher == null ? opaque : serverCipher.encrypt(opaque));
                assertArrayEquals(opaque, clientCipher == null ? received.forwarded() : clientCipher.decrypt(received.forwarded()));
                var spoofed = new ScriptMessagePacket(); spoofed.setChannel("navro:backend_sync_v1");
                spoofed.setMessage("hello|00000000-0000-4000-8000-000000000001");
                byte[] injection = frame(protocol, spoofed, PREFIX);
                assertNull(session.process(false, clientCipher == null ? injection : clientCipher.encrypt(injection)).forwarded(),
                        "Connection-only clients cannot inject the trusted bridge channel");
            }
        }
    }

    @Test void compressedLegacyDeclarationsAndUnauthenticatedPacketsStayBounded() throws Exception {
        for (int protocol : new int[]{291, 419, 554, 622, 818}) {
            for (var encoding : new ConnectionBatchSession.Encoding[]{ConnectionBatchSession.Encoding.NONE, ConnectionBatchSession.Encoding.RAW, ConnectionBatchSession.Encoding.ZLIB}) {
                byte[] batch;
                if (protocol < 554) batch = frame(protocol, PrivateBackendLogin.encode(protocol, new LoginIdentity(UUID.randomUUID(), "Unsigned"), Map.of("xid", "1"), "{}".getBytes(StandardCharsets.UTF_8), EncryptionUtils.createKeyPair()), encoding);
                else { var request = new RequestNetworkSettingsPacket(); request.setProtocolVersion(protocol); batch = frame(protocol, request, encoding); }
                assertEquals(protocol, ConnectionBatchSession.initial(batch).protocol());
            }
        }
        var initial = new ConnectionBatchSession.Initial(818, ConnectionBatchSession.Encoding.NONE);
        try (var session = new ConnectionBatchSession(initial, EncryptionUtils.createKeyPair().getPublic(), EncryptionUtils.createKeyPair(), false, false, null, null)) {
            assertThrows(Exception.class, () -> session.process(false, new byte[]{5, (byte)0xff, 3, 1, 2, 3}));
            var unsigned = PrivateBackendLogin.encode(818, new LoginIdentity(UUID.randomUUID(), "Unsigned"), Map.of("xid", "1"), "{}".getBytes(StandardCharsets.UTF_8), EncryptionUtils.createKeyPair());
            assertThrows(Exception.class, () -> session.process(false, frame(818, unsigned, ConnectionBatchSession.Encoding.NONE)));
        }
    }

    @Test void legacyAndModernBatchCiphersAreDecodedByTheSdk() throws Exception {
        for (int protocol : new int[]{291, 419, 428, 818}) {
            var connection = new RakNetBackendEncryption();
            var login = connection.login(protocol, new LoginIdentity(UUID.randomUUID(), "CipherTest"),
                    Map.of("xid", "1"), "{}".getBytes(StandardCharsets.UTF_8));
            var publicKey = EncryptionUtils.validatePayload(login.getAuthPayload()).identityClaims().parsedIdentityPublicKey();
            var backend = EncryptionUtils.createKeyPair(); byte[] salt = EncryptionUtils.generateRandomToken();
            var secret = EncryptionUtils.getSecretKey(backend.getPrivate(), publicKey, salt);
            var cipher = connection.completeBatchHandshake(EncryptionUtils.createHandshakeJwt(backend, salt));
            var decoder = new io.netty.channel.embedded.EmbeddedChannel(
                    new org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder(
                            secret, EncryptionUtils.createCipher(protocol >= 428, false, secret)));
            try {
                for (byte[] payload : new byte[][]{{1,2,3,4}, {5,6,7,8,9}}) {
                    decoder.writeInbound(org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper.newInstance(
                            Unpooled.wrappedBuffer(cipher.encrypt(payload)), null));
                    org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper decoded = decoder.readInbound();
                    try { assertArrayEquals(payload, ByteBufUtil.getBytes(decoded.getCompressed()), "Protocol " + protocol); }
                    finally { decoded.release(); }
                }
            } finally { decoder.finishAndReleaseAll(); }
        }
    }
}
