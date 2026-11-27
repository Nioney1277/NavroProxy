package ac.navro.proxy;

import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.protocol.RakNetBackendEncryption;
import ac.navro.proxy.util.EntityBatchEncoder;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.packets.server.ServerChunkPackets;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;


public final class ProxySession {
    private final NavroProxy proxy;
    private final BedrockServerSession client;
    private final Queue<BedrockPacket> queuedClientPackets = new ArrayDeque<>();
    private final SessionState state;
    private final ProtocolDefinitions definitions = new ProtocolDefinitions();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ac.navro.proxy.util.block.BlockCorrectionSender blockCorrections;
    private final ac.navro.proxy.permission.ProtocolPermissions permissions=new ac.navro.proxy.permission.ProtocolPermissions();
    private final ac.navro.proxy.teleport.TeleportSender teleportSender;
    private BedrockCodec codec;
    private BedrockClientSession backend;
    private ChannelFuture backendConnect;
    private boolean clientEncryptionStarted;
    private LoginPacket pendingLogin;
    private boolean clientHandshakeAccepted;
    private boolean backendNetworkSettingsReceived;
    private boolean backendEncryptionReady;
    private RakNetBackendEncryption backendEncryption;
    private io.netty.util.concurrent.ScheduledFuture<?> backendEncryptionTimeout;
    private io.netty.util.concurrent.ScheduledFuture<?> serverTickTask;
    private java.util.UUID identity;
    private java.util.Set<String> proxyCommandNames = ac.navro.proxy.command.NavroCommand.NAMES;
    private final java.util.function.Consumer<String> alertRecipient = this::sendAlertMessage;

    public ProxySession(NavroProxy proxy, BedrockServerSession client) {
        this.proxy = proxy;
        this.state = new SessionState(proxy.config().backendType());
        this.client = client;
        state.setBackendSyncTransport(packet->{if(backendReady())backend.sendPacket(packet);});
        this.teleportSender=new ac.navro.proxy.teleport.TeleportSender(client,state,()->!closed.get()&&clientHandshakeAccepted);
        state.setTeleportTransport(teleportSender::send);
        state.setPredictionMotionTransport(teleportSender::sendMotion);
        state.setSoftCorrectionTransport(teleportSender::send);
        state.setSmoothSetback(proxy.config().smoothSetback());
        state.setCommandRuntime(proxy.commands());
        state.setClientStateCorrectionTransport(client::sendPacket);
        state.player().setTimerTeleport(teleportSender::teleportToLastValid);
        this.blockCorrections = new ac.navro.proxy.util.block.BlockCorrectionSender(client, definitions,
                state.player(), () -> !closed.get() && clientHandshakeAccepted, state::onProxyBlockCorrection);
        state.setBlockCorrectionTransport((position, blocks) ->
                blockCorrections.restoreCorrectBlock(position, blocks.layerZero(), blocks.layerOne()));
        var alerts = proxy.getAlertManager();
        state.player().setFormattedAlertSink(message -> {
            alerts.formattedAlert(message);
            state.forwardBackendAlert(message);
        });
        state.player().setAlertSink(message -> state.player().formattedAlert(alerts.getPrefix() + "§3" + message));
        state.player().setDisconnectSink(reason->client.getPeer().getChannel().eventLoop().execute(()->disconnect(reason)));
        state.player().setCorrectionRequestSink(this::log);
        state.player().setMaxBalanceAdvantageMs(proxy.config().maxBalanceAdvantageMs());
        state.setDetectionEnabled(true);
        if(proxy.config().debug()){
            var trace=new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(state.player(),state.world(),java.util.UUID.randomUUID().toString(),ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fileSink());
            state.player().setWorldDiagnostics(trace);trace.event("session_open",ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields("remote",client.getSocketAddress().toString()));
            log("world/collision diagnostics: "+ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.logPath());
        }
        this.client.setLogging(proxy.config().debug());
        if (state.backendType() == ac.navro.proxy.protocol.BackendType.BDS) {
            var correctionAcknowledgments = new ac.navro.proxy.bds.BdsCorrectionAcknowledgments();
            state.setBdsCorrectionAcknowledgmentDispatcher(correctionAcknowledgments::defer);
            this.client.getPeer().getChannel().pipeline().addBefore(
                    org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder.NAME,
                    "navro-bds-inbound-correction-ack", correctionAcknowledgments);
        }
        this.client.setPacketHandler(new ClientPacketHandler());
        this.client.getPeer().getChannel().closeFuture().addListener(ignored -> close());
        state.placementPipeline().setTransports(packet->{
            if(closed.get()||!backendReady())return false;
            backend.sendPacket(packet);return true;
        },client::sendPacket);
        log("RakNet session created");
    }

