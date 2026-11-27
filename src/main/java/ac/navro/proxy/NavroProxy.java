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
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/** Standalone Bedrock proxy entry point. */
public final class NavroProxy {
    private final ac.navro.proxy.alert.AlertManager alertManager;
    private final ac.navro.proxy.permission.ProxyPermissions permissions =
            new ac.navro.proxy.permission.ProxyPermissions(java.nio.file.Path.of("permissions.json"),System.out::println);
    public ac.navro.proxy.permission.ProxyPermissions permissions(){return permissions;}
    private final java.util.Map<java.util.UUID, java.util.function.Consumer<String>> onlineAlerts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ProxyConfig config;
    private final java.security.PrivateKey backendSigningKey;
    private ac.navro.proxy.protocol.FloodgateForwarding floodgateForwarding;
    private final ac.navro.proxy.command.CommandRuntime commands;
    public ac.navro.proxy.command.CommandRuntime commands(){return commands;}
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup childGroup = new NioEventLoopGroup();
    private final EventLoopGroup backendGroup = new NioEventLoopGroup();
    private Channel serverChannel;
    private ac.navro.proxy.protocol.BackendAdvertisement backendAdvertisement;

    public NavroProxy(ProxyConfig config) {
        this.config = config;
        try {
            this.backendSigningKey = config.pmmpNgBackendAuth()
                    ? ac.navro.proxy.protocol.PrivateBackendLogin.loadProxySigningKey(config.backendSigningKey()) : null;
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not load the PMMP-NG backend signing key", error);
        }
        commands=new ac.navro.proxy.command.CommandRuntime(ac.navro.proxy.config.ConfigLoader.DEFAULT_FILE,config.smoothSetback(),config.maxBalanceAdvantageMs());
        java.nio.file.Path subscriptions = java.nio.file.Path.of("alert-subscriptions.json");
        boolean firstRun = !java.nio.file.Files.exists(subscriptions);
        alertManager = new ac.navro.proxy.alert.AlertManager(subscriptions, ()->commands.config().prefix(),
                System.out::println, () -> java.util.Map.copyOf(onlineAlerts));
        // Console alerts were requested for this proxy test; preserve later subscription changes.
        if (firstRun) alertManager.setAlert(ac.navro.proxy.alert.AlertManager.CONSOLE_UUID, true);
    }

    java.security.PrivateKey backendSigningKey() { return backendSigningKey; }

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
        if (args.length == 0) {
            var network = NetworkConfig.load();
            if (network.usesNetherNetFrontend() || network.backendTransport().equals("nethernet")) {
                runNetherNetStack(network);
                return;
            }
            String backendAddress = network.backendTransport().equals("raknet")
                    ? unbracketAddress(network.backendAddress()) : network.backendAddress();
            String listenAddress = unbracketAddress(network.listen());
            var options = new ArrayList<>(List.of("--listen", listenAddress, "--backend", backendAddress,
                    "--backend-type", network.backendType()));
            if (network.smoothSetback()) options.add("--smooth-setback");
            if (network.backendEncryption()) options.add("--encrypt-backend");
            if (network.backendAuth().equals("pmmp-ng")) {
                options.add("--backend-auth"); options.add("pmmp-ng");
                options.add("--backend-signing-key"); options.add(network.backendSigningKey().toString());
            }
            args = options.toArray(String[]::new);
        }
        ProxyConfig config;
        try {
            config = ProxyConfig.parse(args);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(error.getMessage() + "; use --help for supported options", error);
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

    private static void runNetherNetStack(NetworkConfig config) throws Exception {
        Path root = distributionRoot();
        String javaCommand = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        String adapter = "127.0.0.1:" + config.adapterPort();
        String adapterClasspath = root.resolve("lib") + java.io.File.separator + "*";
        var adapterCommand = new ArrayList<>(List.of(javaCommand, "-cp", adapterClasspath,
                "ac.navro.proxy.nethernet.NetherNetAdapter", Integer.toString(config.adapterPort()),
                "--backend-type", config.backendType()));
        if (config.backendAuth().equals("pmmp-ng")) {
            adapterCommand.add("--backend-signing-key"); adapterCommand.add(config.backendSigningKey().toString());
        }
        if (config.smoothSetback()) adapterCommand.add("--smooth-setback");
        if (config.backendStateSync()) adapterCommand.add("--backend-state-sync");
        for (Path palette : config.blockPalettes()) {
            adapterCommand.add("--block-palette");
            adapterCommand.add(palette.toString());
        }
        Process adapterProcess = new ProcessBuilder(adapterCommand).redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true).start();
        Thread shutdown = new Thread(() -> stop(adapterProcess), "navro-adapter-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            waitForReady(adapterProcess, "NETHERNET_ADAPTER_READY", "Java packet adapter");
            Path frontend = root.resolve("bin").resolve(System.getProperty("os.name").toLowerCase().contains("windows")
                    ? "navro-nethernet.exe" : "navro-nethernet");
            if (!Files.isRegularFile(frontend)) throw new IOException("Missing bundled NetherNet transport: " + frontend);
            if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
                try {
                    var permissions = Files.getPosixFilePermissions(frontend);
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                    permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(frontend, permissions);
                } catch (UnsupportedOperationException ignored) { }
            }
            var command = new ArrayList<>(List.of(frontend.toString(), "-frontend", config.frontend(),
                    "-listen", config.listen(), "-backend", config.backendUrl(), "-adapter", adapter,
                    "-backend-auth", config.backendAuth()));
            if (config.backendAuth().equals("pmmp-ng")) {
                command.add("-backend-signing-key");
                command.add(config.backendSigningKey().toString());
            }
            if (!config.localIp().isBlank()) { command.add("-local-ip"); command.add(config.localIp()); }
            Process transport = new ProcessBuilder(command).redirectErrorStream(true).start();
            Thread transportShutdown = new Thread(() -> stop(transport), "navro-transport-shutdown");
            Runtime.getRuntime().addShutdownHook(transportShutdown);
            adapterProcess.onExit().thenRun(() -> stop(transport));
            try {
                waitForReady(transport, "NETHERNET_TERMINATING_READY", "NetherNet transport", "RAKNET_NETHERNET_READY");
                int exit = transport.waitFor();
                if (!adapterProcess.isAlive()) throw new IOException("Java packet adapter exited unexpectedly");
                if (exit != 0) throw new IOException("NetherNet transport exited with status " + exit);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                throw new IOException("NetherNet transport failed to start", error);
            } finally {
                stop(transport);
                try { Runtime.getRuntime().removeShutdownHook(transportShutdown); } catch (IllegalStateException ignored) { }
            }
        } finally {
            stop(adapterProcess);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }

