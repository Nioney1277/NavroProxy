package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerCreationEvent;
import cn.nukkit.network.*;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.session.NetherNetPlayerSession;
import cn.nukkit.network.session.NetworkPlayerSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.*;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A separately authenticated listener; decoding and session budgets stay in Nukkit-MOT. */
public final class PrivateNetherNet implements AdvancedSourceInterface {
    private final Server server;
    private final NetherNetInterface nativeInterface;
    private final NavroFireworkBridge plugin;
    private final PrivateNetherNetSettings settings;
    private final Map<InetSocketAddress, Session> sessions = new LinkedHashMap<>();
    private final Set<Session> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Queue<Session> ready = new ConcurrentLinkedQueue<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final EventLoopGroup group;
    private final Channel channel;
    private volatile boolean closed;
    private int cursor;

    static PrivateNetherNet start(NavroFireworkBridge plugin) throws Exception {
        if (plugin.getServer().xboxAuth)
            throw new IllegalStateException("Private proxy logins require xbox-auth=off on this Nukkit-MOT core; player authentication remains on Navro");
        var nativeInterface = plugin.getServer().getNetwork().getInterfaces().stream()
                .filter(NetherNetInterface.class::isInstance).map(NetherNetInterface.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("Enable Nukkit-MOT network-settings.nethernet.enabled to supply the native session decoder"));
        var settings = PrivateNetherNetSettings.parse(plugin.getConfig().getSection("private-nethernet"), plugin.getDataFolder().toPath());
        var result = new PrivateNetherNet(plugin, nativeInterface, settings);
        plugin.getServer().getNetwork().registerInterface(result);
        return result;
    }

