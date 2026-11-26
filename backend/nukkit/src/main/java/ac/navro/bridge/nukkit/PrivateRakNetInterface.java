package ac.navro.bridge.nukkit;

import cn.nukkit.Server;
import cn.nukkit.network.RakNetInterface;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.Config;

import java.net.InetAddress;
import java.nio.file.Path;

/** Separate RakNet listener which assigns the proxy-authenticated player implementation. */
final class PrivateRakNetInterface extends RakNetInterface {
    private final PrivateRakNetLoginTrust trust;

    static PrivateRakNetInterface start(NavroFireworkBridge plugin) throws Exception {
        var values = plugin.getConfig().getSection("private-raknet");
        String bind = values.getString("bind-address", "127.0.0.1");
        int port = values.getInt("port", 19134);
        if (port < 1 || port > 65535 || port == plugin.getServer().getPort())
            throw new IllegalArgumentException("private-raknet.port must differ from the public RakNet port");
        if (plugin.getServer().isIpv6Enabled() && port == plugin.getServer().getIpv6Port())
            throw new IllegalArgumentException("private-raknet.port must differ from the public IPv6 RakNet port");
        InetAddress address = InetAddress.getByName(bind);
        if (!address.getHostAddress().equals(bind) && !bind.equalsIgnoreCase("localhost"))
            throw new IllegalArgumentException("private-raknet.bind-address must be a literal IP address");
        String keyFile = values.getString("proxy-public-key-file", "navro-backend.pub.pem");
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path publicKey = dataFolder.resolve(keyFile).normalize();
        if (!publicKey.startsWith(dataFolder)) throw new IllegalArgumentException("proxy-public-key-file must stay inside the plugin data folder");
        return new PrivateRakNetInterface(plugin.getServer(), bind, port, new PrivateRakNetLoginTrust(publicKey));
    }

    private PrivateRakNetInterface(Server server, String bind, int port, PrivateRakNetLoginTrust trust) {
        super(server, bind, port, PrivateRakNetPlayer.class, true);
        this.trust = trust;
    }

    PrivateRakNetLoginTrust.ForwardedIdentity verify(byte[] loginPayload) throws Exception {
        return trust.verifyLogin(loginPayload);
    }
}
