package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.encryption.EncryptionUtils;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.LoginPacket;
import cn.nukkit.utils.Binary;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.LoginChainData;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/** Only constructed by the RSA-authenticated listener. Public listeners retain normal Player. */
public class PrivateNetherNetPlayer extends Player {
    private final PrivateNetherNet source;
    private UUID forwardedUuid;
    private String forwardedXuid;
    private boolean identityApplied;

    public PrivateNetherNetPlayer(SourceInterface source,Long clientId,InetSocketAddress address) {
        super(source,clientId,address);this.source=(PrivateNetherNet)source;
    }
    PrivateNetherNet source() {return source;}
    @Override public boolean isEnableNetworkEncryption() {return false;}

    @Override public void handleDataPacket(DataPacket packet) {
        if (packet instanceof LoginPacket login) {
            try {
                if (forwardedUuid!=null) throw new IllegalArgumentException("Duplicate forwarded login");
                var session=(cn.nukkit.network.session.NetherNetPlayerSession)getNetworkSession();
                var claims=verifyForwardedLogin(login.getBuffer(), session.getChannel());
                forwardedUuid=UUID.fromString(claims.getStringClaimValue("leguuid"));
                forwardedXuid=claims.getStringClaimValue("xid");
                if (forwardedXuid==null || !forwardedXuid.matches("[0-9]{0,20}"))
                    throw new IllegalArgumentException("Invalid forwarded XUID");
            } catch (Exception error) {
                getNetworkSession().disconnect("Invalid authenticated proxy login");return;
            }
        }
        super.handleDataPacket(packet);
    }

    static JwtClaims verifyForwardedLogin(byte[] payload,io.netty.channel.Channel channel) throws Exception {
        var input=new BinaryStream(payload);
        String token=ForwardedLoginToken.read(input);
        if (token.length()>60000 || token.split("\\.").length!=3)
            throw new IllegalArgumentException("Invalid forwarded token");
        var preview=JwtClaims.parse(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8));
        var key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(preview.getStringClaimValue("cpk"))));
        if (TransportIdentityBinding.state(channel)==TransportIdentityBinding.State.NONE)
            throw new IllegalArgumentException("Missing authenticated transport binding");
        PlayerInfo peer=channel.attr(NetherNetChildChannel.PLAYER_INFO).get();
        if (peer==null || !java.security.MessageDigest.isEqual(peer.clientPublicKey().getEncoded(),key.getEncoded()))
            throw new IllegalArgumentException("Login key differs from authenticated proxy session");
        String refusal=TransportIdentityBinding.mismatch(channel,key);
        if (refusal!=null) throw new IllegalArgumentException(refusal);
        var claims=new JwtConsumerBuilder().setVerificationKey(key)
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,"ES384")
                .setExpectedAudience("api://auth-minecraft-services/multiplayer")
                .setRequireExpirationTime().setRequireIssuedAt().setAllowedClockSkewInSeconds(5)
                .build().processToClaims(token);
        UUID.fromString(claims.getStringClaimValue("leguuid"));
        String name=claims.getStringClaimValue("xname");
        if (name==null || name.isBlank()) throw new IllegalArgumentException("Missing forwarded name");
        int size=input.getLInt();
        if (size<1 || size>3*1024*1024) throw new IllegalArgumentException("Invalid client data size");
        String clientToken=new String(input.get(size),java.nio.charset.StandardCharsets.UTF_8);
        if (EncryptionUtils.verifyClientData(clientToken,key)==null) throw new IllegalArgumentException("Invalid client data signature");
        return claims;
    }

    @Override protected void processPreLogin() {
        if (forwardedUuid==null || loginChainData==null) {
            getNetworkSession().disconnect("Missing authenticated proxy login");return;
        }
        applyForwardedIdentity();
        super.processPreLogin();
    }

    void applyForwardedIdentity() {
        if (identityApplied || forwardedUuid==null || loginChainData==null) return;
        // The core verified the self-signed chain; keep the forwarded account UUID/XUID
        // instead of the offline-name UUID. Do not label it a Microsoft signature.
        LoginChainData original=loginChainData;
        loginChainData=(LoginChainData)Proxy.newProxyInstance(LoginChainData.class.getClassLoader(),new Class<?>[]{LoginChainData.class},(proxy,method,args) -> {
            if (method.getName().equals("getClientUUID")) return forwardedUuid;
            if (method.getName().equals("getXUID")) return forwardedXuid;
            try {return method.invoke(original,args);}
            catch (InvocationTargetException error) {throw error.getCause();}
        });
        uuid=forwardedUuid;rawUUID=Binary.writeUUID(uuid);
        identityApplied=true;
    }
}
