package ac.navro.proxy.protocol;

import ac.navro.proxy.ProxyConfig;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.junit.jupiter.api.Test;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RakNetBackendEncryptionTest {
    @Test void privateRakNetAssertionUsesThePinnedRsaKeyAndBindsTheSessionIdentity() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var rsa=generator.generateKeyPair();
        var encryption=new RakNetBackendEncryption(rsa.getPrivate());
        var identity=new LoginIdentity(UUID.randomUUID(),"PrivateBackendPlayer");
        var login=encryption.login(2193,identity,Map.of("xid","123456789"),"{}".getBytes(StandardCharsets.UTF_8));
        var chain=EncryptionUtils.validatePayload(login.getAuthPayload());
        var gameClaims=chain.rawIdentityClaims();
        assertTrue(gameClaims.get("proxyAuth") instanceof String);
        var verified=new JwtConsumerBuilder().setVerificationKey(rsa.getPublic())
                .setExpectedIssuer("navro-proxy").setExpectedSubject("navro-proxy")
                .setExpectedAudience("urn:navro:raknet-backend").setRequireExpirationTime().build()
                .processToClaims((String)gameClaims.get("proxyAuth"));
        assertEquals(identity.uuid().toString(),verified.getStringClaimValue("leguuid"));
        assertEquals("PrivateBackendPlayer",verified.getStringClaimValue("xname"));
        assertEquals("123456789",verified.getStringClaimValue("xid"));
        assertEquals(gameClaims.get("cpk"),verified.getStringClaimValue("cpk"));
    }

    @Test void theBackendDecodesTheEncryptedConfirmationUsingTheKeyFromTheNewLogin() throws Exception {
        var encryption = new RakNetBackendEncryption();
        var identity = new LoginIdentity(UUID.randomUUID(), "BridgeTest");
        var login = encryption.login(2193, identity, Map.of("xid", "1234"),
                "{\"DeviceOS\":1,\"Waterdog_IP\":\"forged\",\"Waterdog_XUID\":\"5678\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(AuthType.SELF_SIGNED, login.getAuthPayload().getAuthType());
        var chain = EncryptionUtils.validatePayload(login.getAuthPayload());
        assertFalse(chain.signed());
        assertEquals("1234", chain.identityClaims().extraData.xuid);
        var proxyKey = chain.identityClaims().parsedIdentityPublicKey();
        var clientData = JsonParser.parseString(new String(EncryptionUtils.verifyClientData(login.getClientJwt(), proxyKey),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, clientData.get("DeviceOS").getAsInt());
        assertFalse(clientData.has("Waterdog_IP"));
        assertFalse(clientData.has("Waterdog_XUID"));

        var serverKeys = EncryptionUtils.createKeyPair();
        var salt = EncryptionUtils.generateRandomToken();
        var expectedKey = EncryptionUtils.getSecretKey(serverKeys.getPrivate(), proxyKey, salt);
        var clientChannel = new EmbeddedChannel();
        var serverChannel = new EmbeddedChannel(new FrameIdCodec(0xfe),
                new BedrockEncryptionDecoder(expectedKey, EncryptionUtils.createCipher(true, false, expectedKey)),
                new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false),
                new BedrockBatchDecoder(), packetCodec());
        try {
            clientChannel.pipeline().addLast(FrameIdCodec.NAME, new FrameIdCodec(0xfe));
            clientChannel.pipeline().addLast(CompressionCodec.NAME,
                    new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
            clientChannel.pipeline().addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
            clientChannel.pipeline().addLast(BedrockPacketCodec.NAME, packetCodec());
            var backend = new BedrockClientSession(new BedrockPeer(clientChannel, BedrockClientSession::new), 0);
            encryption.complete(backend, EncryptionUtils.createHandshakeJwt(serverKeys, salt));
            ByteBuf encrypted = clientChannel.readOutbound();
            assertNotNull(encrypted);
            assertTrue(serverChannel.writeInbound(new RakMessage(encrypted)));
            BedrockPacketWrapper confirmation = serverChannel.readInbound();
            try {
                assertInstanceOf(ClientToServerHandshakePacket.class, confirmation.getPacket());
            } finally {
                confirmation.release();
            }
        } finally {
            clientChannel.finishAndReleaseAll();
            serverChannel.finishAndReleaseAll();
        }
    }

    @Test void rejectsAnInvalidBackendSignatureAndAnInvalidSalt() throws Exception {
        var encryption = new RakNetBackendEncryption();
        var serverKeys = EncryptionUtils.createKeyPair();
        var parts = EncryptionUtils.createHandshakeJwt(serverKeys, EncryptionUtils.generateRandomToken()).split("\\.");
        var signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertThrows(IllegalArgumentException.class, () -> encryption.sessionKey(String.join(".", parts)));
        var invalidSalt = EncryptionUtils.createHandshakeJwt(serverKeys, new byte[8]);
        assertThrows(IllegalArgumentException.class, () -> encryption.sessionKey(invalidSalt));
    }

    @Test void encryptionIsOptInAndCanTargetARemoteBackend() {
        assertFalse(ProxyConfig.defaults().encryptBackend());
        assertTrue(ProxyConfig.parse(new String[]{"--encrypt-backend", "--backend", "127.0.0.1:19133"}).encryptBackend());
        assertTrue(ProxyConfig.parse(new String[]{"--encrypt-backend", "--backend", "192.168.0.100:19133"}).encryptBackend());
    }

    private static BedrockPacketCodec packetCodec() {
        var codec = new BedrockPacketCodec_v3();
        codec.setCodec(Bedrock_v2193.CODEC);
        return codec;
    }
}