    private static Path distributionRoot() throws Exception {
        Path location = Path.of(NavroProxy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path root = Files.isDirectory(location) ? location : location.getParent().getParent();
        if (!Files.isDirectory(root.resolve("lib"))) root = Path.of("").toAbsolutePath();
        return root;
    }

    private static void waitForReady(Process process, String readyMarker, String name, String... alternateMarkers)
            throws java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException, InterruptedException {
        var ready = new CompletableFuture<Void>();
        process.onExit().thenAccept(exited -> ready.completeExceptionally(
                new IOException(name + " exited with status " + exited.exitValue())));
        Thread output = new Thread(() -> {
            try (var reader = process.inputReader()) {
                for (String line; (line = reader.readLine()) != null;) {
                    System.out.println("[" + name + "] " + line);
                    if (line.contains(readyMarker) || java.util.Arrays.stream(alternateMarkers).anyMatch(line::contains)) {
                        ready.complete(null);
                    }
                }
                ready.completeExceptionally(new IOException(name + " closed its output before becoming ready"));
            } catch (IOException error) { ready.completeExceptionally(error); }
        }, "navro-" + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + "-output");
        output.setDaemon(true);
        output.start();
        ready.get(30, TimeUnit.SECONDS);
    }

    private static void stop(Process process) {
        if (process.isAlive()) {
            process.destroy();
            try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }
    }

    private static String unbracketAddress(String value) {
        if (!value.startsWith("[")) return value;
        int close = value.indexOf(']');
        return close < 0 ? value : value.substring(1, close) + value.substring(close + 1);
    }

    public ProxyConfig config() {
        return config;
    }

    ac.navro.proxy.protocol.FloodgateForwarding floodgateForwarding() {
        return floodgateForwarding;
    }

    EventLoopGroup backendGroup() {
        return backendGroup;
    }

    public void start() throws InterruptedException, java.io.IOException, java.security.GeneralSecurityException {
        if (config.floodgateForwardingKey() != null) {
            floodgateForwarding = ac.navro.proxy.protocol.FloodgateForwarding.load(config.floodgateForwardingKey());
        }
        ac.navro.proxy.data.inventory.NativePredictionItems.initialize();
        long guid = ThreadLocalRandom.current().nextLong();
        backendAdvertisement = new ac.navro.proxy.protocol.BackendAdvertisement(guid);
        BedrockServerInitializer initializer = new BedrockServerInitializer() {
            @Override
            protected void postInitChannel(Channel channel) {
                PacketRelay.install(channel);
            }

            @Override
            protected void preInitChannel(Channel channel) throws Exception {
                // Cookie transport versions use the same Bedrock framing as RakNet 11.
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
                .option(RakChannelOption.RAK_HANDLE_PING, true)
                .option(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.INVALID)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{9, 10, 11, 12})
                .childHandler(new io.netty.channel.ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        int raknetVersion = channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
                        channel.pipeline().addLast("navro-connection-routing", new ConnectionRoutingHandler(
                                raknetVersion,
                                selected -> selected.pipeline().addLast(initializer),
                                (selected, declaration) -> NativeRakNetForwarder.install(
                                        selected, config, declaration.raknetVersion(), declaration.protocol())));
                    }
                });

        InetSocketAddress address = new InetSocketAddress(config.listenHost(), config.listenPort());
        ChannelFuture future = bootstrap.bind(address).sync();
        serverChannel = future.channel();
        backendAdvertisement.attach(serverChannel);
        backendAdvertisement.start(backendGroup, new InetSocketAddress(config.backendHost(), config.backendPort()));
        log("listening on " + address + ", backend " + config.backendHost() + ":" + config.backendPort()
                + ", fallback protocol " + config.fallbackProtocolVersion());
    }

    public void shutdown() {
        if (backendAdvertisement != null) backendAdvertisement.close();
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
