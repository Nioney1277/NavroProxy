package ac.navro.proxy.nethernet;

import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.protocol.BackendType;
import ac.navro.proxy.protocol.RakNetBackendEncryption.BatchCipher;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.jose4j.jwk.*;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.http.Response;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RakNetFrontendSessionTest {
    private static final BedrockCodec CODEC = SupportedCodecs.forProtocol(2225);
    private static byte[] frame(BedrockPacket packet, boolean compressed) {
        var buf=Unpooled.buffer();var body=Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(body, CODEC.getPacketDefinition(packet.getClass()).getId());
            CODEC.tryEncode(CODEC.createHelper(),body,packet);
            if(compressed) buf.writeByte(255);
            VarInts.writeUnsignedInt(buf,body.readableBytes());buf.writeBytes(body);
            byte[] bytes=new byte[buf.readableBytes()];buf.readBytes(bytes);return bytes;
        } finally {buf.release();body.release();}
    }
    private static List<BedrockPacket> decode(byte[] bytes, boolean compressed) {
        var buf=Unpooled.wrappedBuffer(bytes);var result=new ArrayList<BedrockPacket>();
        try {
            if(compressed)assertEquals(255,buf.readUnsignedByte());
            while(buf.isReadable()) {
                int size=VarInts.readUnsignedInt(buf);var packet=buf.readSlice(size);
                int id=VarInts.readUnsignedInt(packet);result.add(CODEC.tryDecode(CODEC.createHelper(),packet,id));
                assertFalse(packet.isReadable());
            }
            return result;
        } finally {buf.release();}
    }
    private static void negotiate(NetherNetBatchSession session) throws Exception {
        var request=new RequestNetworkSettingsPacket();request.setProtocolVersion(2225);
        assertFalse(session.process(false,frame(request,false)).backendbound().isEmpty());
        var settings=new NetworkSettingsPacket();settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);settings.setCompressionThreshold(1);
        var response=session.process(true,frame(settings,false));
        assertInstanceOf(NetworkSettingsPacket.class,decode(response.forwarded(),false).getFirst());
    }
    private static NetherNetBatchSession session(KeyPair backend) {
        return NetherNetBatchSession.forRakNetFrontend(backend,BackendType.NAVRO,false,false);
    }
    @Test void ipcAllowsMissingDtlsKeyOnlyForTheRakNetFrontendMode() throws Exception {
        var handle=NetherNetInspectServer.class.getDeclaredMethod("handle",java.net.Socket.class,
                BackendType.class,boolean.class,Map.class,boolean.class,boolean.class);
        handle.setAccessible(true);
        for(int magic:new int[]{0x4e564238,0x4e564237}) {
            try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
                var client=new java.net.Socket(server.getInetAddress(),server.getLocalPort())) {
                client.setSoTimeout(10000);
                var errors=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var thread=new Thread(()->{
                    try {handle.invoke(null,server.accept(),BackendType.NAVRO,false,Map.of(),false,false);}
                    catch(Throwable e){errors.set(e);}
                });thread.setDaemon(true);thread.start();
                var in=new DataInputStream(client.getInputStream());var out=new DataOutputStream(client.getOutputStream());
                out.writeInt(magic);out.writeInt(0);
                if(magic==0x4e564238) {
                    var keys=EncryptionUtils.createKeyPair();
                    for(byte[] bytes:List.of(keys.getPublic().getEncoded(),keys.getPrivate().getEncoded())) {
                        out.writeInt(bytes.length);out.write(bytes);
                    }
                    out.flush();assertEquals(magic,in.readInt());
                    var request=new RequestNetworkSettingsPacket();request.setProtocolVersion(2225);
                    byte[] bytes=frame(request,false);
                    out.writeByte(1);out.writeByte(0);out.writeInt(bytes.length);out.write(bytes);out.flush();
                    assertEquals(-1,in.readInt());assertEquals(0,in.readInt());assertEquals(1,in.readInt());
                    assertArrayEquals(bytes,in.readNBytes(in.readInt()));
                    client.shutdownOutput();
                }else {out.flush();assertEquals(-1,in.read());}
                thread.join(10000);assertFalse(thread.isAlive());assertNull(errors.get());
            }
        }
    }

    @Test void rejectsUnauthenticatedGamePacketsAndGuestLogin() throws Exception {
        try(var session=session(EncryptionUtils.createKeyPair())) {
            negotiate(session);
            var cache=new ClientCacheStatusPacket();cache.setSupported(true);
            assertThrows(IOException.class,()->session.process(false,frame(cache,true)));
            var guest=new LoginPacket();guest.setProtocolVersion(2225);
            guest.setAuthPayload(new TokenPayload("invalid",AuthType.GUEST));guest.setClientJwt("invalid");
            assertThrows(IOException.class,()->session.process(false,frame(guest,true)));
            assertFalse(session.authenticated());
            assertThrows(IOException.class,()->session.process(false,frame(new ClientToServerHandshakePacket(),true)));
        }
    }
    @Test void rejectsSelfSignedPublicLogin() throws Exception {
        var client=EncryptionUtils.createKeyPair();
        var login=ac.navro.proxy.protocol.PrivateBackendLogin.encode(2225,
                new ac.navro.proxy.protocol.LoginIdentity(UUID.randomUUID(),"Untrusted"),Map.of("xid","1234"),
                "{\"DeviceOS\":1}".getBytes(StandardCharsets.UTF_8),client);
        try(var session=session(EncryptionUtils.createKeyPair())) {
            negotiate(session);
            assertThrows(IOException.class,()->session.process(false,frame(login,true)));
            assertFalse(session.authenticated());
        }
    }
    @Test void validatesSignedLoginThenWaitsForEncryptedConfirmationAndRebindsBackendIdentity() throws Exception {
        // A local test authority supplies a real RSA signature through the SDK's JWKS fetcher.
        // Production authentication is unchanged; no test key is present in main sources.
        var authority=RsaJwkGenerator.generateJwk(2048);authority.setKeyId("reverse-test-authority");
        var jwksField=EncryptionUtils.class.getDeclaredField("JWKS");jwksField.setAccessible(true);
        var jwks=(HttpsJwks)jwksField.get(null);
        jwks.setRefreshReprieveThreshold(0);
        jwks.setSimpleHttpGet(location -> new Response(200,"OK",Map.of("Cache-Control",List.of("max-age=3600")),new JsonWebKeySet(authority).toJson()));
        jwks.refresh();
        var issuerField=EncryptionUtils.class.getDeclaredField("ISSUER");issuerField.setAccessible(true);
        var client=EncryptionUtils.createKeyPair();var backend=EncryptionUtils.createKeyPair();
        var claims=new JwtClaims();claims.setIssuer((String)issuerField.get(null));claims.setSubject("reverse-test-player");
        claims.setAudience("api://auth-minecraft-services/multiplayer");claims.setIssuedAtToNow();claims.setExpirationTimeMinutesInTheFuture(5);
        claims.setClaim("cpk",Base64.getEncoder().encodeToString(client.getPublic().getEncoded()));
        claims.setClaim("xname","ReverseTest");claims.setClaim("xid","1234567890123456");claims.setClaim("mid","fixture");
        var token=new JsonWebSignature();token.setAlgorithmHeaderValue("RS256");token.setKeyIdHeaderValue(authority.getKeyId());
        token.setKey(authority.getPrivateKey());token.setPayload(claims.toJson());
        var login=new LoginPacket();login.setProtocolVersion(2225);login.setAuthPayload(new TokenPayload(token.getCompactSerialization(),AuthType.FULL));
        var skin=new JsonWebSignature();skin.setAlgorithmHeaderValue("ES384");skin.setKey(client.getPrivate());
        skin.setHeader("x5u",Base64.getEncoder().encodeToString(client.getPublic().getEncoded()));skin.setPayload("{\"DeviceOS\":1}");
        login.setClientJwt(skin.getCompactSerialization());
        String validSkin=login.getClientJwt();
        skin.setKey(EncryptionUtils.createKeyPair().getPrivate());
        login.setClientJwt(skin.getCompactSerialization());
        try(var rejected=session(backend)) {
            negotiate(rejected);
            assertThrows(Exception.class,()->rejected.process(false,frame(login,true)));
            assertFalse(rejected.authenticated());
        }
        login.setClientJwt(validSkin);
        try(var session=session(backend)) {
            negotiate(session);
            var response=session.process(false,frame(login,true));
            assertFalse(response.authenticated());assertNull(response.forwarded());assertTrue(response.backendbound().isEmpty());
            assertEquals(1,response.clientbound().size(),"Only the unencrypted handshake may be sent before confirmation");
            var handshake=assertInstanceOf(ServerToClientHandshakePacket.class,decode(response.clientbound().getFirst(),true).getFirst());
            var jws=new JsonWebSignature();jws.setCompactSerialization(handshake.getJwt());
            var serverKey=EncryptionUtils.parseKey(jws.getHeader("x5u"));jws.setKey(serverKey);assertTrue(jws.verifySignature());
            var salt=Base64.getDecoder().decode(com.google.gson.JsonParser.parseString(jws.getPayload()).getAsJsonObject().get("salt").getAsString());
            var cipher=new BatchCipher(EncryptionUtils.getSecretKey(client.getPrivate(),serverKey,salt));
            var accepted=session.process(false,cipher.encrypt(frame(new ClientToServerHandshakePacket(),true)));
            assertTrue(accepted.authenticated());assertNull(accepted.forwarded());assertFalse(accepted.backendbound().isEmpty());
            var forwarded=assertInstanceOf(LoginPacket.class,decode(accepted.backendbound().getFirst(),true).getFirst());
            assertEquals(AuthType.SELF_SIGNED,forwarded.getAuthPayload().getAuthType());
            var forwardedToken=new JsonWebSignature();forwardedToken.setCompactSerialization(((TokenPayload)forwarded.getAuthPayload()).getToken());
            forwardedToken.setKey(backend.getPublic());assertTrue(forwardedToken.verifySignature());
            assertArrayEquals(backend.getPublic().getEncoded(),EncryptionUtils.parseKey(forwardedToken.getHeader("x5u")).getEncoded());
            assertFalse(Arrays.equals(client.getPublic().getEncoded(),backend.getPublic().getEncoded()));
            assertEquals("ReverseTest",com.google.gson.JsonParser.parseString(forwardedToken.getPayload()).getAsJsonObject().get("xname").getAsString());
            var cache=new ClientCacheStatusPacket();cache.setSupported(true);
            var request=session.process(false,cipher.encrypt(frame(cache,true)));
            assertFalse(assertInstanceOf(ClientCacheStatusPacket.class,decode(request.backendbound().getFirst(),true).getFirst()).isSupported());
            var status=new PlayStatusPacket();status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
            var outbound=session.process(true,frame(status,true));
            assertInstanceOf(PlayStatusPacket.class,decode(cipher.decrypt(outbound.forwarded()),true).getFirst());
            var damaged=cipher.encrypt(frame(cache,true));damaged[damaged.length-1]^=1;
            assertThrows(IOException.class,()->session.process(false,damaged));
        }
    }
}
