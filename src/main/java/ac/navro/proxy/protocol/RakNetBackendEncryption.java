package ac.navro.proxy.protocol;

import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jws.JsonWebSignature;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

public final class RakNetBackendEncryption {
    private final KeyPair keys = EncryptionUtils.createKeyPair();

    public LoginPacket login(int protocol, LoginIdentity identity, Map<String, Object> claims,
                             byte[] verifiedClientData) throws Exception {
        return PrivateBackendLogin.encode(protocol, identity, claims, sanitize(verifiedClientData), keys);
    }

    public LoginPacket forwardedLogin(int protocol, ChainValidationResult verifiedChain, byte[] verifiedClientData,
                                      InetSocketAddress address, FloodgateForwarding forwarding) throws Exception {
        return PrivateBackendLogin.packet(protocol, forwarding.token(verifiedChain, address, keys.getPublic()),
                sanitize(verifiedClientData), keys);
    }

    private static byte[] sanitize(byte[] verifiedClientData) {
        var clientData = JsonParser.parseString(new String(verifiedClientData, StandardCharsets.UTF_8)).getAsJsonObject();
        clientData.remove("Waterdog_IP");
        clientData.remove("Waterdog_XUID");
        return clientData.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void complete(BedrockClientSession backend, String jwt) throws Exception {
        backend.enableEncryption(sessionKey(jwt));
        backend.sendPacketImmediately(new ClientToServerHandshakePacket());
    }

    public BatchCipher completeBatchHandshake(String jwt) throws Exception {
        return new BatchCipher(sessionKey(jwt));
    }

    public static final class BatchCipher {
        private static final int TRAILER_SIZE = 8;
        private final Cipher encoder;
        private final Cipher decoder;
        private final byte[] key;
        private final MessageDigest digest;
        private long sent;
        private long received;

        public BatchCipher(SecretKey secret) throws NoSuchAlgorithmException {
            encoder = EncryptionUtils.createCipher(true, true, secret);
            decoder = EncryptionUtils.createCipher(true, false, secret);
            key = secret.getEncoded();
            digest = MessageDigest.getInstance("SHA-256");
        }

        public byte[] encrypt(byte[] batch) {
            byte[] framed = Arrays.copyOf(batch, batch.length + TRAILER_SIZE);
            System.arraycopy(trailer(sent++, batch, batch.length), 0, framed, batch.length, TRAILER_SIZE);
            return encoder.update(framed);
        }

        public byte[] decrypt(byte[] batch) throws IOException {
            if (batch.length <= TRAILER_SIZE) throw new IOException("Truncated encrypted RakNet batch");
            byte[] clear = decoder.update(batch);
            int size = clear.length - TRAILER_SIZE;
            byte[] checksum = Arrays.copyOfRange(clear, size, clear.length);
            if (!MessageDigest.isEqual(checksum, trailer(received++, clear, size))) {
                throw new IOException("Invalid encrypted RakNet batch checksum");
            }
            return Arrays.copyOf(clear, size);
        }

        private byte[] trailer(long counter, byte[] batch, int size) {
            digest.update(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array());
            digest.update(batch, 0, size);
            return Arrays.copyOf(digest.digest(key), TRAILER_SIZE);
        }
    }

    SecretKey sessionKey(String jwt) throws Exception {
        var handshake = new JsonWebSignature();
        handshake.setCompactSerialization(jwt);
        if (!"ES384".equals(handshake.getAlgorithmHeaderValue())) {
            throw new IllegalArgumentException("Invalid backend handshake algorithm");
        }
        var publicKey = EncryptionUtils.parseKey(handshake.getHeader("x5u"));
        handshake.setKey(publicKey);
        if (!handshake.verifySignature()) throw new IllegalArgumentException("Invalid backend handshake signature");
        var salt = JsonParser.parseString(handshake.getPayload()).getAsJsonObject().get("salt");
        if (salt == null || !salt.isJsonPrimitive() || !salt.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing backend handshake salt");
        }
        byte[] token = Base64.getDecoder().decode(salt.getAsString());
        if (token.length != 16) throw new IllegalArgumentException("Invalid backend handshake salt");
        return EncryptionUtils.getSecretKey(keys.getPrivate(), publicKey, token);
    }
}
