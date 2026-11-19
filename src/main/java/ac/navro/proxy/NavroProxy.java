package ac.navro.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;


public final class NavroProxy {
    private final ac.navro.proxy.alert.AlertManager alertManager;
    private final ac.navro.proxy.permission.ProxyPermissions permissions =
            new ac.navro.proxy.permission.ProxyPermissions(java.nio.file.Path.of("permissions.json"),System.out::println);
    public ac.navro.proxy.permission.ProxyPermissions permissions(){return permissions;}
    private final java.util.Map<java.util.UUID, java.util.function.Consumer<String>> onlineAlerts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ProxyConfig config;
    private final ac.navro.proxy.command.CommandRuntime commands;
    public ac.navro.proxy.command.CommandRuntime commands(){return commands;}
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup childGroup = new NioEventLoopGroup();
    private final EventLoopGroup backendGroup = new NioEventLoopGroup();
    private Channel serverChannel;

    public NavroProxy(ProxyConfig config) {
        this.config = config;
        commands=new ac.navro.proxy.command.CommandRuntime(java.nio.file.Path.of("config.yml"),config.smoothSetback(),config.maxBalanceAdvantageMs());
        java.nio.file.Path subscriptions = java.nio.file.Path.of("alert-subscriptions.json");
        boolean firstRun = !java.nio.file.Files.exists(subscriptions);
        alertManager = new ac.navro.proxy.alert.AlertManager(subscriptions, ()->commands.config().prefix(),
                System.out::println, () -> java.util.Map.copyOf(onlineAlerts));

        if (firstRun) alertManager.setAlert(ac.navro.proxy.alert.AlertManager.CONSOLE_UUID, true);
    }

    public ac.navro.proxy.alert.AlertManager getAlertManager() { return alertManager; }
    void registerAlertRecipient(java.util.UUID id, java.util.function.Consumer<String> recipient) {
        onlineAlerts.put(id, recipient);
    }
    void removeAlertRecipient(java.util.UUID id, java.util.function.Consumer<String> recipient) {
        onlineAlerts.remove(id, recipient);
    }

    public static void main(String[] args) throws Exception {
        io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory(
                io.netty.util.internal.logging.JdkLoggerFactory.INSTANCE);
        ProxyConfig config;
        try {
            config = ProxyConfig.parse(args);
        } catch (RuntimeException error) {
            System.err.println(error.getMessage());
            ProxyConfig.defaults();
            ProxyConfig.parse(new String[]{"--help"});
            return;
        }

        NavroProxy proxy = new NavroProxy(config);
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::shutdown, "navro-proxy-shutdown"));
        try {
            proxy.start();
        } catch (Throwable error) {
            proxy.shutdown();
            throw error;
        }
        ac.navro.proxy.command.ConsoleCommands.start(proxy.commands(),proxy.getAlertManager());
        new CountDownLatch(1).await();
    }

    public ProxyConfig config() {
        return config;
    }

    EventLoopGroup backendGroup() {
        return backendGroup;
    }

    public void start() throws InterruptedException {
        if(config.mode()==ProxyConfig.Mode.DETECT)ac.navro.proxy.data.inventory.NativePredictionItems.initialize();
        var latestCodec = SupportedCodecs.fallback(2225);
        long guid = ThreadLocalRandom.current().nextLong();
        var advertisement = new BedrockPong().edition("MCPE").motd("NavroProxy")
                .subMotd("Navro").protocolVersion(latestCodec.getProtocolVersion())
                .version(latestCodec.getMinecraftVersion()).playerCount(0).maximumPlayerCount(50)
                .serverId(guid).gameType("Survival").nintendoLimited(false)
                .ipv4Port(config.listenPort()).ipv6Port(config.listenPort()).toByteBuf();
        BedrockServerInitializer initializer = new BedrockServerInitializer() {
            @Override
            protected void postInitChannel(Channel channel) {
                PacketRelay.install(channel);
            }

            @Override
            protected void preInitChannel(Channel channel) throws Exception {

                channel.config().setOption(RakChannelOption.RAK_PROTOCOL_VERSION, 11);
                super.preInitChannel(channel);
            }
            @Override
            public void initSession(BedrockServerSession session) {
                log("accepted Bedrock session from " + session.getSocketAddress());
                new ProxySession(NavroProxy.this, session);
            }
        };

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, childGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_GUID, guid)
                .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement)
                .option(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.INVALID)
                .childHandler(initializer);

        InetSocketAddress address = new InetSocketAddress(config.listenHost(), config.listenPort());
        ChannelFuture future = bootstrap.bind(address).sync();
        serverChannel = future.channel();
        log("listening on " + address + ", backend " + config.backendHost() + ":" + config.backendPort()
                + ", fallback protocol " + config.fallbackProtocolVersion() + ", mode " + config.mode());
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        backendGroup.shutdownGracefully();
        childGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
    }

    void log(String message) {
        System.out.println("[NavroProxy] " + message);
    }
}
