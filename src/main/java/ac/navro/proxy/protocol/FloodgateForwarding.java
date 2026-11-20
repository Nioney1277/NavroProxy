package ac.navro.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

public final class FloodgateForwarding {
    private final ECPrivateKey signingKey;

    private FloodgateForwarding(ECPrivateKey signingKey) {
        this.signingKey = signingKey;
    }

    public static FloodgateForwarding load(Path path) throws IOException, GeneralSecurityException {
        String pem = Files.readString(path).strip();
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new GeneralSecurityException("Expected an unencrypted PKCS#8 PEM Floodgate forwarding key");
        }
        byte[] der = Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length())
                .replaceAll("\\s", ""));
        var key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (key.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new GeneralSecurityException("Floodgate forwarding requires a P-384 key");
        }
        return new FloodgateForwarding(key);
    }

    String token(ChainValidationResult verifiedChain, InetSocketAddress address, PublicKey backendKey) throws Exception {
        LoginIdentity identity = LoginIdentity.fromVerifiedChain(verifiedChain);
        var data = verifiedChain.identityClaims().extraData;
        String xuid = data.xuid;
        if (xuid == null || !xuid.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("Missing or invalid verified Bedrock XUID");
        }
        Long.parseUnsignedLong(xuid);

        long now = Instant.now().getEpochSecond();
        var claims = new JwtClaims();
        claims.setAudience("urn:navro:floodgate-forwarding:1");
        claims.setIssuedAt(NumericDate.fromSeconds(now));
        claims.setExpirationTime(NumericDate.fromSeconds(now + 30));
        claims.setStringClaim("cpk", Base64.getEncoder().encodeToString(backendKey.getEncoded()));
        claims.setStringClaim("xid", xuid);
        claims.setStringClaim("xname", identity.displayName());
        claims.setStringClaim("leguuid", identity.uuid().toString());
        claims.setStringClaim("mid", data.minecraftId == null ? "" : data.minecraftId);
        claims.setStringClaim("ipt", address.getAddress().getHostAddress().split("%", 2)[0]);

        var signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        signature.setKey(signingKey);
        signature.setPayload(claims.toJson());
        return signature.getCompactSerialization();
    }
}
