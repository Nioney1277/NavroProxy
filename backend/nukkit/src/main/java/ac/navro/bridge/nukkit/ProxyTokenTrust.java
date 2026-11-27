package ac.navro.bridge.nukkit;

import org.cloudburstmc.netty.util.nethernet.Identity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;


final class ProxyTokenTrust implements TokenTrust {
    static final String ISSUER = "navro-proxy";
    static final String AUDIENCE = "urn:navro:nethernet-backend";
    private final JwtConsumer consumer;

    ProxyTokenTrust(Path publicKeyFile) throws Exception {
        String pem = Files.readString(publicKeyFile).strip();
        if (!pem.startsWith("-----BEGIN PUBLIC KEY-----") || !pem.endsWith("-----END PUBLIC KEY-----"))
            throw new IllegalArgumentException("Expected an RSA PUBLIC KEY PEM: " + publicKeyFile);
        byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("Proxy RSA key must be at least 2048 bits");
        consumer = new JwtConsumerBuilder().setVerificationKey(key)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, "RS256")
                .setExpectedIssuer(ISSUER).setExpectedSubject(ISSUER).setExpectedAudience(AUDIENCE)
                .setRequireExpirationTime().setRequireIssuedAt().setIssuedAtRestrictions(5, 90)
                .setAllowedClockSkewInSeconds(5).setMaxFutureValidityInMinutes(2).build();
    }

    @Override public JwtClaims claims(Identity identity) throws Exception {
        String token = identity.assertion().token();
        if (token.length() > 16384) throw new IllegalArgumentException("Proxy token too large");
        var signature = new JsonWebSignature(); signature.setCompactSerialization(token);
        if (!ISSUER.equals(signature.getKeyIdHeaderValue())) throw new IllegalArgumentException("Unknown proxy key ID");
        JwtClaims claims = consumer.processToClaims(token);
        byte[] der = Base64.getDecoder().decode(claims.getStringClaimValue("cpk"));
        var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        if (!(key instanceof java.security.interfaces.ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384)
            throw new IllegalArgumentException("Proxy session key must be P-384");
        return claims;
    }
}
