package ac.navro.bridge.nukkit;

import cn.nukkit.utils.BinaryStream;
import cn.nukkit.network.encryption.EncryptionUtils;
import com.google.gson.JsonParser;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


final class PrivateRakNetLoginTrust {
    private static final String PROXY_ISSUER = "navro-proxy";
    private static final String PROXY_AUDIENCE = "urn:navro:raknet-backend";
    private static final String LOGIN_AUDIENCE = "api://auth-minecraft-services/multiplayer";
    private final JwtConsumer proxyConsumer;
    private final Map<String, Long> usedTokens = new HashMap<>();

    PrivateRakNetLoginTrust(Path publicKeyFile) throws Exception {
        String pem = Files.readString(publicKeyFile).strip();
        if (!pem.startsWith("-----BEGIN PUBLIC KEY-----") || !pem.endsWith("-----END PUBLIC KEY-----"))
            throw new IllegalArgumentException("Expected an RSA PUBLIC KEY PEM: " + publicKeyFile);
        byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("Proxy RSA key must be at least 2048 bits");
        proxyConsumer = new JwtConsumerBuilder().setVerificationKey(key)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "RS256")
                .setExpectedIssuer(PROXY_ISSUER).setExpectedSubject(PROXY_ISSUER).setExpectedAudience(PROXY_AUDIENCE)
                .setRequireExpirationTime().setRequireIssuedAt().setIssuedAtRestrictions(5, 90)
                .setAllowedClockSkewInSeconds(5).setMaxFutureValidityInMinutes(2).build();
    }

    synchronized ForwardedIdentity verifyLogin(byte[] payload) throws Exception {
        var input = new BinaryStream(payload);
        String loginToken = ForwardedLoginToken.read(input);
        if (loginToken.length() > 60000 || loginToken.split("\\.").length != 3)
            throw new IllegalArgumentException("Malformed private RakNet login token");

        var loginSignature = new JsonWebSignature();
        loginSignature.setCompactSerialization(loginToken);
        if (!"ES384".equals(loginSignature.getAlgorithmHeaderValue()))
            throw new IllegalArgumentException("Private RakNet login must use ES384");
        var loginParts = loginToken.split("\\.");
        var preview = JwtClaims.parse(new String(Base64.getUrlDecoder().decode(loginParts[1]), StandardCharsets.UTF_8));
        byte[] sessionDer = Base64.getDecoder().decode(preview.getStringClaimValue("cpk"));
        var sessionKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(sessionDer));
        if (!(sessionKey instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384)
            throw new IllegalArgumentException("Private RakNet login requires a P-384 session key");
        loginSignature.setKey(sessionKey);
        if (!loginSignature.verifySignature()) throw new IllegalArgumentException("Invalid private RakNet login signature");

        var loginClaims = new JwtConsumerBuilder().setVerificationKey(sessionKey)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "ES384")
                .setExpectedAudience(LOGIN_AUDIENCE).setRequireExpirationTime().setRequireIssuedAt()
                .setAllowedClockSkewInSeconds(5).build().processToClaims(loginToken);
        UUID loginUuid = UUID.fromString(loginClaims.getStringClaimValue("leguuid"));
        String loginName = loginClaims.getStringClaimValue("xname");
        String loginXuid = loginClaims.getStringClaimValue("xid");
        if (loginName == null || loginName.isBlank() || loginXuid == null || !loginXuid.matches("[0-9]{0,20}"))
            throw new IllegalArgumentException("Private RakNet login is missing its forwarded identity");

        int clientDataSize = input.getLInt();
        if (clientDataSize < 1 || clientDataSize > 3 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid private RakNet client data size");
        String clientDataToken = new String(input.get(clientDataSize), StandardCharsets.UTF_8);
        if (EncryptionUtils.verifyClientData(clientDataToken, sessionKey) == null)
            throw new IllegalArgumentException("Invalid private RakNet client-data signature");

        String assertion = loginClaims.getStringClaimValue("proxyAuth");
        if (assertion == null || assertion.length() > 16384)
            throw new IllegalArgumentException("Missing proxy RSA identity assertion");
        var assertionSignature = new JsonWebSignature();
        assertionSignature.setCompactSerialization(assertion);
        if (!PROXY_ISSUER.equals(assertionSignature.getKeyIdHeaderValue()))
            throw new IllegalArgumentException("Unknown proxy signing key ID");
        JwtClaims claims = proxyConsumer.processToClaims(assertion);
        if (!MessageDigest.isEqual(sessionDer, Base64.getDecoder().decode(claims.getStringClaimValue("cpk"))) ||
                !loginUuid.equals(UUID.fromString(claims.getStringClaimValue("leguuid"))) ||
                !loginName.equals(claims.getStringClaimValue("xname")) ||
                !loginXuid.equals(claims.getStringClaimValue("xid")))
            throw new IllegalArgumentException("Proxy assertion does not match the forwarded login");

        String jti = claims.getJwtId();
        long now = System.currentTimeMillis() / 1000;
        usedTokens.values().removeIf(expiry -> expiry <= now);
        if (jti == null || !jti.matches("(?i)[0-9a-f-]{36}") || usedTokens.containsKey(jti) || usedTokens.size() >= 8192)
            throw new IllegalArgumentException("Replayed or invalid proxy assertion ID");
        usedTokens.put(jti, claims.getExpirationTime().getValue());
        return new ForwardedIdentity(loginUuid, loginXuid);
    }

    record ForwardedIdentity(UUID uuid, String xuid) { }
}
