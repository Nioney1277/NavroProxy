package ac.navro.proxy;
import ac.navro.proxy.packets.input.AuthInputPackets;


import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.packets.player.PlayerInventoryPackets;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.state.WireVec3;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.protocol.BackendProtocolProfile;
import ac.navro.proxy.collision.ProtocolCollisionWorld;
import ac.navro.proxy.prediction.ProtocolPredictionReplay;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import ac.navro.proxy.teleport.ProtocolTeleportMotion;
import ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WireEvent;
import ac.navro.proxy.state.WireAcknowledgment;
import ac.navro.proxy.state.WireAnomaly;
import ac.navro.proxy.state.WireTickSnapshot;
import ac.navro.proxy.prediction.WireMotionSeed;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Iterator;





public final class SessionState {
    private final ac.navro.proxy.protocol.BackendType backendType;

    public SessionState() { this(ac.navro.proxy.protocol.BackendType.NAVRO); }

    public SessionState(ac.navro.proxy.protocol.BackendType backendType) {
        this.backendType = java.util.Objects.requireNonNull(backendType);
        player.setNativeSprintSynchronization(backendType == ac.navro.proxy.protocol.BackendType.BDS);
        player.getCheckHolder().put(ac.navro.proxy.check.impl.elytra.ElytraA.class,
                new ac.navro.proxy.check.impl.elytra.ElytraA(this));
        world.setCollisionActor(() -> new ac.navro.proxy.collision.BedrockCollision.Actor(
                player.boundingBox().minY, authInputPackets == null ? 0 : authInputPackets.context().fallDistance,
                player.inputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAKING)
                        || player.inputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.DESCEND),
                player.hasFlag(71),
                hasLeatherBoots()));
    }
    private boolean hasLeatherBoots() {
        var boots=inventory.armorContainer.get(3).getData();
        int protocol=player.protocolVersion()>0?player.protocolVersion():world.protocolVersion();
        return boots!=null&&!boots.isNull()&&protocol>0
                &&ac.navro.proxy.data.inventory.NativePredictionItems.resolve(protocol,boots).nativeId()==301;
    }
    private long lastInputTick = -1;
    private boolean spawned;
    private boolean awaitingBackendSpawnPosition;
    private long backendSpawnRevision;

    public ac.navro.proxy.protocol.BackendType backendType() { return backendType; }
    public boolean awaitingBackendSpawnPosition() { return awaitingBackendSpawnPosition; }
    private long lastCorrectionTick = -1;
    private long lastVelocityEntityId = -1;
    private Vector3f clientPosition;
    private Vector3f serverPosition;
    private Vector3f serverVelocity;
    private long packetsFromClient;
    private long packetsFromBackend;
    private long clientInputPackets;
    private long backendCorrectionPackets;
    private long backendAckPackets;
    private long backendChunkPackets;
    private long backendBlockUpdatePackets;
    private long backendInventoryPackets;
    private final Map<Long, Vector3f> entityPositions = new HashMap<>();
    private final Map<Long, Vector3f> entityMotions = new HashMap<>();
    private final Map<String, Integer> packetCounts = new HashMap<>();
    private final Map<Long, EntityCache> entities = new HashMap<>();
    private final Map<Long, Long> uniqueIdToRuntimeId = new HashMap<>();
    private final WirePlayerState player = new WirePlayerState();
    private final ac.navro.proxy.packets.input.teleport.TeleportHandler teleportHandler =
            new ac.navro.proxy.packets.input.teleport.TeleportHandler(this);
    private final CompensatedWorld world = new CompensatedWorld(player.collisionPhysics);
    private final ac.navro.proxy.compensated.world.BackendBlockSnapshots backendBlocks =
            new ac.navro.proxy.compensated.world.BackendBlockSnapshots();
    private final ac.navro.proxy.compensated.BackendInventorySnapshots backendInventories =
            new ac.navro.proxy.compensated.BackendInventorySnapshots();
    public ac.navro.proxy.compensated.BackendInventorySnapshots backendInventories(){return backendInventories;}
    private final ac.navro.proxy.compensated.InventoryResponseSync inventoryResponseSync=new ac.navro.proxy.compensated.InventoryResponseSync(this);
    public ac.navro.proxy.compensated.InventoryResponseSync inventoryResponseSync(){return inventoryResponseSync;}
    public ac.navro.proxy.compensated.world.BackendBlockSnapshots.Blocks backendBlocksAt(Vector3i position) {
        return acknowledgedTerrainGeneration==backendBlocks.generation()?backendBlocks.at(position):null;
    }
    private ac.navro.proxy.check.impl.place.PlacementPipeline placementPipeline;
    public ac.navro.proxy.check.impl.place.PlacementPipeline placementPipeline() {
        if(placementPipeline==null)placementPipeline=new ac.navro.proxy.check.impl.place.PlacementPipeline(this);
        return placementPipeline;
    }
    public void placementTick(){if(placementPipeline!=null)placementPipeline.tick();}
    public void closePlacement(){if(placementPipeline!=null)placementPipeline.close();}

    public void onClientPacketForwarded(BedrockPacket packet) {
        boolean inventoryAction = packet instanceof ItemStackRequestPacket
                || packet instanceof InventoryTransactionPacket
                || packet instanceof PlayerAuthInputPacket input
                        && (input.getItemStackRequest() != null || input.getItemUseTransaction() != null);
        if (!inventoryAction) return;

        backendInventories.markAllPending();

        int hotbarSlot = -1;
        if (packet instanceof InventoryTransactionPacket transaction) {
            hotbarSlot = transaction.getHotbarSlot();
        } else if (packet instanceof PlayerAuthInputPacket input
                && input.getItemUseTransaction() != null) {
            hotbarSlot = input.getItemUseTransaction().getHotbarSlot();
        }
        if (hotbarSlot >= 0 && hotbarSlot <= 8) backendInventories.markPending(0, hotbarSlot);
    }
    public void onProxyInventoryCorrection(org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket packet) {
        PlayerInventoryPackets.onPacketSend(player,inventory,packet);
    }
    private long acknowledgedTerrainGeneration;
    private java.util.function.BiConsumer<Vector3i, ac.navro.proxy.compensated.world.BackendBlockSnapshots.Blocks>
            blockCorrectionTransport = (position, blocks) -> { };
    public void setBlockCorrectionTransport(java.util.function.BiConsumer<Vector3i,
            ac.navro.proxy.compensated.world.BackendBlockSnapshots.Blocks> transport) {
        blockCorrectionTransport = java.util.Objects.requireNonNull(transport);
    }

    public void restoreCorrectBlock(Vector3i position) {
        var blocks=acknowledgedTerrainGeneration==backendBlocks.generation()?backendBlocks.at(position):null;
        var log=player.worldDiagnostics();
        if(log!=null)log.event("block_break_correction",
                ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                        "position",position.toString(),"sourceKnown",blocks!=null,
                        "layerZero",blocks==null?null:blocks.layerZero(),
                        "layerOne",blocks==null?null:blocks.layerOne()));
        if(blocks!=null)blockCorrectionTransport.accept(position,blocks);
    }
    private final ac.navro.proxy.packets.server.ServerChunkPackets serverChunkPackets =
            new ac.navro.proxy.packets.server.ServerChunkPackets(player, world);
    private final ac.navro.proxy.packets.server.ServerEntityPackets serverEntityPackets =
            new ac.navro.proxy.packets.server.ServerEntityPackets(player, world, entities, uniqueIdToRuntimeId);
    private final ac.navro.proxy.packets.server.ServerRewindPackets serverRewindPackets =
            new ac.navro.proxy.packets.server.ServerRewindPackets();

    public void onBackendPacketQueued(BedrockPacket packet) { serverEntityPackets.afterSend(packet); }
    private final ac.navro.proxy.backend.BackendStateSync backendSync=new ac.navro.proxy.backend.BackendStateSync(this);
    private java.util.function.Consumer<BedrockPacket> backendSyncTransport=packet->{};
    public void setBackendSyncTransport(java.util.function.Consumer<BedrockPacket> send) {
        backendSyncTransport=java.util.Objects.requireNonNull(send);
    }
    public ac.navro.proxy.backend.BackendStateSync backendSync() { return backendSync; }
    public boolean consumeBackendSync(BedrockPacket packet) { return backendSync.receive(packet); }
    private final CompensatedInventory inventory = new CompensatedInventory(player);
    private final ac.navro.proxy.validator.inventory.ItemTransactionValidator transactionValidator =
            new ac.navro.proxy.validator.inventory.ItemTransactionValidator(player, inventory);
    public ac.navro.proxy.validator.inventory.ItemTransactionValidator transactionValidator(){return transactionValidator;}
    public boolean hasElytraEquipped() {
        var chest=inventory.armorContainer.get(1).getData();
        return chest!=null&&!chest.isNull()
                &&ac.navro.proxy.data.inventory.NativePredictionItems.resolve(player.protocolVersion(),chest).nativeId()==444;
    }
    private java.util.function.Consumer<BedrockPacket> clientStateCorrection = packet -> { };
    public void setClientStateCorrectionTransport(java.util.function.Consumer<BedrockPacket> transport) {
        clientStateCorrection=java.util.Objects.requireNonNull(transport);
    }
    public void resyncGliding() {
        var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(player.runtimeId());
        ac.navro.proxy.util.EntityMetadataUtil.writeFlags(metadata.getMetadata(),player.trackedFlags().cloneFlags());
        metadata.getMetadata().setFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.GLIDING,false);
        clientStateCorrection.accept(metadata);


        var armor=backendInventories.fullPacket(120);
        if(armor!=null){onProxyInventoryCorrection(armor);clientStateCorrection.accept(armor);}
    }
    private final ProtocolFluidStateTracker fluidState = new ProtocolFluidStateTracker();
    private BackendProtocolProfile backendProfile;
    private long eventSequence;
    private final Deque<WireEvent> eventJournal = new ArrayDeque<>();
    private final Deque<WireAcknowledgment> acknowledgments = new ArrayDeque<>();
    private final Deque<WireAnomaly> anomalies = new ArrayDeque<>();
    private final Deque<WireTickSnapshot> tickSnapshots = new ArrayDeque<>();
    private final Deque<WireMotionSeed> motionSeeds = new ArrayDeque<>();
    private java.util.function.Function<MovePlayerPacket, Boolean> teleportTransport = packet -> false;
    private final ac.navro.proxy.teleport.TeleportUtil teleports = new ac.navro.proxy.teleport.TeleportUtil(
            new ac.navro.proxy.teleport.TeleportUtil.Context() {
                public long runtimeEntityId(){return player.runtimeId();}
                public Vec3 rotation(){var r=player.rawInputRotation();return r==null?Vec3.ZERO:new Vec3(r.x(),r.y(),r.z());}
                public boolean sendPacket(MovePlayerPacket packet){return teleportTransport.apply(packet);}
                public void movementEvent(String name,Object...values){
                    var log=player.worldDiagnostics();
                    if(log!=null)log.event(name,ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(values));
                }
                public void swimStage(String name,Object...values){ }
                public void itemUseStage(String name,Object...values){ }
            });
    public ac.navro.proxy.teleport.TeleportUtil teleports(){return teleports;}
    public void setTeleportTransport(java.util.function.Function<MovePlayerPacket, Boolean> transport){
        teleportTransport=java.util.Objects.requireNonNull(transport);
    }
    public ac.navro.proxy.teleport.data.TeleportData onProxyTeleport(MovePlayerPacket packet){
        var data=queueMovementTeleport(packet, false);
        recordEvent(WireEvent.Direction.PROXY,packet);
        packetCounts.merge("proxy:MovePlayerPacket",1,Integer::sum);
        return data;
    }
    private ac.navro.proxy.teleport.data.TeleportData queueMovementTeleport(MovePlayerPacket packet, boolean fromBackend){
        if(packet.getRuntimeEntityId()!=player.runtimeId()||packet.getMode()==MovePlayerPacket.Mode.HEAD_ROTATION)return null;
        if(packet.getMode()==null)packet.setMode(MovePlayerPacket.Mode.NORMAL);
        if(packet.getTeleportationCause()==null)packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.UNKNOWN);
        var p=packet.getPosition();
        ac.navro.proxy.teleport.data.TeleportData data=predictionResetPackets.remove(packet);
        if(data==null){if(authInputPackets!=null)authInputPackets.velocityPackets().clearExternalMotion();data=new ac.navro.proxy.teleport.data.TeleportData(new Vec3(p),packet.isOnGround(),packet.getMode().ordinal());}
        var queued = data;
        boolean backendTeleport = fromBackend && packet.getMode() == MovePlayerPacket.Mode.TELEPORT;
        teleports.queue(queued, accept -> player.queueAcknowledgment(packet, () -> {
            accept.run();
            if (backendTeleport) teleports.supersedeHardCorrection(queued);
        }));
        return data;
    }
    private final Deque<ProtocolTeleportMotion> teleportMotionSeeds = new ArrayDeque<>();
    private boolean enforceBoatStep;
    private ProtocolEntityTolerance.Tolerance entityTolerance =
            new ProtocolEntityTolerance.Tolerance(false, false, 0, 0, 0);

    public void setProtocolVersion(int protocolVersion) {
        world.setProtocolVersion(protocolVersion);
        backendBlocks.setProtocolVersion(protocolVersion);
    }

    public void setRuntimeBlockPalette(ac.navro.proxy.data.block.NetworkBlockStateRegistry.RuntimePalette palette) {
        world.setRuntimeBlockPalette(palette);
        backendBlocks.setRuntimeBlockPalette(palette);
    }

    private boolean detectionEnabled;
    private ac.navro.proxy.packets.input.AuthInputPackets authInputPackets;
    public ac.navro.proxy.packets.input.AuthInputPackets authInputPackets(){
        if(authInputPackets==null){authInputPackets=new ac.navro.proxy.packets.input.AuthInputPackets(this);applyCheckConfig();}
        return authInputPackets;
    }
    public boolean spawned(){return spawned;}

    private java.util.function.Consumer<Runnable> bdsCorrectionAcknowledgmentDispatcher = Runnable::run;
    public void setBdsCorrectionAcknowledgmentDispatcher(java.util.function.Consumer<Runnable> dispatcher) {
        if (backendType != ac.navro.proxy.protocol.BackendType.BDS)
            throw new IllegalStateException("Correction batch deferral belongs only to BDS");
        bdsCorrectionAcknowledgmentDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }
    private ac.navro.proxy.command.CommandRuntime commandRuntime;
    public void setCommandRuntime(ac.navro.proxy.command.CommandRuntime runtime) { commandRuntime=runtime; }
    private final java.util.Map<java.util.UUID, java.util.function.Consumer<String>> debugSubscribers=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.function.Consumer<String>> debug2Subscribers=new java.util.concurrent.ConcurrentHashMap<>();
    public boolean toggleDebugSubscriber(boolean second,java.util.UUID id,java.util.function.Consumer<String> recipient) {
        var subscribers=second?debug2Subscribers:debugSubscribers;
        boolean enabled=subscribers.remove(id)==null;if(enabled)subscribers.put(id,recipient);return enabled;
    }
    public void removeDebugSubscriber(java.util.UUID id) { debugSubscribers.remove(id);debug2Subscribers.remove(id); }
    public void clearDebugSubscriptions() { debugSubscribers.clear();debug2Subscribers.clear(); }
    public boolean hasDebugSubscribers() { return !debugSubscribers.isEmpty()||!debug2Subscribers.isEmpty(); }
    public void sendDebug(boolean second,String message) { (second?debug2Subscribers:debugSubscribers).values().forEach(r->r.accept(message)); }
    public String commandPrefix() { return commandRuntime==null?"§aNavro §7>§r ":commandRuntime.config().formattedPrefix(); }
    public void applyCheckConfig() {
        if(commandRuntime==null)return;
        var config=commandRuntime.config();smoothSetback=config.smoothSetback();
        player.setMaxBalanceAdvantageMs(config.maxBalanceAdvantage());player.setDisabledChecks(config.disabledChecks());
        if(authInputPackets!=null) {
            var context=authInputPackets.context();context.maxOffset=config.acceptanceThreshold();
            context.alertThreshold=config.alertThreshold();context.disabledChecks=config.disabledChecks();
        }
    }
    public int rewindHistorySize() { return commandRuntime==null?20:commandRuntime.config().rewindHistory(); }
    public long maxLatencyWait() { return commandRuntime==null?15000:commandRuntime.config().maxLatencyWait(); }
    private boolean smoothSetback;
    public void setSmoothSetback(boolean enabled) { smoothSetback = enabled; }
    public boolean smoothSetback() { return smoothSetback; }
    private java.util.function.Function<CorrectPlayerMovePredictionPacket, Boolean> softCorrectionTransport = packet -> false;
    public void setSoftCorrectionTransport(java.util.function.Function<CorrectPlayerMovePredictionPacket, Boolean> transport) {
        softCorrectionTransport = java.util.Objects.requireNonNull(transport);
    }
    public ac.navro.proxy.ack.types.MovementCorrectionAck onProxyPredictionCorrection(CorrectPlayerMovePredictionPacket packet) {
        teleports.syncBlocks(world, player.position(), player.tick(), this::restoreCorrectBlock);
        serverRewindPackets.syncCorrection(player, packet.getTick(), clientStateCorrection);
        var acknowledgment = teleports.addPendingCorrection(packet.getTick());
        player.queueAcknowledgment(packet, () -> {
            if (!teleports.removePendingCorrection(acknowledgment)) return;
            var diagnostics = player.worldDiagnostics();
            if (diagnostics != null) diagnostics.event("proxy_prediction_correction_acknowledged",
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                            "sourceTick", acknowledgment.tick(), "ackId", player.getLatencyUtil().currentAcknowledgmentId(),
                            "backendType", backendType.name()));
        });
        recordEvent(WireEvent.Direction.PROXY, packet);
        packetCounts.merge("proxy:CorrectPlayerMovePredictionPacket", 1, Integer::sum);
        return acknowledgment;
    }
    public float playerYOffset(){return playerYOffsetInternal();}
    private final java.util.NavigableMap<Long,ac.navro.proxy.teleport.data.RewindHistory> predictionHistory=new java.util.TreeMap<>();
    private final java.util.IdentityHashMap<MovePlayerPacket,ac.navro.proxy.teleport.data.CorrectionResetData> predictionResetPackets=new java.util.IdentityHashMap<>();
    private java.util.function.Function<org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket,Boolean> predictionMotionTransport=packet->false;
    public void setPredictionMotionTransport(java.util.function.Function<org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket,Boolean> transport){predictionMotionTransport=java.util.Objects.requireNonNull(transport);}
    public void cachePredictionFrame(ac.navro.proxy.prediction.live.PredictionContext context){
        var frame=ac.navro.proxy.teleport.data.RewindHistory.capture(context,context.tick,playerYOffset());
        predictionHistory.put(frame.tick(),frame);
        while(predictionHistory.size()>rewindHistorySize())predictionHistory.pollFirstEntry();
        teleports.setLastKnowValid(frame.position());
    }
    public void cacheRewindBaseline(long tick, float yOffset) {
        var frame = new ac.navro.proxy.teleport.data.RewindHistory(tick, player.position().up(yOffset),
                new ac.navro.proxy.data.input.PredictionData(Vec3.ZERO.clone(), Vec3.ZERO.clone(), player.velocity().clone()),
                player.snapshotAabb(), yOffset, player.onGround());
        predictionHistory.put(tick, frame);
        teleports.setLastKnowValid(frame.position());
    }
    public void rewindPrediction(long tick){
        if(teleports.isTeleporting() || teleports.hasPendingCorrection() || player.isMovementExempted())return;
        var frame=predictionHistory.get(tick);
        if(frame==null)return;
        if(smoothSetback || player.vehicleData != null) {
            teleports.correct(frame, player.vehicleData != null && player.vehicleData.canWeControlThisVehicle, softCorrectionTransport);
            return;
        }
        var prediction=authInputPackets();
        var reset=new ac.navro.proxy.teleport.data.CorrectionResetData(frame,player.flying()?null:prediction.velocityPackets().setbackVelocity(frame));
        reset.bindExternalMotion(prediction.velocityPackets().externalMotionSequence(),prediction.context().certainVelocity);
        var packet=teleports.hardCorrectionPacket(reset);
        predictionResetPackets.put(packet,reset);
        boolean sent;
        try{sent=teleportTransport.apply(packet);}finally{predictionResetPackets.remove(packet);}
        if(sent && reset.setbackVelocity()!=null){
            var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(player.runtimeId());motion.setMotion(reset.setbackVelocity().toVector3f());motion.setTick(0);
            if(predictionMotionTransport.apply(motion)){prediction.velocityPackets().consumeSetbackExternalMotion(reset);reset.motionSent();}
        }
    }



    public void setDetectionEnabled(boolean enabled) {
        detectionEnabled = enabled;
        inventory.setNativeItemDecoding(enabled);
        player.setPredictionInputProcessing(enabled);
        player.getLatencyUtil().setDetectionEnabled(enabled);
    }

    public boolean detectionEnabled() { return detectionEnabled; }

    private java.util.function.BooleanSupplier exemptionPermission;

    public void setExemptionPermission(java.util.function.BooleanSupplier permission) {
        exemptionPermission = java.util.Objects.requireNonNull(permission);
    }

    private void refreshExemptionPermission() {
        if (detectionEnabled && exemptionPermission != null) player.setExempted(exemptionPermission.getAsBoolean());
    }


    public boolean receiveClient(BedrockPacket packet, long maxBalanceAdvantageMs) {
        if(ac.navro.proxy.backend.BackendStateSync.privatePacket(packet))return false;
        refreshExemptionPermission();
        applyCheckConfig();
        if(commandRuntime!=null)maxBalanceAdvantageMs=commandRuntime.config().maxBalanceAdvantage();
        ac.navro.proxy.packets.input.PacketEvent event=null;
        if(detectionEnabled&&!player.isExempted()&&!(packet instanceof PlayerAuthInputPacket)) {
            event=placementPipeline().begin(packet);
            if(event.isCancelled()) {
                observeRejectedClient(packet,"placementAwaitingData","Waiting for exact placement facts");
                return placementPipeline().finish(event,false);
            }
        }
        ac.navro.proxy.packets.other.VehiclePackets.onPacketReceive(player, packet);
        final boolean checked = detectionEnabled && !player.isExempted();
        if (detectionEnabled && !ac.navro.proxy.listener.PacketListener.onPacketReceived(
                this, packet, maxBalanceAdvantageMs)) {
            if (packet instanceof PlayerAuthInputPacket input) {
                if (teleportHandler.processRejectedInput(input)) player.finishInput();
                else ac.navro.proxy.packets.input.PostAuthInputPackets.onPacket(this, input, true);
            }
            return event==null?false:placementPipeline().finish(event,false);
        }
        if(event==null&&detectionEnabled&&!player.isExempted()) {
            event=placementPipeline().begin(packet);
            if(event.isCancelled()){observeRejectedClient(packet,"placementAwaitingData","Waiting for exact placement facts");return placementPipeline().finish(event,false);}
        }
        if (event != null && packet instanceof PlayerAuthInputPacket input
                && input.getItemUseTransaction() != null
                && input.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PERFORM_ITEM_INTERACTION)) {




            var embedded = placementPipeline().embeddedTransaction(input);
            if (embedded == null || !placementPipeline().handleTransaction(embedded)) {
                observeRejectedClient(packet, "inventoryPredictionRejected", "Embedded item-use transaction rejected");
                event.setCancelled(true);
                return placementPipeline().finish(event, false);
            }
        }
        boolean accepted=observeClient(packet,checked);
        return event==null?accepted:placementPipeline().finish(event,accepted);
    }

    public void observeClient(BedrockPacket packet) {
        refreshExemptionPermission();
        ac.navro.proxy.packets.other.VehiclePackets.onPacketReceive(player, packet);
        observeClient(packet, false);
    }

    private boolean observeClient(BedrockPacket packet, boolean checked) {
        applyCheckConfig();
        boolean forward = true;
        if(player.worldDiagnostics()!=null)player.worldDiagnostics().client(packet);
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket request) {
            transactionValidator.handle(request);
            if(detectionEnabled)inventoryResponseSync.receive(request);
        }
        PlayerInventoryPackets.onPacketReceive(player, inventory, packet, item -> {
            if (detectionEnabled) authInputPackets().itemUse().onHeldItemChanged(item);
        });
        packetsFromClient++;
        packetCounts.merge("client:" + packet.getClass().getSimpleName(), 1, Integer::sum);
        long sequence = recordEvent(WireEvent.Direction.CLIENT, packet);
        if (packet instanceof PlayerAuthInputPacket input) {
            clientInputPackets++;
            clientPosition = input.getPosition();
            if(detectionEnabled)inventoryResponseSync.receive(input);
            if (detectionEnabled && player.isExempted()) return true;
            if (detectionEnabled && !awaitingBackendSpawnPosition) authInputPackets().beforeInput(input);

            if (!checked) {
                long arrivalMs = System.currentTimeMillis();
                player.lastAuthInputGapMs = arrivalMs - player.sinceAuthInput;
                player.sinceAuthInput = arrivalMs;
                player.sinceLoadingScreen++;
            }
            if (input.getTick() < 0) {
                recordAnomaly(WireEvent.Direction.CLIENT, sequence, "negativeInputTick", Long.toString(input.getTick()));
            } else if (lastInputTick >= 0 && input.getTick() <= lastInputTick) {
                recordAnomaly(WireEvent.Direction.CLIENT, sequence, "nonIncreasingInputTick", input.getTick() + " <= " + lastInputTick);
            }
            lastInputTick = Math.max(lastInputTick, input.getTick());
            boolean movementCoordinates = ac.navro.proxy.packets.input.legacy.InputPositionDecoder
                    .usesMovementCoordinates(spawned, input.getInputData().contains(
                            org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HANDLE_TELEPORT));
            if(detectionEnabled)authInputPackets();


            player.apply(input, world, detectionEnabled ? null : fluidState, movementCoordinates ? playerYOffset() : 0);
            if (spawned) {
                world.tickCollisionBlockActors();
                if(detectionEnabled)player.tickPredictionEffects();
                for (EntityCache entity : entities.values()) if (entity.getCurrent() != null) entity.getCurrent().tick();
            }
            boolean wasHardTeleporting=teleports.isHardTeleporting();
            boolean wasTeleporting=teleports.isTeleporting();
            boolean skipTravel=teleportHandler.processTeleport(input);
            if (spawned && !detectionEnabled) player.collisionPhysics.begin(world, entities, input.getTick(), null);
            if(detectionEnabled && !teleports.isHardTeleporting()) {
                if(skipTravel || wasHardTeleporting || (wasTeleporting && player.isMovementExempted())) authInputPackets().finishTeleportConfirmation(input);
                else authInputPackets().run(input);
            }
            tickSnapshots.addLast(player.snapshot(fluidState));
            while (tickSnapshots.size() > (detectionEnabled ? 20 : 4096)) tickSnapshots.removeFirst();
            if(detectionEnabled && spawned && player.vehicleData==null && !player.predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SLEEPING)){player.finishPredictionInput();authInputPackets().itemUse().finishInput();}
            if (detectionEnabled) forward = ac.navro.proxy.packets.input.PostAuthInputPackets.onPacket(this, input, false);
            else ac.navro.proxy.packets.input.PostAuthInputPackets.finishInput(player);
        }
        if(detectionEnabled && packet instanceof org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket transaction) {
            var result=PlayerInventoryPackets.onTransactionReceive(this,transaction);
            if(result==PlayerInventoryPackets.TransactionResult.REJECTED) {
                forward=false;
                recordAnomaly(WireEvent.Direction.CLIENT,sequence,"inventoryPredictionRejected","Navro transaction validator rejected");
            }
        }
        if (packet instanceof NetworkStackLatencyPacket latency) {
            recordAcknowledgment(latency, WireEvent.Direction.CLIENT, sequence);
        }
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket) {
            spawned = true;
        }
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket transaction
                && !detectionEnabled
                && transaction.getTransactionType() == org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.NORMAL) {
            boolean accepted = transactionValidator.handleNormal(transaction);
            if (!accepted) recordAnomaly(WireEvent.Direction.CLIENT, sequence, "inventoryPredictionRejected",
                    "Navro legacy drop prediction rejected; inspection forwarding unchanged");
        }
        return forward;
    }

    public void pollRewindHistory() {
        teleports.pollAuthInputHistory(rewindHistorySize());
        while (predictionHistory.size() > rewindHistorySize()) predictionHistory.pollFirstEntry();
        teleportMotionSeeds.removeIf(seed -> seed.inputTick() < player.tick() - rewindHistorySize());
    }

    public long observeRejectedClient(BedrockPacket packet, String check, String detail) {
        packetsFromClient++;
        packetCounts.merge("client:" + packet.getClass().getSimpleName(), 1, Integer::sum);
        long sequence = recordEvent(WireEvent.Direction.CLIENT, packet);
        if (packet instanceof PlayerAuthInputPacket input) {
            clientInputPackets++;
            var log=player.worldDiagnostics();
            if(log!=null)log.event("client_input_rejected",ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                    "inputTick",input.getTick(),"check",check,"detail",detail,
                    "handleTeleport",input.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HANDLE_TELEPORT),
                    "wirePosition",String.valueOf(input.getPosition()),"pendingTeleports",teleports.getQueuedTeleports().size()));
        }
        recordAnomaly(WireEvent.Direction.CLIENT, sequence, check, detail);
        return sequence;
    }


    public String validateClient(BedrockPacket packet) {
        if (!(packet instanceof PlayerAuthInputPacket input)) {
            return null;
        }
        if (input.getTick() < 0) {
            return "negative client tick";
        }
        if (lastInputTick >= 0 && input.getTick() <= lastInputTick) {
            return "non-increasing client tick " + input.getTick() + " <= " + lastInputTick;
        }
        if (!finite(input.getPosition()) || !finite(input.getRotation())) {
            return "non-finite client input vector";
        }
        return null;
    }

    public String validateBackend(BedrockPacket packet) {
        if (packet instanceof MovePlayerPacket move && !finite(move.getPosition())) {
            return "non-finite backend player position";
        }
        if (packet instanceof CorrectPlayerMovePredictionPacket correction && !finite(correction.getPosition())) {
            return "non-finite backend correction position";
        }
        if (packet instanceof CorrectPlayerMovePredictionPacket correction
                && (!finite(correction.getDelta()) || correction.getTick() < 0)) {
            return "invalid backend correction motion or tick";
        }
        return null;
    }


    public void onProxyBlockCorrection(UpdateBlockPacket packet) {
        serverChunkPackets.onPacket(packet);
        packetCounts.merge("proxy:" + packet.getClass().getSimpleName(), 1, Integer::sum);
        recordEvent(WireEvent.Direction.PROXY, packet);
    }

    public void observeBackend(BedrockPacket packet) {
        if(consumeBackendSync(packet))return;
        serverRewindPackets.capture(player, packet);
        backendBlocks.observe(packet);
        backendInventories.observe(packet);
        inventoryResponseSync.backend(packet);
        if(placementPipeline!=null)placementPipeline.observeBackend(packet);
        if(detectionEnabled){authInputPackets().itemUse().observeInventory(packet);if(packet instanceof SetEntityDataPacket data)authInputPackets().itemUse().observeMetadata(data);}
        ac.navro.proxy.packets.server.ServerDataPackets.onPacket(player, packet,
                backendType == ac.navro.proxy.protocol.BackendType.BDS);
        serverChunkPackets.onPacket(packet);
        serverEntityPackets.onPacket(packet);
        ac.navro.proxy.packets.other.VehiclePackets.onPacketSend(this, packet);
        PlayerInventoryPackets.onPacketSend(player, inventory, packet);
        packetsFromBackend++;
        packetCounts.merge("backend:" + packet.getClass().getSimpleName(), 1, Integer::sum);
        long sequence = recordEvent(WireEvent.Direction.BACKEND, packet);
        if (packet instanceof StartGamePacket start) {
            if (backendType == ac.navro.proxy.protocol.BackendType.BDS) { awaitingBackendSpawnPosition = true; backendSpawnRevision++; }
            world.apply(start);
            acknowledgedTerrainGeneration = backendBlocks.generation();
            player.apply(start);
            teleports.setLastKnowValid(player.position().up(playerYOffset()));
            backendProfile = BackendProtocolProfile.from(start);
            backendSyncTransport.accept(backendSync.begin());
        } else if (backendType == ac.navro.proxy.protocol.BackendType.BDS && packet instanceof org.cloudburstmc.protocol.bedrock.packet.RespawnPacket respawn
                && (respawn.getRuntimeEntityId() == 0 || respawn.getRuntimeEntityId() == player.runtimeId())) {
            if (respawn.getState() == org.cloudburstmc.protocol.bedrock.packet.RespawnPacket.State.SERVER_SEARCHING) {
                awaitingBackendSpawnPosition = true;
                backendSpawnRevision++;
            } else if (respawn.getState() == org.cloudburstmc.protocol.bedrock.packet.RespawnPacket.State.SERVER_READY
                    && finite(respawn.getPosition())) {
                awaitingBackendSpawnPosition = true;
                final long revision = ++backendSpawnRevision;
                final Vec3 foot = new Vec3(respawn.getPosition()).down(playerYOffset());
                final var supersededTeleports = List.copyOf(teleports.getQueuedTeleports());


                if (authInputPackets != null) authInputPackets.velocityPackets().clearExternalMotion();
                player.queueAcknowledgment(packet, () -> {
                    if (revision != backendSpawnRevision) return;
                    player.acceptTeleport(foot, false);
                    if (authInputPackets != null) authInputPackets.reset();
                    fluidState.resetMovement();


                    teleports.getQueuedTeleports().removeAll(supersededTeleports);
                    teleports.clearInterpolation();
                    teleports.resetCorrection();
                    teleports.getAuthInputHistory().clear();
                    predictionHistory.clear();tickSnapshots.clear();motionSeeds.clear();teleportMotionSeeds.clear();
                    teleports.setLastKnowValid(foot.up(playerYOffset()));
                    awaitingBackendSpawnPosition = false;
                    var trace = player.worldDiagnostics();
                    if (trace != null) trace.event("backend_spawn_position_acknowledged",
                            ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields("feet",foot.toVector3f().toString()));
                });
            }
        } else if (packet instanceof ChangeDimensionPacket change) {
            if(authInputPackets!=null)authInputPackets.velocityPackets().clearExternalMotion();

            final ChangeDimensionPacket snapshot = change.clone();
            final long terrainGeneration = backendBlocks.generation();
            ac.navro.proxy.packets.input.AuthInputPackets.onPacketSend(player, world, change, () -> {
                world.changeDimension(snapshot.getDimension());
                acknowledgedTerrainGeneration = terrainGeneration;
                player.collisionPhysics.reset();
                player.apply(snapshot);
                teleports.setLastKnowValid(player.position().up(playerYOffset()));
                player.resetFlying();
                fluidState.reset();
                teleports.getQueuedTeleports().clear();
                teleports.clearInterpolation();
                teleports.resetCorrection();
                teleportMotionSeeds.clear();
                tickSnapshots.clear();
                motionSeeds.clear();
                predictionHistory.clear();
                teleports.getAuthInputHistory().clear();
                if(authInputPackets!=null)authInputPackets.reset();
            });
        } else if (packet instanceof LevelChunkPacket || packet instanceof SubChunkPacket) {
            backendChunkPackets++;
        } else if (packet instanceof UpdateBlockPacket || packet instanceof UpdateSubChunkBlocksPacket) {
            backendBlockUpdatePackets++;
        } else if (packet instanceof InventoryContentPacket || packet instanceof InventorySlotPacket) {
            backendInventoryPackets++;
        } else if (packet instanceof NetworkStackLatencyPacket) {
            backendAckPackets++;
            recordAcknowledgment((NetworkStackLatencyPacket) packet, WireEvent.Direction.BACKEND, sequence);
        } else if (packet instanceof SetEntityDataPacket data) {
            EntityCache cached = entities.get(data.getRuntimeEntityId());
            if (cached != null) {
                var snapshot = new org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap();
                data.getMetadata().forEach((type, value) -> snapshot.put(type,
                        value instanceof java.util.EnumMap<?, ?> flags ? flags.clone() : value));
                player.queueAcknowledgment(data, () -> cached.metadata(snapshot));
                cached.properties(data.getProperties());
                cached.lastWireTick(data.getTick());
            }
        } else if (packet instanceof UpdateAttributesPacket attributes) {
            EntityCache cached = entities.get(attributes.getRuntimeEntityId());
            if (cached != null) {
                cached.attributes(attributes.getAttributes());
                cached.lastWireTick(attributes.getTick());
            }
        } else if (packet instanceof MobEffectPacket effect) {
            EntityCache cached = entities.get(effect.getRuntimeEntityId());
            if (cached != null) cached.lastWireTick(effect.getTick());
        }


        if (packet instanceof MovePlayerPacket move
                && move.getRuntimeEntityId() == player.runtimeId()
                && move.getMode() != MovePlayerPacket.Mode.HEAD_ROTATION) {
            serverPosition = move.getPosition();
            player.apply(move);
            queueMovementTeleport(move, true);
        } else if (packet instanceof CorrectPlayerMovePredictionPacket correction) {
            backendCorrectionPackets++;
            lastCorrectionTick = correction.getTick();
            serverPosition = correction.getPosition();
            queueBackendPredictionCorrection(correction);
            var trace=player.worldDiagnostics();
            if(trace!=null){trace.beginMotionTrace();trace.event("backend_prediction_correction",ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                    "sourceTick",correction.getTick(),"position",String.valueOf(correction.getPosition()),
                    "delta",String.valueOf(correction.getDelta()),"onGround",correction.isOnGround(),
                    "predictionType",String.valueOf(correction.getPredictionType())));}
        } else if (packet instanceof SetEntityMotionPacket motion) {
            lastVelocityEntityId = motion.getRuntimeEntityId();
            serverVelocity = motion.getMotion();
            entityMotions.put(motion.getRuntimeEntityId(), motion.getMotion());
            if(detectionEnabled && motion.getRuntimeEntityId()==player.runtimeId())authInputPackets().velocityPackets().onPacketSend(motion);
            else player.apply(motion);
            if (motion.getRuntimeEntityId() == player.runtimeId() && motion.getMotion() != null) {
                motionSeeds.addLast(new WireMotionSeed("SetEntityMotionPacket", motion.getTick(),
                        new ac.navro.anticheat.util.math.Vec3(motion.getMotion()), true));
                while (motionSeeds.size() > 4096) motionSeeds.removeFirst();
            }
        }
        if (packet instanceof AddEntityPacket add) player.apply(add);
        if (packet instanceof SetEntityDataPacket data && player.vehicleData == null) {
            player.apply(data);
            if (data.getRuntimeEntityId() == player.runtimeId()) {
                long revision = player.latestPoseMetadataRevision();
                if (revision > player.acknowledgedPoseMetadataRevision()) {
                    player.queueAcknowledgment(data, () -> player.acknowledgePoseMetadata(revision));
                }
            }
        }
        if (packet instanceof UpdateAttributesPacket attributes) {
            if(detectionEnabled){


                var snapshot=new UpdateAttributesPacket();
                snapshot.setRuntimeEntityId(attributes.getRuntimeEntityId());snapshot.setTick(attributes.getTick());
                snapshot.getAttributes().addAll(attributes.getAttributes());
                var trace=player.worldDiagnostics();if(trace!=null)trace.movementAttributes("movement_attributes_queued",snapshot);
                player.queueAcknowledgment(packet,()->{
                    player.apply(snapshot);
                    if(trace!=null)trace.movementAttributes("movement_attributes_applied",snapshot);
                });
            }
            else player.apply(attributes);
        }
        if (packet instanceof MobEffectPacket effect) {
            if(detectionEnabled)ac.navro.proxy.packets.player.PlayerEffectPackets.onPacket(player, effect);
            else player.apply(effect);
        }
        if (packet instanceof MovementEffectPacket effect) {
            if(detectionEnabled && effect.getEntityRuntimeId()==player.runtimeId()
                    && effect.getEffectType()==org.cloudburstmc.protocol.bedrock.data.MovementEffectType.GLIDE_BOOST){
                effect.setTick(Integer.MIN_VALUE);var snapshot=effect.clone();player.queueAcknowledgment(packet,()->player.apply(snapshot));
            }else player.apply(effect);
        }
        if (detectionEnabled && smoothSetback) serverRewindPackets.onPacketSend(packet);
    }

    private ac.navro.proxy.teleport.data.RewindData queueBackendPredictionCorrection(
            CorrectPlayerMovePredictionPacket packet) {
        if (!detectionEnabled || packet.getPredictionType() == org.cloudburstmc.protocol.bedrock.data.PredictionType.VEHICLE)
            return null;
        long includedMotion = backendType == ac.navro.proxy.protocol.BackendType.BDS
                ? authInputPackets().velocityPackets().externalMotionSequence() : -1;
        var rewind = new ac.navro.proxy.teleport.data.RewindData(packet.getTick(),
                new Vec3(packet.getPosition()), new Vec3(packet.getDelta()), packet.isOnGround(),
                predictionHistory.get(packet.getTick()), playerYOffset(), includedMotion);
        teleports.queue(rewind, accept -> player.queueAcknowledgment(packet, () -> {
            Long ackId = player.getLatencyUtil().currentAcknowledgmentId();
            Runnable complete = () -> {
                accept.run();
                if (backendType == ac.navro.proxy.protocol.BackendType.BDS) teleports.supersedeHardCorrection(rewind);
                var diagnostics = player.worldDiagnostics();
                if (diagnostics != null) diagnostics.event(
                        "backend_prediction_correction_acknowledged",
                        ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                                "sourceTick", rewind.tick(), "ackId", ackId, "backendType", backendType.name()));
            };
            if (backendType == ac.navro.proxy.protocol.BackendType.BDS)
                bdsCorrectionAcknowledgmentDispatcher.accept(complete);
            else complete.run();
        }));
        return rewind;
    }

    public long lastInputTick() {
        return lastInputTick;
    }

    public long lastCorrectionTick() {
        return lastCorrectionTick;
    }

    public long lastVelocityEntityId() {
        return lastVelocityEntityId;
    }

    public Vector3f clientPosition() {
        return clientPosition;
    }

    public Vector3f serverPosition() {
        return serverPosition;
    }

    public Vector3f serverVelocity() {
        return serverVelocity;
    }

    public long packetsFromClient() {
        return packetsFromClient;
    }

    public long packetsFromBackend() {
        return packetsFromBackend;
    }

    public long clientInputPackets() {
        return clientInputPackets;
    }

    public long backendCorrectionPackets() {
        return backendCorrectionPackets;
    }

    public long backendAckPackets() {
        return backendAckPackets;
    }

    public long backendChunkPackets() {
        return backendChunkPackets;
    }

    public long backendBlockUpdatePackets() {
        return backendBlockUpdatePackets;
    }

    public long backendInventoryPackets() {
        return backendInventoryPackets;
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ, int dimension) {
        return world.isChunkLoaded(chunkX, chunkZ, dimension);
    }

    public Vector3f entityPosition(long runtimeId) {
        EntityCache entity = entities.get(runtimeId);
        if (entity == null || entity.position() == null) return null;
        WireVec3 position = entity.position();
        return Vector3f.from(position.x(), position.y(), position.z());
    }

    public Vector3f entityMotion(long runtimeId) {
        return entityMotions.get(runtimeId);
    }

    public CompensatedWorld world() {
        return world;
    }

    public ProtocolFluidStateTracker fluidState(){return fluidState;}

    public CompensatedInventory inventory() {
        return inventory;
    }

    public EntityCache entity(long runtimeId) {
        return entities.get(runtimeId);
    }

    public WirePlayerState player() {
        return player;
    }

    public BackendProtocolProfile backendProfile() {
        return backendProfile;
    }

    public ProtocolCollisionWorld collisionWorld() {
        return new ProtocolCollisionWorld(world, world.dimension(), entities, player.runtimeId(), player.stackable());
    }

    public ProtocolPredictionReplay predictionReplay() {
        return new ProtocolPredictionReplay(collisionWorld());
    }

    public ProtocolEntityTolerance.Sample sampleEntityTolerance() {
        var state = player.collisionPhysics;
        if (state.active() && state.tolerance() != null) {
            var t = state.tolerance();
            return new ProtocolEntityTolerance.Sample(new ProtocolEntityTolerance.Tolerance(
                    t.boat(), t.happyGhast(), t.x(), t.y(), t.z()), state.enforceBoatStep());
        }
        ProtocolEntityTolerance.Sample sample = ProtocolEntityTolerance.sample(player.collisionPhysics.active()
                        ? player.collisionPhysics.actors() : ac.navro.proxy.collision.EntityCollisionPhysics.snapshotActors(entities),
                player.previousPosition(), player.boundingBox(),
                player.vehicleData==null?Long.MIN_VALUE:player.vehicleData.vehicleRuntimeId,
                player.vehicleData!=null, state.active() ? state.enforceBoatStep() : enforceBoatStep,
                player.unvalidatedTickEnd().y, state.active() ? state.selectedStartY() : player.velocity().y);
        enforceBoatStep = sample.enforceBoatStep();
        entityTolerance = sample.tolerance();
        player.setEntityTolerance(sample);
        var tolerance = sample.tolerance();
        player.collisionPhysics.setTolerance(new ac.navro.proxy.collision.EntityCollisionPhysics.ToleranceSample(
                new ac.navro.proxy.collision.EntityCollisionPhysics.Tolerance(tolerance.boat(), tolerance.happyGhast(),
                        tolerance.x(), tolerance.y(), tolerance.z()), sample.enforceBoatStep()));
        return sample;
    }

    public ProtocolEntityTolerance.Tolerance entityToleranceState() {
        return entityTolerance;
    }

    public boolean enforceBoatStep() {
        return enforceBoatStep;
    }

    public Map<Long, EntityCache> entities() {
        return Map.copyOf(entities);
    }

    public List<WireEvent> events() {
        return List.copyOf(eventJournal);
    }

    public List<WireAcknowledgment> acknowledgments() {
        return List.copyOf(acknowledgments);
    }

    public List<WireAnomaly> anomalies() {
        return List.copyOf(anomalies);
    }

    public List<WireTickSnapshot> tickSnapshots() {
        return List.copyOf(tickSnapshots);
    }

    public WireTickSnapshot snapshotAt(long tick) {
        for (WireTickSnapshot snapshot : tickSnapshots) {
            if (snapshot.tick() == tick) return snapshot;
        }
        return null;
    }

    public boolean restoreTick(long tick) {
        WireTickSnapshot snapshot = snapshotAt(tick);
        if (snapshot == null) return false;
        player.restore(snapshot, fluidState);
        entityTolerance = player.entityTolerance();
        enforceBoatStep = player.enforceBoatStep();
        return true;
    }

    public List<WireMotionSeed> motionSeeds() {
        return List.copyOf(motionSeeds);
    }

    public List<ProtocolTeleportMotion> teleportMotionSeeds() {
        return List.copyOf(teleportMotionSeeds);
    }

    public List<ac.navro.anticheat.util.math.Vec3> teleportStartVelocities(long tick) {
        List<ac.navro.anticheat.util.math.Vec3> result = new java.util.ArrayList<>();
        for (ProtocolTeleportMotion seed : teleportMotionSeeds) result.addAll(seed.velocitiesForTick(tick));
        return List.copyOf(result);
    }

    public Map<Long, Long> vehicleByRider() {
        return player.vehicleData==null?Map.of():Map.of(player.runtimeId(),player.vehicleData.vehicleRuntimeId);
    }

    public Long entityRuntimeIdByUniqueId(long uniqueId) { return uniqueIdToRuntimeId.get(uniqueId); }

    public int publishedChunkRadius() { return world.getRadius(); }
    public Vector3i publisherPosition() { return world.getRadiusCenter(); }
    public int publisherRadius() { return world.getRadius(); }

    public void resetMovementHistory() {
        predictionHistory.clear();
        teleports.getAuthInputHistory().clear();
        fluidState.resetMovement();
        tickSnapshots.clear();
        motionSeeds.clear();
        teleportMotionSeeds.clear();
    }

    public void rememberTeleportMotion(ProtocolTeleportMotion seed) {
        teleportMotionSeeds.addLast(seed);
    }


    public void acceptValidatedMovementBaseline(ac.navro.anticheat.data.MovementAabbSnapshot physical,float yOffset){
        teleports.setLastKnowValid(physical.box().feetCenter().up(yOffset));
    }

    private float playerYOffsetInternal() {
        if (player.vehicleData == null) return 1.62001F;
        EntityCache vehicle = entities.get(player.vehicleData.vehicleRuntimeId);
        if (vehicle != null && vehicle.boat()) return 0.375F;
        return 0.0F;
    }

    private long recordEvent(WireEvent.Direction direction, BedrockPacket packet) {
        long tick = -1;
        if (packet instanceof PlayerAuthInputPacket value) tick = value.getTick();
        else if (packet instanceof MovePlayerPacket value) tick = value.getTick();
        else if (packet instanceof CorrectPlayerMovePredictionPacket value) tick = value.getTick();
        else if (packet instanceof NetworkStackLatencyPacket value) tick = value.getTimestamp();
        WireEvent event = new WireEvent(++eventSequence, direction,
                packet.getClass().getSimpleName(), tick, System.nanoTime());
        eventJournal.addLast(event);
        while (eventJournal.size() > 16384) eventJournal.removeFirst();
        return event.sequence();
    }

    private void recordAcknowledgment(NetworkStackLatencyPacket packet,
                                      WireEvent.Direction direction, long sequence) {
        acknowledgments.addLast(new WireAcknowledgment(packet.getTimestamp(), packet.isFromServer(), direction, sequence));
        while (acknowledgments.size() > 4096) acknowledgments.removeFirst();
    }

    private void recordAnomaly(WireEvent.Direction direction, long sequence,
                               String kind, String detail) {
        anomalies.addLast(new WireAnomaly(sequence, direction, kind, detail));
        while (anomalies.size() > 4096) anomalies.removeFirst();
    }

    public int packetCount(String direction, String packetSimpleName) {
        return packetCounts.getOrDefault(direction + ":" + packetSimpleName, 0);
    }

    private static boolean finite(Vector3f value) {
        return value != null && Float.isFinite(value.getX())
                && Float.isFinite(value.getY()) && Float.isFinite(value.getZ());
    }
}
