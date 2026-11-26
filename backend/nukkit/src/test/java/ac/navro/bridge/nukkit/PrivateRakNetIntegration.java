package ac.navro.bridge.nukkit;

import cn.nukkit.utils.BinaryStream;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class PrivateRakNetIntegration {
    private static String sign(JwtClaims claims, java.security.PrivateKey key, String algorithm, String cpk) throws Exception {
        var signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(algorithm);
        signature.setPayload(claims.toJson());
        signature.setKey(key);
        if (cpk != null) signature.setHeader("x5u", cpk);
        if (algorithm.equals("RS256")) signature.setKeyIdHeaderValue("navro-proxy");
        return signature.getCompactSerialization();
    }

    private static String makeLogin(byte[] ecDer, java.security.KeyPair ec, java.security.KeyPair rsa,
                                    UUID uuid, String name, String xuid, String jti) throws Exception {
        String cpk = Base64.getEncoder().encodeToString(ecDer);
        long now = Instant.now().getEpochSecond();
        var assertion = new JwtClaims();
        assertion.setIssuer("navro-proxy"); assertion.setSubject("navro-proxy");
        assertion.setAudience("urn:navro:raknet-backend"); assertion.setIssuedAtToNow();
        assertion.setExpirationTimeMinutesInTheFuture(1); assertion.setJwtId(jti);
        assertion.setStringClaim("cpk", cpk); assertion.setStringClaim("leguuid", uuid.toString());
        assertion.setStringClaim("xname", name); assertion.setStringClaim("xid", xuid);
        String proxyAssertion = sign(assertion, rsa.getPrivate(), "RS256", null);

        var login = new JwtClaims(); login.setAudience("api://auth-minecraft-services/multiplayer");
        login.setIssuedAt(org.jose4j.jwt.NumericDate.fromSeconds(now));
        login.setExpirationTime(org.jose4j.jwt.NumericDate.fromSeconds(now + 60));
        login.setStringClaim("cpk", cpk); login.setStringClaim("leguuid", uuid.toString());
        login.setStringClaim("xname", name); login.setStringClaim("xid", xuid);
        login.setStringClaim("proxyAuth", proxyAssertion);
        String loginToken = sign(login, ec.getPrivate(), "ES384", cpk);

        var clientData = new JwtClaims(); clientData.setStringClaim("GameVersion", "1.26.50");
        String clientToken = sign(clientData, ec.getPrivate(), "ES384", cpk);
        var packet = new BinaryStream();
        byte[] auth = ("{\"AuthenticationType\":2,\"Token\":\"" + loginToken + "\"}").getBytes(StandardCharsets.UTF_8);
        packet.putLInt(auth.length); packet.put(auth);
        byte[] client = clientToken.getBytes(StandardCharsets.UTF_8); packet.putLInt(client.length); packet.put(client);
        return Base64.getEncoder().encodeToString(packet.getBuffer());
    }

    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("navro-private-raknet-");
        var rsaGenerator = KeyPairGenerator.getInstance("RSA"); rsaGenerator.initialize(2048);
        var rsa = rsaGenerator.generateKeyPair();
        var otherRsa = rsaGenerator.generateKeyPair();
        var ec = cn.nukkit.network.encryption.EncryptionUtils.createKeyPair();
        Path publicKey = folder.resolve("proxy.pub.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder().encodeToString(rsa.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        var trust = new PrivateRakNetLoginTrust(publicKey);
        UUID uuid = UUID.randomUUID(); String xuid = "123456789"; String jti = UUID.randomUUID().toString();
        byte[] valid = Base64.getDecoder().decode(makeLogin(ec.getPublic().getEncoded(), ec, rsa, uuid, "ProxyPlayer", xuid, jti));
        var identity = trust.verifyLogin(valid);
        if (!identity.uuid().equals(uuid) || !identity.xuid().equals(xuid)) throw new AssertionError("Forwarded identity mismatch");
        try { trust.verifyLogin(valid); throw new AssertionError("Replay accepted"); }
        catch (IllegalArgumentException expected) { }

        Path wrongKey = folder.resolve("wrong.pub.pem");
        Files.writeString(wrongKey, "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder().encodeToString(otherRsa.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        try { new PrivateRakNetLoginTrust(wrongKey).verifyLogin(valid); throw new AssertionError("Wrong proxy key accepted"); }
        catch (Exception expected) { }

        System.out.println("PRIVATE_RAKNET_AUTH_PASSED RSA pin, session-key binding, identity preservation and replay rejection");
    }
}