    PrivateNetherNet(NavroFireworkBridge plugin, NetherNetInterface nativeInterface, PrivateNetherNetSettings settings) throws Exception {
        this.plugin = plugin; this.server = plugin.getServer(); this.nativeInterface = nativeInterface; this.settings = settings;
        var trust = new ProxyTokenTrust(settings.publicKey());
        Files.createDirectories(settings.identityFile().getParent());
        var identity = OperatorIdentity.fromPemOrCreate(settings.identityFile().toFile(), "Navro private backend");
        var signaling = new NetherNetHTTPServerSignaling.Builder().setIdentity(identity).setServeHttp(true)
                .setIceOnLocalPort(false).setTokenTrust(trust).setAdvertisedAddresses(List.of(settings.advertisedAddress()))
                .setMaxConnectionsPerAddress(settings.maxConnections()).setMaxPendingJoins(Math.min(32,settings.maxConnections()))
                .setPlayerFilter((host,player) -> closed || connections.get() >= settings.maxConnections() ? JoinRefusal.FULL : null)
                .setMotdProvider((host,remote,clientInfo) -> new PongData.Builder().setServerName(server.getMotd())
                        .setProtocol(ProtocolInfo.CURRENT_PROTOCOL).setVersion(ProtocolInfo.MINECRAFT_VERSION_NETWORK)
                        .setPlayerCount(server.getOnlinePlayers().size()).setMaxPlayerCount(server.getMaxPlayers())
                        .setOnlineAuth(false).setSelfSignedAuth(true).setNonce("0000000000000000").build()).build();
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        var peer = PeerConnectionConfiguration.DEFAULT.withEnableIceUdpMux(true)
                .withPortRangeBegin(settings.iceBegin()).withPortRangeEnd(settings.iceEnd());
        if (!settings.iceBind().isAnyLocalAddress()) peer = peer.withBindAddress(settings.iceBind());
        try {
            var bound = new ServerBootstrap().group(group).channelFactory(NetherNetChannelFactory.server(signaling))
                    .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG,peer)
                    .option(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS,30)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            if (closed) {channel.close();return;}
                            if (connections.incrementAndGet() > settings.maxConnections()) {
                                connections.decrementAndGet(); channel.close(); return;
                            }
                            channel.closeFuture().addListener(ignored -> connections.decrementAndGet());
                            var session = new Session((NetherNetChildChannel)channel);
                            pending.add(session); channel.pipeline().addLast("navro-private-session", session);
                        }
                    }).bind(new InetSocketAddress(settings.signalingBind(),settings.signalingPort())).awaitUninterruptibly();
            if (!bound.isSuccess()) throw new IllegalStateException("Could not bind private NetherNet listener",bound.cause());
            channel = bound.channel();
        } catch (Throwable error) {
            signaling.close();group.shutdownGracefully(0,1,TimeUnit.SECONDS).awaitUninterruptibly();throw error;
        }
    }

    final class Session extends NetherNetPlayerSession {
        private boolean queued;
        Session(NetherNetChildChannel channel) { super(nativeInterface,channel); }
        @Override public void channelActive(ChannelHandlerContext context) {
            if (!queued) {
                queued=true;
                getState().getConnection().setQueuedForPlayerCreation(true);
                getState().getConnection().setQueuedForPlayerCreationNanos(System.nanoTime());
                ready.offer(this);
            }
        }
    }

    @Override public boolean process() {
        for (Session session; (session=ready.poll())!=null;) {
            pending.remove(session);
            if (closed || session.getDisconnectReason()!=null || !session.getChannel().isActive()) continue;
            var address=(InetSocketAddress)session.getChannel().remoteAddress();
            if (sessions.containsKey(address)) {session.disconnect("Duplicate proxy endpoint");continue;}
            try {
                var event=new PlayerCreationEvent(this,PrivateNetherNetPlayer.class,PrivateNetherNetPlayer.class,null,address);
                server.getPluginManager().callEvent(event);
                if (!PrivateNetherNetPlayer.class.isAssignableFrom(event.getPlayerClass()))
                    throw new IllegalStateException("Private NetherNet needs its authenticated player class");
                sessions.put(address,session);
                Player player=event.getPlayerClass().getConstructor(SourceInterface.class,Long.class,InetSocketAddress.class)
                        .newInstance(this,event.getClientId(),address);
                player.raknetProtocol=11;session.setPlayer(player);server.addPlayer(address,player);
            } catch (Exception error) {
                sessions.remove(address);session.disconnect("Private backend player creation failed");
                plugin.getLogger().error("Private NetherNet player creation failed",error);
            }
        }
        long now=System.nanoTime();
        for (Session session:new ArrayList<>(pending)) {
            if (closed || session.getDisconnectReason()!=null || !session.getChannel().isOpen()) pending.remove(session);
            else if (session.isPendingLoginTimedOut(now,30000)) {pending.remove(session);session.disconnect("Login timeout");}
        }
        int packets=4096;long bytes=16L<<20;
        var entries=new ArrayList<>(sessions.entrySet());
        int start=entries.isEmpty()?0:Math.floorMod(cursor,entries.size());
        for (int index=0;index<entries.size();index++) {
            var entry=entries.get((start+index)%entries.size());
            Session session=entry.getValue();Player player=session.getPlayer();
            if (session.getDisconnectReason()!=null || !session.getChannel().isActive()) {
                sessions.remove(entry.getKey());
                if (player!=null) player.close(player.getLeaveMessage(),Objects.requireNonNullElse(session.getDisconnectReason(),"Transport closed"),false);
                continue;
            }
            if (session.isLoginPhaseTimedOut(now,30000)) {session.disconnect("Login timeout");continue;}
            if (packets>0 && bytes>0) {
                var drained=session.serverTick(Math.min(256,packets),Math.min(1L<<20,bytes));
                packets-=drained.packets();bytes-=drained.bytes();
            }
        }
        cursor=start+1;
        return true;
    }
    boolean owns(Player player) { return player instanceof PrivateNetherNetPlayer p && p.source()==this; }
    @Override public NetworkPlayerSession getSession(InetSocketAddress address) {return sessions.get(address);}
    @Override public int getNetworkLatency(Player player) {return (int)player.getNetworkSession().getPing();}
    @Override public void close(Player player) {close(player,"Connection closed");}
    @Override public void close(Player player,String reason) {player.getNetworkSession().disconnect(reason);}
    @Override public void setName(String name) { }
    @Override public void setNetwork(Network network) { }
    @Override public void blockAddress(InetAddress address) {nativeInterface.blockAddress(address);}
    @Override public void blockAddress(InetAddress address,int timeout) {nativeInterface.blockAddress(address,timeout);}
    @Override public void unblockAddress(InetAddress address) {nativeInterface.unblockAddress(address);}
    @Override public void sendRawPacket(InetSocketAddress address,ByteBuf buffer) { }
    @Override public void shutdown() {
        if (closed) return;closed=true;
        for (Session session:pending) session.getChannel().close();
        for (Session session:new ArrayList<>(sessions.values())) {
            Player player=session.getPlayer();
            if (player!=null) player.close(player.getLeaveMessage(),"Plugin disabled",false);
            session.disconnect("Plugin disabled");session.getChannel().close();
        }
        channel.close().awaitUninterruptibly();group.shutdownGracefully(0,2,TimeUnit.SECONDS).awaitUninterruptibly();
        pending.clear();ready.clear();sessions.clear();server.getNetwork().unregisterInterface(this);
    }
    @Override public void emergencyShutdown() {shutdown();}
}