    public SessionState state() {
        return state;
    }


    public void restoreCorrectBlock(org.cloudburstmc.math.vector.Vector3i position,
                                    int layerZeroNetworkId, int layerOneNetworkId) {
        blockCorrections.restoreCorrectBlock(position, layerZeroNetworkId, layerOneNetworkId);
    }

    public void restoreCorrectBlock(org.cloudburstmc.math.vector.Vector3i position, int networkId) {
        blockCorrections.restoreCorrectBlock(position, networkId);
    }

    public BedrockCodec codec() {
        return codec;
    }

    void handleClientPacket(BedrockPacket packet) {
        if(ac.navro.proxy.backend.BackendStateSync.privatePacket(packet))return;
        if (closed.get()) {
            return;
        }
        boolean ownLatency = false;
        if (packet instanceof NetworkStackLatencyPacket latency) {
            ownLatency = NetworkLatencyPackets.onPacket(state.player(), latency);
        } else if (packet instanceof ServerboundLoadingScreenPacket loading) {
            ServerChunkPackets.finishLoadingScreen(state.player(), loading);
        }
        if (!state.receiveClient(packet, proxy.config().maxBalanceAdvantageMs()) || state.player().disconnectRequested()) return;
        logPacket("client -> proxy", packet);
        if (ownLatency) return;

        if (packet instanceof RequestNetworkSettingsPacket request) {
            BedrockCodec requested = SupportedCodecs.forProtocol(request.getProtocolVersion());
            if (requested == null) {
                requested = SupportedCodecs.fallback(proxy.config().fallbackProtocolVersion());
                if (requested.getProtocolVersion() != request.getProtocolVersion()) {
                    disconnect("Unsupported Bedrock protocol " + request.getProtocolVersion());
                    return;
                }
            }
            codec = requested;
            state.setProtocolVersion(codec.getProtocolVersion());
            state.player().setProtocolVersion(codec.getProtocolVersion());
            client.getPeer().setCodec(codec);
            definitions.configure(client.getPeer().getCodecHelper(), false);
            log("negotiated Bedrock protocol " + request.getProtocolVersion());
            connectBackend(request);
            return;
        }

        if (packet instanceof LoginPacket login) {
            if (clientEncryptionStarted || codec == null) {
                disconnect("Unexpected Bedrock login packet");
                return;
            }
            pendingLogin = login;
            if (!startClientEncryption(login)) {
                return;
            }
            return;
        }

        if (packet instanceof ClientToServerHandshakePacket) {
            if (!clientEncryptionStarted || clientHandshakeAccepted || pendingLogin == null) {
                disconnect("Unexpected Bedrock encryption confirmation");
                return;
            }
            clientHandshakeAccepted = true;
            state.player().setEntityBatchEncoder(EntityBatchEncoder.install(client, state.player().getLatencyUtil()));
            if (proxy.config().encryptBackend()) {
                if (!backendNetworkSettingsReceived || backend == null || !backend.isConnected()) {
                    disconnect("Backend encryption requested before Bedrock network settings");
                    return;
                }
                backendEncryptionTimeout = backend.getPeer().getChannel().eventLoop().schedule(
                        () -> disconnect("Bedrock backend encryption handshake timed out"), 10, TimeUnit.SECONDS);
            }
            forwardToBackend(pendingLogin);
            pendingLogin = null;
            log("client encryption handshake confirmed");
            return;
        }

        if (!clientHandshakeAccepted) {
            disconnect("Game packet received before Bedrock login completed");
            return;
        }
        if (proxy.config().encryptBackend() && !backendEncryptionReady) return;
        if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket command
                &&ac.navro.proxy.command.NavroCommand.handle(command.getCommand(),new ac.navro.proxy.command.NavroCommand.Sender(){
                    public java.util.UUID identity(){return identity;}
                    public String name(){return state.player().getName();}
                    public boolean hasPermission(String node){return proxy.permissions().hasPermission(identity,node,permissions.isOperator());}
                    public void sendMessage(String message){sendAlertMessage(message);}
                },proxy.getAlertManager(),proxy.commands(),proxyCommandNames))return;
        forwardToBackend(packet);
    }

    private void forwardToBackend(BedrockPacket packet) {
        state.onClientPacketForwarded(packet);


        if (backend == null || !backend.isConnected()) {
            ReferenceCountUtil.retain(packet);
            queuedClientPackets.add(packet);
            return;
        }
        if(state.detectionEnabled() && packet instanceof org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket)
            backend.sendPacket(packet);
        else PacketRelay.forward(client, backend, packet);
    }

    private boolean backendReady() {
        return backend != null && backend.isConnected()
                && (!proxy.config().encryptBackend() || backendEncryptionReady);
    }


    private boolean startClientEncryption(LoginPacket login) {
        try {
            if (login.getAuthPayload() == null || login.getAuthPayload().getAuthType() == AuthType.GUEST) {
                disconnect("Guest or missing Bedrock login is not accepted");
                return false;
            }

            ChainValidationResult chain = EncryptionUtils.validatePayload(login.getAuthPayload());
            if (!chain.signed()) {
                disconnect("Invalid Bedrock login chain");
                return false;
            }

            PublicKey identityKey = chain.identityClaims().parsedIdentityPublicKey();
            byte[] clientData = EncryptionUtils.verifyClientData(login.getClientJwt(), identityKey);
            if (clientData == null) {
                disconnect("Invalid Bedrock client data signature");
                return false;
            }
            var verifiedIdentity=ac.navro.proxy.protocol.LoginIdentity.fromVerifiedChain(chain);
            if (proxy.config().encryptBackend() || proxy.config().pmmpNgBackendAuth()) {
                backendEncryption = new RakNetBackendEncryption(proxy.backendSigningKey());
                var forwarding = proxy.floodgateForwarding();
                if (forwarding != null) {
                    pendingLogin = backendEncryption.forwardedLogin(login.getProtocolVersion(), chain, clientData,
                            (InetSocketAddress) client.getSocketAddress(), forwarding);
                } else {
                    pendingLogin = backendEncryption.login(login.getProtocolVersion(), verifiedIdentity,
                            chain.rawIdentityClaims(), clientData);
                }
            }
            state.player().setName(verifiedIdentity.displayName());
            identity=verifiedIdentity.uuid();
            state.setExemptionPermission(() -> proxy.permissions().hasPermission(identity,"navro.exempt",false));
            proxy.registerAlertRecipient(identity,alertRecipient);
            proxy.commands().attach(identity,state);
            try {
                int deviceOs = JsonParser.parseString(new String(clientData, StandardCharsets.UTF_8))
                        .getAsJsonObject().get("DeviceOS").getAsInt();
                state.player().setDeviceOs(deviceOs);
            } catch (RuntimeException ignored) {
                state.player().setDeviceOs(-1);
            }

            KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
            byte[] token = EncryptionUtils.generateRandomToken();
            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(EncryptionUtils.createHandshakeJwt(serverKeyPair, token));
            client.sendPacketImmediately(handshake);

            SecretKey encryptionKey = EncryptionUtils.getSecretKey(serverKeyPair.getPrivate(), identityKey, token);
            client.enableEncryption(encryptionKey);
            clientEncryptionStarted = true;
            log("client identity verified: "+state.player().getName()+" ("+identity+"); public encryption enabled");
            return true;
        } catch (Exception | LinkageError error) {
            error.printStackTrace();
            disconnect("Unable to validate Bedrock login: " + error.getMessage());
            return false;
        }
    }

    private void connectBackend(RequestNetworkSettingsPacket request) {
        if (backendConnect != null) {
            return;
        }

        InetSocketAddress target = new InetSocketAddress(proxy.config().backendHost(), proxy.config().backendPort());
        backendConnect = new Bootstrap()


                .group(client.getPeer().getChannel().eventLoop())
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.getRaknetProtocolVersion())
                .handler(new BackendInitializer())
                .connect(target);
        backendConnect.addListener(future -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                Throwable root = cause;
                while (root != null && root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String detail = cause == null ? "connection failed without a cause"
                        : cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
                if (root != null && root != cause) {
                    detail += "; root=" + root.getClass().getSimpleName() + ": "
                            + String.valueOf(root.getMessage());
                }
                if (proxy.config().debug() && cause != null) cause.printStackTrace(System.err);
                disconnect("Unable to connect to Bedrock backend: " + detail);
            } else {
                log("connected to backend " + target);
            }
        });
    }

    private void attachBackend(BedrockClientSession session) {
        if (closed.get()) {
            session.disconnect("Proxy session closed");
            return;
        }
        backend = session;
        backend.setLogging(proxy.config().debug());
        backend.setCodec(codec);
        definitions.configure(backend.getPeer().getCodecHelper(), true);
        backend.getPeer().getChannel().closeFuture().addListener(ignored -> disconnect("Bedrock backend disconnected"));
        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(codec.getProtocolVersion());
        backend.sendPacket(request);
        while (!queuedClientPackets.isEmpty()) {
            backend.sendPacket(queuedClientPackets.remove());
        }
    }

    void handleBackendPacket(BedrockPacket packet) {
        if (closed.get()) {
            return;
        }
        if (packet instanceof ServerToClientHandshakePacket handshake) {
            completeBackendEncryption(handshake);
            return;
        }
        if (proxy.config().encryptBackend() && !backendEncryptionReady
                && !(packet instanceof DisconnectPacket) && !(packet instanceof NetworkSettingsPacket)) {
            disconnect("Bedrock backend sent packets before completing the encryption handshake");
            return;
        }
        String backendViolation = state.validateBackend(packet);
        if (backendViolation != null) {
            disconnect("Invalid backend packet: " + backendViolation);
            return;
        }
        if(state.consumeBackendSync(packet))return;
        if (packet instanceof NetworkStackLatencyPacket latency) {
            NetworkLatencyPackets.onPacketSend(state.player(), latency);
        }
        PacketListener.onPacketSent(state.player(), packet);
        state.observeBackend(packet);
        if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket commands)
            proxyCommandNames = state.hasBackendCommands()
                    ? ac.navro.proxy.command.NavroCommand.unclaimedNames(commands)
                    : ac.navro.proxy.command.NavroCommand.advertise(commands);
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.StartGamePacket && serverTickTask == null) {
            serverTickTask = client.getPeer().getChannel().eventLoop().scheduleAtFixedRate(this::serverTick,
                    50, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        permissions.observe(state.player(),packet);
        definitions.observeBackend(packet);
        logPacket("backend -> proxy", packet);
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket items) {
            log("registered " + items.getItems().size() + " backend item definitions");
        } else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket creative) {
            log("decoded creative contents: groups=" + creative.getGroups().size()
                    + ", items=" + creative.getContents().size());
        }

        if (packet instanceof NetworkSettingsPacket settings) {
            client.sendPacketImmediately(packet);
            client.setCompression(settings.getCompressionAlgorithm());
            backend.setCompression(settings.getCompressionAlgorithm());
            backendNetworkSettingsReceived = true;
            return;
        }
        if(state.detectionEnabled() && packet instanceof org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket motion && motion.getRuntimeEntityId()==state.player().runtimeId()){
            client.sendPacket(motion);
        } else if(state.detectionEnabled() && packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket effect
                &&effect.getEntityRuntimeId()==state.player().runtimeId()){
            client.sendPacket(effect);
        } else if (packet instanceof NetworkStackLatencyPacket latency) {

            client.sendPacket(latency.clone());
        } else if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket commands
                && !state.hasBackendCommands()){
            client.sendPacket(commands);
        } else if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket move
                &&move.getRuntimeEntityId()==state.player().runtimeId()
                &&move.getMode()!=org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket.Mode.HEAD_ROTATION){
            client.sendPacket(move);
        } else PacketRelay.forward(backend, client, packet);
        state.onBackendPacketQueued(packet);
    }

    private void completeBackendEncryption(ServerToClientHandshakePacket handshake) {
        if (!proxy.config().encryptBackend()) {
            disconnect("Backend requested Bedrock encryption. Use --encrypt-backend with a trusted private backend.");
            return;
        }
        if (backendEncryption == null || backendEncryptionTimeout == null || backendEncryptionReady) {
            disconnect("Unexpected Bedrock backend encryption handshake");
            return;
        }
        try {
            backendEncryption.complete(backend, handshake.getJwt());
            backendEncryptionReady = true;
            backendEncryption = null;
            backendEncryptionTimeout.cancel(false);
            backendEncryptionTimeout = null;
            log("Bedrock backend encryption handshake confirmed");
        } catch (Exception | LinkageError error) {
            if (proxy.config().debug()) error.printStackTrace();
            disconnect("Invalid Bedrock backend encryption handshake");
        }
    }

    public void disconnect(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log("disconnect: " + reason);
        try {
            if (client.isConnected()) client.disconnect(reason);
        } finally {
            closeBackend();
        }
    }

    private void close() {
        closed.set(true);
        while (!queuedClientPackets.isEmpty()) ReferenceCountUtil.safeRelease(queuedClientPackets.remove());
        if (backendConnect != null && !backendConnect.isDone()) backendConnect.cancel(false);
        closeBackend();
    }

    private void closeBackend() {
        if (backendEncryptionTimeout != null) backendEncryptionTimeout.cancel(false);
        backendEncryptionTimeout = null;
        backendEncryption = null;
        pendingLogin = null;
        state.closeBackendWorld();
        if(state.player().worldDiagnostics()!=null)state.player().worldDiagnostics().event("session_close",java.util.Map.of());
        if (identity != null) { proxy.removeAlertRecipient(identity, alertRecipient);proxy.commands().detach(identity,state); }
        if (serverTickTask != null) serverTickTask.cancel(false);
        if (state.player().getEntityBatchEncoder() != null) state.player().getEntityBatchEncoder().close();
        else state.player().getLatencyUtil().close();
        BedrockClientSession current = backend;
        if (current != null && current.isConnected()) {
            current.disconnect("Proxy session closed");
        }
    }

    private void serverTick() {
        state.placementTick();
        if (closed.get() || state.player().isExempted()) return;
        var latency = state.player().getLatencyUtil();
        var encoder = state.player().getEntityBatchEncoder();
        if (latency.sentQueue().isEmpty()) {
            if (encoder != null && encoder.hasPending()) return;
            if (encoder != null) encoder.keepalive();
            return;
        }
        if (System.currentTimeMillis() - latency.prevAcceptedTime > state.maxLatencyWait()) disconnect(state.configuredMessage("disconnect-timeout",java.util.Map.of()));
    }

    private void sendAlertMessage(String message) {
        Runnable send = () -> {
            if (closed.get()) return;
            var packet = new org.cloudburstmc.protocol.bedrock.packet.TextPacket();
            packet.setType(org.cloudburstmc.protocol.bedrock.packet.TextPacket.Type.RAW);
            packet.setMessage(message);
            packet.setNeedsTranslation(false);
            packet.setSourceName("");
            packet.setXuid("");
            packet.setPlatformChatId("");
            client.sendPacket(packet);
        };
        var loop=client.getPeer().getChannel().eventLoop();
        if(loop.inEventLoop())send.run();else loop.execute(send);
    }

    private void logPacket(String direction, BedrockPacket packet) {
        if (proxy.config().debug()) {
            log(direction + " " + packet.getClass().getSimpleName());
        }
    }

    private void log(String message) {
        proxy.log("[" + client.getSocketAddress() + "] " + message);
    }

    private final class ClientPacketHandler implements org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler {
        @Override
        public PacketSignal handlePacket(BedrockPacket packet) {
            handleClientPacket(packet);
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            close();
        }
    }

    private final class BackendPacketHandler implements org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler {
        @Override
        public PacketSignal handlePacket(BedrockPacket packet) {
            handleBackendPacket(packet);
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            disconnect("Bedrock backend disconnected: " + reason);
        }
    }

    private final class BackendInitializer extends BedrockClientInitializer {
        @Override
        protected void postInitChannel(io.netty.channel.Channel channel) {
            PacketRelay.install(channel);
        }

        @Override
        public void initSession(BedrockClientSession session) {
            session.setCodec(codec);
            session.setPacketHandler(new BackendPacketHandler());
            attachBackend(session);
        }
    }
}
