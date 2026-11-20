package org.geysermc.geyser.util;

import io.netty.util.NetUtil;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.UUID;

public final class NavroForwardedLogin {
    private final JwtConsumer consumer;

    public NavroForwardedLogin(PublicKey key) {
        consumer = new JwtConsumerBuilder()
                .setVerificationKey(key)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,
                        AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384)
                .setExpectedAudience(true, "urn:navro:floodgate-forwarding:1")
                .setRequireExpirationTime()
                .setRequireIssuedAt()
                .setIssuedAtRestrictions(5, 35)
                .setMaxFutureValidityInMinutes(1)
                .build();
    }

    public static NavroForwardedLogin configured() {
        return Configuration.INSTANCE;
    }

    private static final class Configuration {
        private static final NavroForwardedLogin INSTANCE = load();

        private static NavroForwardedLogin load() {
            String keyPath = System.getProperty("navro.floodgate.public-key");
            if (keyPath == null) {
                return null;
            }
            try {
                String pem = Files.readString(Path.of(keyPath)).strip();
                String begin = "-----BEGIN PUBLIC KEY-----";
                String end = "-----END PUBLIC KEY-----";
                if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                    throw new IllegalArgumentException("Expected an X.509 PEM public key");
                }
                var key = EncryptionUtils.parseKey(pem.substring(begin.length(), pem.length() - end.length())
                        .replaceAll("\\s", ""));
                if (key.getParams().getCurve().getField().getFieldSize() != 384) {
                    throw new IllegalArgumentException("Navro forwarding requires a P-384 public key");
                }
                return new NavroForwardedLogin(key);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot load navro.floodgate.public-key", ex);
            }
        }
    }

    public Identity verify(AuthPayload payload, SocketAddress peer) throws Exception {
        if (!(peer instanceof InetSocketAddress inet) || inet.getAddress() == null
                || !inet.getAddress().isLoopbackAddress()
                || !(payload instanceof TokenPayload token) || token.getAuthType() != AuthType.SELF_SIGNED) {
            throw new IllegalArgumentException("Invalid Navro forwarding transport or login type");
        }
        var claims = consumer.processToClaims(token.getToken());
        String xuid = claims.getStringClaimValue("xid");
        if (xuid == null || !xuid.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("Invalid forwarded Bedrock XUID");
        }
        Long.parseUnsignedLong(xuid);
        String username = claims.getStringClaimValue("xname");
        String ip = claims.getStringClaimValue("ipt");
        byte[] address = ip == null ? null : NetUtil.createByteArrayFromIpAddressString(ip);
        if (address == null || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Missing forwarded Bedrock identity");
        }
        return new Identity(username, UUID.fromString(claims.getStringClaimValue("leguuid")),
                xuid, claims.getStringClaimValue("mid"), claims.getIssuedAt().getValue(),
                new InetSocketAddress(InetAddress.getByAddress(address), 0),
                EncryptionUtils.parseKey(claims.getStringClaimValue("cpk")));
    }

    public record Identity(String username, UUID uuid, String xuid, String minecraftId, long issuedAt,
                           InetSocketAddress address, PublicKey sessionKey) {
    }
}
