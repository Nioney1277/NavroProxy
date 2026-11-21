import ac.navro.proxy.NavroProxy;

import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jws.JsonWebSignature;

import java.util.Arrays;


public final class LoginCryptoPreflight {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();

        var keys = EncryptionUtils.createKeyPair();
        byte[] token = EncryptionUtils.generateRandomToken();
        String handshake = EncryptionUtils.createHandshakeJwt(keys, token);

        var signed = new JsonWebSignature();
        signed.setCompactSerialization(handshake);
        signed.setKey(keys.getPublic());
        if (!"ES384".equals(signed.getAlgorithmHeaderValue()) || !signed.verifySignature()) {
            throw new IllegalStateException("Generated Bedrock handshake signature did not verify");
        }
        System.out.println(
                "LOGIN_CRYPTO_READY elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        if (args.length > 0 && args[0].equals("--start-proxy")) {
            NavroProxy.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0) {
            throw new IllegalArgumentException(
                    "Expected --start-proxy followed by proxy arguments");
        }
    }
}
