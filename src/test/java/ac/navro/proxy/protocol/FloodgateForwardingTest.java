package ac.navro.proxy.protocol;

import ac.navro.proxy.ProxyConfig;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.geysermc.geyser.util.NavroForwardedLogin;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FloodgateForwardingTest {
    private static final InetSocketAddress PROXY = new InetSocketAddress("127.0.0.1", 43210);
    private static final UUID UUID_FROM_CHAIN = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final byte[] CLIENT_DATA = """
            {"DeviceOS":1,"Waterdog_IP":"192.0.2.99","Waterdog_XUID":"54321","ipt":"192.0.2.98","xid":"99999"}
            """.getBytes(UTF_8);

    @TempDir Path tempDir;
    private KeyPair signer;
    private FloodgateForwarding forwarding;
    private NavroForwardedLogin verifier;

    @BeforeEach void setUp() throws Exception {
        signer = EncryptionUtils.createKeyPair();
        Path path = tempDir.resolve("signing.pem");
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(signer.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        forwarding = FloodgateForwarding.load(path);
        verifier = new NavroForwardedLogin(signer.getPublic());
    }

    @Test void geyserReceivesOnlyTheVerifiedIdentityAndConnectionAddress() throws Exception {
        var backend = new RakNetBackendEncryption();
        var playerAddress = new InetSocketAddress("2001:db8::abcd", 50000);
        var packet = backend.forwardedLogin(2193, chain(true, "18446744073709551615"), CLIENT_DATA,
                playerAddress, forwarding);
        assertEquals(AuthType.SELF_SIGNED, packet.getAuthPayload().getAuthType());
        var accepted = verifier.verify(packet.getAuthPayload(), PROXY);
        assertEquals("18446744073709551615", accepted.xuid());
        assertEquals("PlayerName", accepted.username());
        assertEquals(UUID_FROM_CHAIN, accepted.uuid());
        assertEquals(playerAddress.getAddress(), accepted.address().getAddress());

        var skin = JsonParser.parseString(new String(
                EncryptionUtils.verifyClientData(packet.getClientJwt(), accepted.sessionKey()), UTF_8)).getAsJsonObject();
        assertEquals(1, skin.get("DeviceOS").getAsInt());
        assertFalse(skin.has("Waterdog_IP"));
        assertFalse(skin.has("Waterdog_XUID"));

        var geyserKeys = EncryptionUtils.createKeyPair();
        var salt = EncryptionUtils.generateRandomToken();
        assertEquals(EncryptionUtils.getSecretKey(geyserKeys.getPrivate(), accepted.sessionKey(), salt),
                backend.sessionKey(EncryptionUtils.createHandshakeJwt(geyserKeys, salt)));
        var other = new RakNetBackendEncryption().forwardedLogin(2193, chain(true, "12345"), CLIENT_DATA,
                playerAddress, forwarding);
        assertNotEquals(accepted.sessionKey(), verifier.verify(other.getAuthPayload(), PROXY).sessionKey());
    }

    @Test void rejectsUnsignedMissingAndOutOfRangeXuids() throws Exception {
        var backend = new RakNetBackendEncryption();
        for (String xuid : new String[]{"", "0", "18446744073709551616", "abc"}) {
            assertThrows(IllegalArgumentException.class, () -> backend.forwardedLogin(2193, chain(true, xuid),
                    CLIENT_DATA, PROXY, forwarding));
        }
        assertThrows(IllegalArgumentException.class, () -> backend.forwardedLogin(2193, chain(false, "12345"),
                CLIENT_DATA, PROXY, forwarding));
    }

    @Test void geyserRejectsOtherKeysTamperingExpiredTokensAndNonLoopbackPeers() throws Exception {
        var token = (TokenPayload) new RakNetBackendEncryption().forwardedLogin(2193, chain(true, "12345"), CLIENT_DATA,
                PROXY, forwarding).getAuthPayload();
        var wrongKeyVerifier = new NavroForwardedLogin(EncryptionUtils.createKeyPair().getPublic());
        assertThrows(InvalidJwtException.class, () -> wrongKeyVerifier.verify(token, PROXY));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(token, new InetSocketAddress("192.0.2.1", 40000)));

        var jws = new JsonWebSignature();
        jws.setCompactSerialization(token.getToken());
        var claims = JwtClaims.parse(jws.getUnverifiedPayload());
        claims.setStringClaim("xid", "99999");
        var parts = token.getToken().split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toJson().getBytes(UTF_8));
        var tampered = new TokenPayload(String.join(".", parts), AuthType.SELF_SIGNED);
        assertThrows(InvalidJwtException.class, () -> verifier.verify(tampered, PROXY));

        long now = Instant.now().getEpochSecond();
        claims.setIssuedAt(NumericDate.fromSeconds(now - 31));
        claims.setExpirationTime(NumericDate.fromSeconds(now - 1));
        jws.setKey(signer.getPrivate());
        jws.setPayload(claims.toJson());
        var expired = new TokenPayload(jws.getCompactSerialization(), AuthType.SELF_SIGNED);
        assertThrows(InvalidJwtException.class, () -> verifier.verify(expired, PROXY));
    }

    @Test void requiresExplicitOptInAndBackendEncryption() {
        assertNull(ProxyConfig.defaults().floodgateForwardingKey());
        assertThrows(IllegalArgumentException.class, () -> ProxyConfig.parse(new String[]{"--floodgate-forwarding-key", "key.pem"}));
        assertEquals(Path.of("key.pem"), ProxyConfig.parse(new String[]{"--encrypt-backend", "--floodgate-forwarding-key", "key.pem"})
                .floodgateForwardingKey());
    }

    private static ChainValidationResult chain(boolean signed, String xuid) {
        return new ChainValidationResult(signed, Map.of(
                "identityPublicKey", Base64.getEncoder().encodeToString(EncryptionUtils.createKeyPair().getPublic().getEncoded()),
                "extraData", Map.of("identity", UUID_FROM_CHAIN.toString(), "displayName", "PlayerName", "XUID", xuid)));
    }
}
