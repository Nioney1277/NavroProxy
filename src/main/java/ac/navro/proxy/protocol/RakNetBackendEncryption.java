package ac.navro.proxy.protocol;

import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jws.JsonWebSignature;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
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
