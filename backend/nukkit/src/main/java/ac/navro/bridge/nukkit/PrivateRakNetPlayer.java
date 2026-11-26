package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.encryption.EncryptionUtils;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.LoginPacket;
import cn.nukkit.utils.Binary;
import cn.nukkit.utils.LoginChainData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.UUID;

/** Player implementation used only by the RSA-authenticated private RakNet interface. */
public final class PrivateRakNetPlayer extends Player {
    private final PrivateRakNetInterface source;
    private UUID forwardedUuid;
    private String forwardedXuid;
    private boolean identityApplied;

    public PrivateRakNetPlayer(SourceInterface source, Long clientId, InetSocketAddress address) {
        super(source, clientId, address);
        if (!(source instanceof PrivateRakNetInterface privateInterface))
            throw new IllegalArgumentException("PrivateRakNetPlayer requires the private RakNet listener");
        this.source = privateInterface;
    }

    @Override public boolean isEnableNetworkEncryption() { return false; }

    @Override public void handleDataPacket(DataPacket packet) {
        if (packet instanceof LoginPacket login) {
            try {
                if (forwardedUuid != null) throw new IllegalArgumentException("Duplicate proxy login");
                var identity = source.verify(login.getBuffer());
                forwardedUuid = identity.uuid();
                forwardedXuid = identity.xuid();
            } catch (Exception error) {
                getNetworkSession().disconnect("Invalid authenticated proxy login");
                return;
            }
        }
        super.handleDataPacket(packet);
    }

    @Override protected void processPreLogin() {
        if (forwardedUuid == null || loginChainData == null) {
            getNetworkSession().disconnect("Missing authenticated proxy login");
            return;
        }
        applyForwardedIdentity();
        super.processPreLogin();
    }

    private void applyForwardedIdentity() {
        if (identityApplied || forwardedUuid == null || loginChainData == null) return;
        LoginChainData original = loginChainData;
        loginChainData = (LoginChainData) Proxy.newProxyInstance(LoginChainData.class.getClassLoader(),
                new Class<?>[]{LoginChainData.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getClientUUID")) return forwardedUuid;
                    if (method.getName().equals("getXUID")) return forwardedXuid;
                    try { return method.invoke(original, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        uuid = forwardedUuid;
        rawUUID = Binary.writeUUID(uuid);
        identityApplied = true;
    }
}
