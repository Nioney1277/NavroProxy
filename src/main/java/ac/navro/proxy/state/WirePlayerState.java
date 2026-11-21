package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.data.vanilla.StatusEffect;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import ac.navro.proxy.prediction.ProtocolInputMath;
import ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.check.api.holder.CheckHolder;
import ac.navro.proxy.util.LatencyUtil;
import ac.navro.proxy.util.EntityBatchEncoder;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;





public final class WirePlayerState implements ac.navro.proxy.prediction.fluid.FluidMovement.Context {
    public WirePlayerState(){
        attributes.put("minecraft:movement",new AttributeInstance(0.1F));
        attributes.put("minecraft:underwater_movement",new AttributeInstance(0.02F));
        attributes.put("minecraft:lava_movement",new AttributeInstance(0.02F));
        trackedFlags.set(19,true);
    }
    private final ac.navro.proxy.player.data.tracker.FlagTracker<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> trackedFlags = new ac.navro.proxy.player.data.tracker.FlagTracker<>();
    public ac.navro.proxy.player.data.tracker.FlagTracker<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> trackedFlags() { return trackedFlags; }
    private MovementMetadata.Context<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> movementMetadataContext;
    public void setMovementMetadataContext(MovementMetadata.Context<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> context) { movementMetadataContext = context; }
    private boolean predictionInputProcessing;
    private boolean nativeSprintSynchronization;
    public void setNativeSprintSynchronization(boolean enabled) { nativeSprintSynchronization = enabled; }
    private static final String SPRINT_MODIFIER = "D208FC00-42AA-4AAD-9276-D5446530DE43";
    private long lastSprintActionTick = -1;
    private final java.util.NavigableMap<Long,Boolean> sprintHistory = new java.util.TreeMap<>();
    public record SprintSynchronization(long lastActionTick, Map<Long,Boolean> history) {
        public SprintSynchronization { history = Map.copyOf(history); }
    }
    private boolean sprintAt(long sourceTick) {
        var entry = sprintHistory.floorEntry(sourceTick);
        return entry != null && entry.getValue();
    }
    private void rememberSprint(long sourceTick, boolean sprinting) {
        sprintHistory.put(sourceTick, sprinting);
        while (sprintHistory.size() > 4096) sprintHistory.pollFirstEntry();
    }
    private static void sprintModifier(AttributeInstance speed, boolean sprinting) {
        speed.removeModifier(SPRINT_MODIFIER);
        if(sprinting)speed.addTemporaryModifier(new AttributeInstance.AttributeModifierData(
                SPRINT_MODIFIER,AttributeInstance.AttributeOperation.MULTIPLY_TOTAL,0.3F));
    }
    public void setPredictionInputProcessing(boolean enabled){predictionInputProcessing=enabled;}
    private java.util.function.Consumer<PlayerAuthInputPacket> predictionInputHandler=packet->{ };
    public void setPredictionInputHandler(java.util.function.Consumer<PlayerAuthInputPacket> handler){predictionInputHandler=java.util.Objects.requireNonNull(handler);}
    public void setPredictionSprintingAction(boolean value){
        var before = worldDiagnostics == null ? null : worldDiagnostics.movementState();
        clientSprinting=serverSprinting=value;setPredictionFlag(EntityFlag.SPRINTING,value);
        if (nativeSprintSynchronization) {
            lastSprintActionTick = tick;
            rememberSprint(tick, value);
        }
        var speed=attributes.get("minecraft:movement");
        if(speed!=null){
            sprintModifier(speed, value);
        }
        if(worldDiagnostics!=null)worldDiagnostics.event("sprint_action",ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "acceptedSprinting",value,"normalizedInput",input.toString(),"before",before,"after",worldDiagnostics.movementState()));
    }
    public static final float JUMP_HEIGHT = 0.42F;
    public static final float STEP_HEIGHT = 0.5625F;
    public static final float GRAVITY = 0.08F;

    private long runtimeId = -1;
    public ac.navro.proxy.player.data.VehicleData vehicleData;
    private long uniqueId = -1;
    private final ac.navro.proxy.prediction.PredictionSyncState predictionSync = new ac.navro.proxy.prediction.PredictionSyncState();
    public ac.navro.proxy.prediction.PredictionSyncState predictionSync() { return predictionSync; }
    private long tick = -1;
    private long previousTick = -1;
    private long lastCorrectionTick = -1;
    public int tickSinceBlockResync;
    public final ac.navro.proxy.collision.CollisionPhysicsState collisionPhysics = new ac.navro.proxy.collision.CollisionPhysicsState();
    private ac.navro.proxy.data.vanilla.GameType gameType = ac.navro.proxy.data.vanilla.GameType.DEFAULT;
    public ac.navro.proxy.data.vanilla.GameType gameType() { return gameType; }
    public void acknowledgeGameType(ac.navro.proxy.data.vanilla.GameType value) { gameType = value; }
    @FunctionalInterface public interface GameTypeEventSink { void event(String name, Object... details); }
    private GameTypeEventSink gameTypeEvents = (name, details) -> { };
    public void setGameTypeEventSink(GameTypeEventSink sink) { gameTypeEvents = java.util.Objects.requireNonNull(sink); }
    public void gameTypeEvent(String name, Object... details) { gameTypeEvents.event(name, details); }
    private boolean flying, wasFlying;
    private Set<org.cloudburstmc.protocol.bedrock.data.Ability> abilities = Set.of();
    public Set<org.cloudburstmc.protocol.bedrock.data.Ability> abilities() { return abilities; }
    public boolean wasFlying() { return wasFlying; }
    public void setFlying(boolean value) { wasFlying = flying; flying = value; }
    public void resetFlying() { flying = wasFlying = false; }
    public void acknowledgeAbilities(Set<org.cloudburstmc.protocol.bedrock.data.Ability> values) {
        abilities = Set.copyOf(values);
        setFlying(abilities.contains(org.cloudburstmc.protocol.bedrock.data.Ability.FLYING)
                || abilities.contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY) && flying);
    }
    public boolean isMovementExempted() {
        return isExempted() || abilities.contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY) || flying || wasFlying;
    }
    private int dimension;
    private int protocolVersion;
    private int deviceOs = -1;
    private final CheckHolder checkHolder = new CheckHolder(this);
    private final LatencyUtil latencyUtil = new LatencyUtil(checkHolder);
    private EntityBatchEncoder entityBatchEncoder;
    private ac.navro.proxy.diagnostics.WorldCollisionDiagnostics worldDiagnostics;
    public ac.navro.proxy.diagnostics.WorldCollisionDiagnostics worldDiagnostics(){return worldDiagnostics;}
    public void setWorldDiagnostics(ac.navro.proxy.diagnostics.WorldCollisionDiagnostics value){worldDiagnostics=value;}
    public EntityBatchEncoder getEntityBatchEncoder() { return entityBatchEncoder; }
    public void setEntityBatchEncoder(EntityBatchEncoder value) { entityBatchEncoder = value; }
    private java.util.function.BiConsumer<BedrockPacket,Runnable> transportAcknowledgment;
    public void setTransportAcknowledgment(java.util.function.BiConsumer<BedrockPacket,Runnable> transport) {
        transportAcknowledgment=java.util.Objects.requireNonNull(transport);
    }
    public void queueAcknowledgment(BedrockPacket packet, Runnable task) {
        if (transportAcknowledgment != null) transportAcknowledgment.accept(packet,task);
        else if (entityBatchEncoder != null) entityBatchEncoder.track(packet, task);
        else latencyUtil.queue(task);
    }
    public Integer currentLoadingScreen;
    public boolean inLoadingScreen = true;
    public int sinceLoadingScreen;
    public boolean acknowledgedDead;


    private boolean awaitingRespawnHealth;
    public boolean awaitingRespawnHealth() { return awaitingRespawnHealth; }
    public void acknowledgeMovementHealth(boolean alive) { awaitingRespawnHealth = !alive; }
    public float sneakingAttributeModifier;
    public boolean doingInventoryAction;
    public long sinceAuthInput = System.currentTimeMillis();
    public long lastAuthInputGapMs;
    private boolean exempted;
    private String name = "unknown";
    private long maxBalanceAdvantageMs = 8500L;
    private java.util.function.Consumer<String> alerts = value -> { };
    private java.util.function.Consumer<String> correctionRequests = value -> { };
    private long pendingCorrectionRequests;
    private Runnable timerTeleport = () -> { };
    public void setTimerTeleport(Runnable action) { timerTeleport = java.util.Objects.requireNonNull(action); }

    public boolean isAlive() { return !acknowledgedDead; }
    public boolean isExempted() { return exempted; }
    private java.util.Set<String> disabledChecks=java.util.Set.of();
    public void setDisabledChecks(java.util.Set<String> names) { disabledChecks=java.util.Set.copyOf(names); }
    public boolean checkEnabled(Class<?> type) {
        var info=type.getDeclaredAnnotation(ac.navro.proxy.check.api.annotations.CheckInfo.class);
        if(info==null)return true;
        String key=info.type().isEmpty()?info.name():info.name()+"-"+info.type();
        if(type==ac.navro.proxy.check.impl.badpackets.BadPacketsC.class
                &&(disabledChecks.contains("BadPacketsC")||disabledChecks.contains("ClientTickOrder")))return false;
        return !disabledChecks.contains(key);
    }
    public void setExempted(boolean value) { exempted = value; }
    public String getName() { return name; }
    public void setName(String value) { name = java.util.Objects.requireNonNull(value); }
    public long maxBalanceAdvantageMs() { return maxBalanceAdvantageMs; }
    public void setMaxBalanceAdvantageMs(long value) { maxBalanceAdvantageMs = value; }
    public void setAlertSink(java.util.function.Consumer<String> sink) { alerts = java.util.Objects.requireNonNull(sink); }
    public void alert(String message) { alerts.accept(message); }
    public void setCorrectionRequestSink(java.util.function.Consumer<String> sink) {
        correctionRequests = java.util.Objects.requireNonNull(sink);
    }
    public long pendingCorrectionRequests() { return pendingCorrectionRequests; }
    public void requestTeleportToLastValid() {
        pendingCorrectionRequests++;
        timerTeleport.run();
    }
    private java.util.function.LongConsumer predictionRewind=tick->{ };
    public void setPredictionRewind(java.util.function.LongConsumer handler){predictionRewind=java.util.Objects.requireNonNull(handler);}
    public void requestRewind(long inputTick) {
        pendingCorrectionRequests++;
        predictionRewind.accept(inputTick);
        correctionRequests.accept("Navro rewind requested for tick " + inputTick);
    }
    public void commitInputTick(long value) {
        previousTick = tick;
        tick = value;
    }

    public CheckHolder getCheckHolder() { return checkHolder; }
    public LatencyUtil getLatencyUtil() { return latencyUtil; }
    public int protocolVersion() { return protocolVersion; }
    public void setProtocolVersion(int value) { protocolVersion = value; }
    public int deviceOs() { return deviceOs; }
    public void setDeviceOs(int value) { deviceOs = value; }
    private InputMode inputMode = InputMode.UNDEFINED;
    private InputInteractionModel interactionModel;
    private InputInteractionModel previousInteractionModel;
    private ClientPlayMode playMode;
    private WireVec3 rawInputPosition;
    private WireVec3 rawInputRotation;
    private WireVec3 previousInputRotation;
    private WireVec3 rawInputDelta;
    private WireVec2 rawInputMotion;
    private WireVec2 rawMoveVector;
    private Vec3 input = Vec3.ZERO;
    private WireVec3 cameraOrientation;
    private WireVec2 interactRotation;
    private Set<PlayerAuthInputData> inputData = Set.of();
    private long predictedVehicle;
    private Vec3 position = Vec3.ZERO;
    private Vec3 previousPosition = Vec3.ZERO;
    private Vec3 unvalidatedPosition = Vec3.ZERO;
    private Vec3 previousUnvalidatedPosition = Vec3.ZERO;
    private Vec3 unvalidatedTickEnd = Vec3.ZERO;
    private InputPosition inputPosition;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 afterCollision = Vec3.ZERO;
    private Vec3 serverPosition;
    private Vec3 serverVelocity;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean verticalCollision;
    private boolean clientSprinting;
    private boolean serverSprinting;
    private boolean predictionSprinting;
    private boolean clientSwimming;
    private boolean predictionSwimming;
    private boolean clientCrawling;
    private boolean predictionCrawling;
    private boolean spinAttack;
    private final ac.navro.proxy.prediction.fluid.FluidPoseState pose = new ac.navro.proxy.prediction.fluid.FluidPoseState();
    public ac.navro.proxy.prediction.fluid.FluidPoseState fluidPrediction() { return pose; }
    public boolean hasFlag(int flag) { return trackedFlags.has(flag); }
    public boolean isLivePlayer() { return true; }
    public void trace(String name, Object... values) { }

    public void movementSamplerPoseDimensions(EntityDimensions before, Box box, boolean horizontal) { }
    public void trapdoorSamplerPoseDimensions(EntityDimensions before, Box box, boolean horizontal) { }
    private boolean poseMetadataAwaitingAcknowledgment;
    private long poseMetadataRevision;
    private long acknowledgedPoseMetadataRevision;
    private final Deque<PendingPoseMetadata> pendingPoseMetadata = new ArrayDeque<>();
    private EntityDimensions dimensions = EntityDimensions.changing(0.6F, 1.8F).withEyeHeight(1.62F);
    private Box boundingBox = Box.EMPTY;
    private long aabbResizeRevision;
    private EntityDataMap metadata = new EntityDataMap();
    private final Map<String, AttributeInstance> attributes = new HashMap<>();
    private final Map<Integer, StatusEffect> effects = new HashMap<>();
    private java.util.List<ac.navro.proxy.collision.CollisionShapeEntry> movementCollisionEntries = java.util.List.of();
    private int collisionPenetratingFrames;
    public void setCollisionPenetratingFrames(int value) { collisionPenetratingFrames = value; }
    public java.util.List<ac.navro.proxy.collision.CollisionShapeEntry> movementCollisionEntries() { return movementCollisionEntries; }
    public void setMovementCollisionEntries(java.util.List<ac.navro.proxy.collision.CollisionShapeEntry> entries) {
        movementCollisionEntries = java.util.List.copyOf(entries);
    }
    public boolean pushTowardsClosestSpace() { return predictionFlag(EntityFlag.PUSH_TOWARDS_CLOSEST_SPACE); }
    private boolean oneWayCollision;
    private int glideBoostTicks;
    private ProtocolEntityTolerance.Tolerance entityTolerance =
            new ProtocolEntityTolerance.Tolerance(false, false, 0, 0, 0);
    private boolean enforceBoatStep;

    public void apply(StartGamePacket packet) {
        predictionSync.clear();
        uniqueId = packet.getUniqueEntityId();
        runtimeId = packet.getRuntimeEntityId();
        dimension = packet.getDimensionId();
        if (packet.getRotation() != null) {
            var rotation = packet.getRotation();
            rawInputRotation = new WireVec3(rotation.getX(), rotation.getY(), rotation.getY());
            previousInputRotation = rawInputRotation;
        }
        if (packet.getPlayerPosition() != null) {
            position = new Vec3(packet.getPlayerPosition());
            previousPosition=position.clone();
            unvalidatedPosition = position.clone();
            previousUnvalidatedPosition = position.clone();
            setBoundingBox(position);
        }
    }

    public void apply(AddEntityPacket packet) {
        if (packet.getRuntimeEntityId() == runtimeId) {
            runtimeId = packet.getRuntimeEntityId();
            uniqueId = packet.getUniqueEntityId();
            if (packet.getPosition() != null) setServerPosition(WireVec3.of(packet.getPosition()));
            if (packet.getMotion() != null) serverVelocity = new Vec3(packet.getMotion());
            applyMetadata(packet.getMetadata());
            applyAttributes(packet.getAttributes());
        }
    }

    public void apply(PlayerAuthInputPacket packet, CompensatedWorld world, ProtocolFluidStateTracker fluidState) {
        apply(packet, world, fluidState, 0);
    }

    public void apply(PlayerAuthInputPacket packet, CompensatedWorld world, ProtocolFluidStateTracker fluidState,
                      float inputYOffset) {
        beginPoseInput();

        if (tick != packet.getTick()) commitInputTick(packet.getTick());
        rawInputPosition = WireVec3.of(packet.getPosition());
        previousInputRotation = rawInputRotation;
        rawInputRotation = WireVec3.of(packet.getRotation());
        rawInputDelta = WireVec3.of(packet.getDelta());
        rawInputMotion = WireVec2.of(packet.getMotion());
        rawMoveVector = WireVec2.of(packet.getRawMoveVector());
        cameraOrientation = WireVec3.of(packet.getCameraOrientation());
        interactRotation = WireVec2.of(packet.getInteractRotation());
        inputData = packet.getInputData() == null ? Set.of() : Set.copyOf(new HashSet<>(packet.getInputData()));
        inputMode = packet.getInputMode();
        previousInteractionModel = interactionModel;
        interactionModel = packet.getInputInteractionModel();
        playMode = packet.getPlayMode();
        predictedVehicle = packet.getPredictedVehicle();
        input = ProtocolInputMath.normalize(rawMoveVector, rawInputMotion, doingInventoryAction);
        if (rawInputPosition != null) {
            captureInputPosition(packet, inputYOffset);
        }
        if(predictionInputProcessing)predictionInputHandler.accept(packet);
        inputData=Set.copyOf(packet.getInputData());
        if (!predictionInputProcessing) {

            for (PlayerAuthInputData action : packet.getInputData()) {
                switch (action) {
                    case START_SPRINTING -> setPredictionSprintingAction(input.z > 0);
                    case STOP_SPRINTING -> setPredictionSprintingAction(false);
                    case START_SNEAKING -> setPredictionFlag(EntityFlag.SNEAKING, true);
                    case STOP_SNEAKING -> setPredictionFlag(EntityFlag.SNEAKING, false);
                    case START_SWIMMING -> applySwimmingAction(true);
                    case STOP_SWIMMING -> applySwimmingAction(false);
                    case START_CRAWLING -> setPredictionFlag(EntityFlag.CRAWLING, true);
                    case STOP_CRAWLING -> setPredictionFlag(EntityFlag.CRAWLING, false);
                    case START_GLIDING -> setPredictionFlag(EntityFlag.GLIDING, true);
                    case STOP_GLIDING -> setPredictionFlag(EntityFlag.GLIDING, false);
                    case START_FLYING -> setFlying(abilities.contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY)
                            || abilities.contains(org.cloudburstmc.protocol.bedrock.data.Ability.FLYING));
                    case STOP_FLYING -> setFlying(false);
                    default -> { }
                }
            }
            horizontalCollision = inputData.contains(PlayerAuthInputData.HORIZONTAL_COLLISION);
            verticalCollision = inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION);
            updateInputPose();
        }
        predictionSprinting = serverSprinting;
        predictionSwimming = clientSwimming;
        predictionCrawling = clientCrawling;
        if (world != null && fluidState != null) {
            prepareFluidInput(world, fluidState, position);
        }
    }


    public void prepareFluidInput(CompensatedWorld world, ProtocolFluidStateTracker fluidState, Vec3 feet) {
        EntityDimensions inputSize = pose.inputDimensions == null ? dimensions : pose.inputDimensions;
        boolean inputHorizontal = pose.inputDimensions == null ? pose.horizontalPoseApplied : pose.inputHorizontalPoseApplied;
        fluidState.beginInput(world, pose.inputAabb, boundingBox, dimensions, feet, inputSize, inputHorizontal,
                flying(), vehicleData != null, pose.wasSwimming, clientSwimming, pose.wasCrawling,
                inputData.toArray(PlayerAuthInputData[]::new), inputView(rawInputRotation));
        setClientSwimming(fluidState.swimming());
        predictionSwimming = clientSwimming;
        updateInputPose();
    }

    private static Vec3 inputView(WireVec3 rotation) {
        if (rotation == null || !rotation.finite()) return null;
        return ac.navro.anticheat.util.MathUtil.getRotationVector(rotation.x(), rotation.y());
    }

    public void apply(MovePlayerPacket packet) {
        if (packet.getRuntimeEntityId() != runtimeId || packet.getMode() == MovePlayerPacket.Mode.HEAD_ROTATION) return;
        setServerPosition(WireVec3.of(packet.getPosition()));

        lastCorrectionTick = packet.getTick();
    }

    public void apply(ChangeDimensionPacket packet) {
        dimension = packet.getDimension();
        if (packet.getPosition() != null) {
            Vec3 target = new Vec3(packet.getPosition());
            previousPosition = position;
            position = target;
            unvalidatedPosition = target.clone();
            serverPosition = target.clone();
            setBoundingBox(target);
        }
    }

    public void apply(SetEntityMotionPacket packet) {
        if (runtimeId >= 0 && packet.getRuntimeEntityId() != runtimeId) return;
        if (packet.getMotion() != null) {
            serverVelocity = new Vec3(packet.getMotion());
            velocity = serverVelocity.clone();
        }
    }

    public void apply(SetEntityDataPacket packet) {
        if (runtimeId >= 0 && packet.getRuntimeEntityId() != runtimeId) return;
        if (vehicleData != null) return;
        queuePoseMetadata(packet.getMetadata(), packet.getTick());
    }

    public void apply(UpdateAttributesPacket packet) {
        if (runtimeId >= 0 && packet.getRuntimeEntityId() != runtimeId) return;
        applyAttributes(packet.getAttributes(), packet.getTick());
    }

    public void apply(MobEffectPacket packet) {
        if (runtimeId >= 0 && packet.getRuntimeEntityId() != runtimeId) return;
        switch (packet.getEvent()) {
            case ADD, MODIFY -> effects.put(packet.getEffectId(),
                    new StatusEffect(packet.getEffectId(), packet.getAmplifier(), packet.getDuration()));
            case REMOVE -> effects.remove(packet.getEffectId());
        }
    }

    public void addEffect(int effectId, int amplifier, int duration) {
        if (!ac.navro.proxy.data.vanilla.NativeStatusEffects.contains(effectId)) return;
        effects.put(effectId, new StatusEffect(effectId, amplifier, duration));
    }

    public void removeEffect(int effectId) {
        effects.remove(effectId);
    }

    public void apply(MovementEffectPacket packet) {
        if (runtimeId >= 0 && packet.getEntityRuntimeId() != runtimeId) return;
        if (packet.getEffectType() == org.cloudburstmc.protocol.bedrock.data.MovementEffectType.GLIDE_BOOST) {
            glideBoostTicks = packet.getDuration()==Integer.MAX_VALUE?1:Math.max(1, packet.getDuration() / 2);
        }
    }

    private void applyMetadata(EntityDataMap update) { applyMetadata(update, -1); }
    private void applyMetadata(EntityDataMap update, long sourceTick) {
        if (update == null) return;
        ac.navro.proxy.util.EntityMetadataUtil.copyProperties(update, metadata);
        var flags = ac.navro.proxy.util.EntityMetadataUtil.copyFlags(update);
        boolean base = ac.navro.proxy.util.EntityMetadataUtil.hasBaseFlags(update);
        boolean extended = ac.navro.proxy.util.EntityMetadataUtil.hasExtendedFlags(update);
        Float width = ac.navro.proxy.util.EntityMetadataUtil.getFloat(update, org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.WIDTH);
        Float height = ac.navro.proxy.util.EntityMetadataUtil.getFloat(update, org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.HEIGHT);
        Float scale = ac.navro.proxy.util.EntityMetadataUtil.getFloat(update, org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.SCALE);


        if (nativeSprintSynchronization && base && sourceTick >= 0 && lastSprintActionTick > sourceTick && flags != null) {
            flags = new HashSet<>(flags);
            if (serverSprinting) flags.add(3); else flags.remove(3);
        }
        if (predictionInputProcessing && movementMetadataContext != null) {
            MovementMetadata.applyClientVisibleMovementMetadata(movementMetadataContext, width, height, scale, flags, base, extended);
        } else {

            var before = snapshotAabb();
            boolean crawlingBefore = ac.navro.proxy.prediction.fluid.FluidMovement.hasCrawlingSizeRequest(this);
            if (flags != null) {
                trackedFlags.restoreFlags(ac.navro.proxy.util.EntityMetadataUtil.mergeFlags(trackedFlags.cloneFlags(), flags, base, extended));
                if (base) { serverSprinting = trackedFlags.has(3); setClientSwimming(trackedFlags.has(57)); }
                ac.navro.proxy.prediction.fluid.FluidMovement.updatePoseForMetadata(this);
            }
            if (width != null) updateWidth(width);
            if (height != null) updateHeight(height);
            if (scale != null) updateScale(scale);
            if (!ac.navro.proxy.prediction.fluid.FluidMovement.finishMetadataPose(this, before, crawlingBefore, width, height, scale, flags != null)) {
                if (height != null && height > 0 || scale != null && scale > 0) ac.navro.proxy.prediction.fluid.FluidMovement.acknowledgeDimensions(this);
                else if (width != null && width > 0) ac.navro.proxy.prediction.fluid.FluidMovement.acknowledgeWidth(this);
            }
        }
        clientCrawling = trackedFlags.has(114);
        spinAttack = trackedFlags.has(56);
        if (nativeSprintSynchronization && base && sourceTick >= lastSprintActionTick && sourceTick >= 0) {
            rememberSprint(sourceTick, serverSprinting);
        }
    }

    public long queuePoseMetadata(EntityDataMap update) {
        return queuePoseMetadata(update, -1);
    }
    public long queuePoseMetadata(EntityDataMap update, long sourceTick) {
        if (update == null || update.isEmpty()) return poseMetadataRevision;
        EntityDataMap copy = ac.navro.proxy.util.EntityMetadataUtil.copy(update);
        long revision = ++poseMetadataRevision;
        pendingPoseMetadata.addLast(new PendingPoseMetadata(revision, copy, sourceTick));
        poseMetadataAwaitingAcknowledgment = true;
        if (worldDiagnostics != null) worldDiagnostics.movementMetadata("movement_metadata_queued", revision, copy, sourceTick);
        return revision;
    }

    public long latestPoseMetadataRevision() {
        return poseMetadataRevision;
    }

    public long acknowledgedPoseMetadataRevision() {
        return acknowledgedPoseMetadataRevision;
    }

    private void applyAttributes(Iterable<AttributeData> values) { applyAttributes(values, -1); }
    private void applyAttributes(Iterable<AttributeData> values, long sourceTick) {
        if (values == null) return;
        for (AttributeData attribute : values) {
            AttributeInstance state = attributes.computeIfAbsent(attribute.getName(), ignored -> new AttributeInstance(attribute.getDefaultValue()));
            if (nativeSprintSynchronization && "minecraft:movement".equals(attribute.getName()) && sourceTick >= 0) {



                float base = attribute.getValue() / (sprintAt(sourceTick) ? 1.3F : 1.0F);
                state.clearModifiers();state.setBaseValue(base);state.setValue(base);
                sprintModifier(state, serverSprinting);
                continue;
            }


            state.clearModifiers();
            state.setBaseValue(attribute.getDefaultValue());
            state.setValue(attribute.getValue());
        }
    }

    private void setServerPosition(WireVec3 value) {
        if (value == null || !value.finite()) return;
        serverPosition = new Vec3(value.x(), value.y(), value.z());
    }

    public void acceptPosition(Vec3 value) {
        previousPosition = position;
        position = value;
        unvalidatedPosition = value.clone();
        setBoundingBox(value);
    }


    public void captureInputPosition(PlayerAuthInputPacket packet, float yOffset) {
        InputPosition observation = InputPosition.capture(packet.getPosition(), yOffset);
        restoreInputObservation(observation, packet.getDelta() == null ? Vec3.ZERO : new Vec3(packet.getDelta()));
    }

    public void restoreInputObservation(InputPosition observation, Vec3 delta) {
        previousUnvalidatedPosition = unvalidatedPosition.clone();
        inputPosition = observation;
        unvalidatedPosition = observation.decoded();
        unvalidatedTickEnd = delta.clone();
    }

    public Vec3 inputPositionResidual(Vec3 physical) {
        return inputPosition == null ? unvalidatedPosition.subtract(physical) : inputPosition.residual(physical);
    }

    public float inputPositionDistance(Vec3 physical) { return inputPositionResidual(physical).length(); }

    public boolean usesVehiclePosition() {
        return predictionInputProcessing ? vehicleData != null : predictedVehicle != 0;
    }

    public Vec3 inputPositionTarget() {
        Vec3 physical = !usesVehiclePosition() && boundingBox.isValid() ? boundingBox.feetCenter() : position;
        return inputPosition == null ? unvalidatedPosition.clone() : inputPosition.physicalTarget(physical);
    }

    public void setResolvedPosition(Vec3 value, Box solved, boolean previous) {
        setResolvedPosition(value, solved, previous, usesVehiclePosition());
    }


    public void setResolvedPosition(Vec3 value, Box solved, boolean previous, boolean trackedVehicle) {
        if (previous) previousPosition = position;
        position = value;
        if (!trackedVehicle) boundingBox = solved.clone();
    }

    public void setResolvedMovement(Box solved, boolean previous) {
        setResolvedPosition(solved.feetCenter(), solved, previous);
    }

    public void rebasePosition(Vec3 value, boolean previous) {
        Box translated = boundingBox.offset(value.subtract(position));
        if (!boundingBox.isValid()) {
            if (previous) previousPosition = position;
            position = value;
            setBoundingBox(value);
        } else setResolvedPosition(value, translated, previous);
    }

    public void acceptInputPosition(Vec3 value, java.util.List<Box> collisions) {
        if (boundingBox.isValid()) {
            if (usesVehiclePosition()) setResolvedPosition(value, boundingBox, false, true);
            else setResolvedMovement(ac.navro.anticheat.collision.AcceptedPositionPhysics.synchronize(boundingBox, value, collisions), false);
        } else {
            position = value;
            setBoundingBox(value);
        }
        collisionPhysics.acceptClientPosition();
    }

    public void acceptObservedInputPosition(java.util.List<Box> collisions) {
        acceptInputPosition(inputPositionTarget(), collisions);
    }


    public void acceptTeleport(Vec3 footPosition, boolean ground) {
        collisionPhysics.reset();
        movementCollisionEntries = java.util.List.of();
        finishInputPose();
        previousPosition = position;
        position = footPosition.clone();
        unvalidatedPosition = footPosition.clone();
        previousUnvalidatedPosition = footPosition.clone();
        inputPosition = null;
        serverPosition = footPosition.clone();
        velocity = Vec3.ZERO.clone();
        afterCollision = Vec3.ZERO.clone();
        serverVelocity = Vec3.ZERO.clone();
        onGround = ground;
        horizontalCollision = false;
        verticalCollision = false;
        collisionPenetratingFrames = 0;
        oneWayCollision = false;
        poseMetadataAwaitingAcknowledgment = !pendingPoseMetadata.isEmpty();
        setBoundingBox(footPosition);
    }

    public void applyDimensions(EntityDimensions value) {
        boolean resized = dimensions.width() != value.width() || dimensions.height() != value.height();
        dimensions = value;
        if (resized || !boundingBox.isValid()) refreshPoseBoundingBox();
    }

    public void refreshPoseBoundingBox() {
        if (usesVehiclePosition()) return;
        float feet = boundingBox.isValid() ? boundingBox.minY : position.y;
        setBoundingBox(new Vec3(position.x, feet, position.z));
    }

    private void setBoundingBox(Vec3 value) {
        boundingBox = dimensions.getBoxAt(value);
        aabbResizeRevision++;
    }

    private void beginPoseInput() { ac.navro.proxy.prediction.fluid.FluidMovement.beginInput(this); }
    public void updateInputPose() { ac.navro.proxy.prediction.fluid.FluidMovement.updatePose(this); }
    public void applySwimmingAction(boolean swimming) { ac.navro.proxy.prediction.fluid.FluidMovement.applySwimmingAction(this, swimming); }
    private void finishInputPose() { ac.navro.proxy.prediction.fluid.FluidMovement.finishInput(this); }

    private void updateWidth(float width) {
        if (Float.isFinite(width) && width > 0) applyDimensions(EntityDimensions.fixed(width, dimensions.height())
                .withEyeHeight(dimensions.eyeHeight()));
    }

    private void updateHeight(float height) {
        if (Float.isFinite(height) && height > 0) applyDimensions(EntityDimensions.fixed(dimensions.width(), height)
                .withEyeHeight(eyeHeightFor(height)));
    }

    private void updateScale(float scale) {
        if (Float.isFinite(scale) && scale > 0) applyDimensions(dimensions.hardScaled(scale));
    }

    private static float eyeHeightFor(float height) {
        if (Math.abs(height - 0.2F) <= 1.0E-3F) return 0.2F;
        if (Math.abs(height - 0.625F) <= 1.0E-3F || Math.abs(height - 0.6F) <= 1.0E-3F) return 0.42F;
        if (Math.abs(height - 1.49F) <= 1.0E-3F || Math.abs(height - 1.65F) <= 1.0E-3F) return 1.26F;
        if (Math.abs(height - 1.5F) <= 1.0E-3F) return 1.27F;
        return 1.62F;
    }

    public void restoreAabb(MovementAabbSnapshot saved, Vec3 target) {
        if (saved == null || target == null) return;
        dimensions = saved.dimensions();
        boundingBox = saved.box().offset(target.subtract(saved.position()));
        position = target.clone();
        aabbResizeRevision = saved.resizeRevision();
    }


    public void prepareReplayAabb(MovementAabbSnapshot start, long precedingResizeRevision) {
        if (start == null) return;
        boolean resized = dimensions.width() != start.dimensions().width()
                || dimensions.height() != start.dimensions().height();
        applyDimensions(start.dimensions());
        if (!resized && start.resizeRevision() != precedingResizeRevision) refreshPoseBoundingBox();
        aabbResizeRevision = start.resizeRevision();
    }


    public void restoreInputPose(WireTickSnapshot frame) {
        if (frame == null) return;
        pose.inputDimensions = frame.inputDimensions();
        pose.inputAabb = frame.inputAabb();
        pose.inputHorizontalPoseApplied = frame.inputHorizontalPoseApplied();
        pose.inputStandingDimensions = frame.inputStandingDimensions();
        pose.inputNonCrawlingHorizontal = frame.inputNonCrawlingHorizontal();
        pose.wasCrawling = frame.inputWasCrawling();
        pose.wasSwimming = frame.inputWasSwimming();
        pose.inputReadyPoseDimensions = frame.inputReadyPoseDimensions();
        pose.inputReadyPoseBeforeMovement = frame.inputReadyPoseBeforeMovement();
        pose.horizontalPoseApplied = pose.inputHorizontalPoseApplied;
        pose.dimensionsBeforeHorizontalPose = pose.inputStandingDimensions;
        pose.inputActive = true;
        pose.pendingPoseDimensions = null;
        pose.readyPoseDimensions = pose.inputReadyPoseDimensions;
        pose.readyPoseBeforeMovement = pose.inputReadyPoseBeforeMovement;
    }


    public void restoreReplayInput(WireTickSnapshot frame, long precedingResizeRevision) {
        tick = frame.tick();
        previousTick = frame.previousTick();
        inputMode = frame.inputMode();
        interactionModel = frame.interactionModel();
        previousInteractionModel = frame.previousInteractionModel();
        playMode = frame.playMode();
        rawInputPosition = frame.inputPosition();
        rawInputRotation = frame.inputRotation();
        previousInputRotation = frame.previousInputRotation();
        rawInputDelta = frame.inputDelta();
        inputPosition = frame.observedPosition();
        rawInputMotion = frame.inputMotion();
        rawMoveVector = frame.rawMoveVector();
        cameraOrientation = frame.cameraOrientation();
        interactRotation = frame.interactRotation();
        inputData = frame.inputData();
        predictedVehicle = frame.predictedVehicle();
        doingInventoryAction = frame.doingInventoryAction();
        sneakingAttributeModifier = frame.sneakingAttributeModifier();
        input = frame.input() == null ? Vec3.ZERO.clone() : frame.input();
        previousPosition = position.clone();
        previousUnvalidatedPosition = unvalidatedPosition.clone();
        unvalidatedPosition = frame.unvalidatedPosition();
        unvalidatedTickEnd = frame.unvalidatedTickEnd();
        clientSprinting = frame.clientSprinting();
        serverSprinting = frame.serverSprinting();
        predictionSprinting = frame.predictionSprinting();
        clientSwimming = frame.clientSwimming();
        predictionSwimming = frame.predictionSwimming();
        clientCrawling = frame.clientCrawling();
        predictionCrawling = frame.predictionCrawling();
        spinAttack = frame.spinAttack();
        trackedFlags.restoreFlags(frame.trackedFlags());
        attributes.clear();
        frame.attributes().forEach((name, value) -> attributes.put(name, value.clone()));

        effects.clear();
        frame.effects().forEach((id, value) -> effects.put(id, value.copy()));
        flying = frame.flying();
        wasFlying = frame.wasFlying();
        abilities = frame.abilities();
        glideBoostTicks = frame.glideBoostTicks();
        prepareReplayAabb(frame.inputAabb(), precedingResizeRevision);
        restoreInputPose(frame);
        pose.inputAabb = snapshotAabb();
    }

    public void finishInput() {
        finishInputPose();
    }

    public void snapshotPredictionMovementState(boolean sprinting,boolean swimming){predictionSprinting=sprinting;predictionSwimming=swimming;}
    public void resetCorrectionObservation(){unvalidatedPosition=position.clone();previousUnvalidatedPosition=position.clone();inputPosition=null;}
    public void completePredictionInput(){previousPosition=position.clone();}
    public void tickPredictionEffects(){effects.entrySet().removeIf(entry->{entry.getValue().tick();return entry.getValue().duration()==0;});}
    public void finishPredictionInput(){glideBoostTicks--;}
    public boolean predictionFlag(EntityFlag flag){return trackedFlags.has(ac.navro.proxy.util.EntityMetadataUtil.nativeFlagId(flag));}
    public void setPredictionFlag(EntityFlag flag,boolean value){
        trackedFlags.set(ac.navro.proxy.util.EntityMetadataUtil.nativeFlagId(flag),value);
        if(flag==EntityFlag.DAMAGE_NEARBY_MOBS)spinAttack=value;
        if(flag==EntityFlag.CRAWLING)clientCrawling=value;
    }
    public void setClientSwimming(boolean value){clientSwimming=value;trackedFlags.set(57,value);}
    public void setServerSprinting(boolean value){serverSprinting=value;}
    public void setPredictionSwimming(boolean value){predictionSwimming=value;}
    public long runtimeId() { return runtimeId; }
    public long uniqueId() { return uniqueId; }
    public long tick() { return tick; }
    public long previousTick() { return previousTick; }
    public int dimension() { return dimension; }
    public InputMode inputMode() { return inputMode; }
    public InputInteractionModel interactionModel() { return interactionModel; }
    public InputInteractionModel previousInteractionModel() { return previousInteractionModel; }
    public ClientPlayMode playMode() { return playMode; }
    public WireVec3 rawInputPosition() { return rawInputPosition; }
    public WireVec3 rawInputRotation() { return rawInputRotation; }
    public WireVec3 previousInputRotation() { return previousInputRotation; }
    public WireVec3 rawInputDelta() { return rawInputDelta; }
    public WireVec2 rawInputMotion() { return rawInputMotion; }
    public WireVec2 rawMoveVector() { return rawMoveVector; }
    public Vec3 input() { return input; }
    public WireVec3 cameraOrientation() { return cameraOrientation; }
    public WireVec2 interactRotation() { return interactRotation; }
    public Set<PlayerAuthInputData> inputData() { return inputData; }
    public long predictedVehicle() { return predictedVehicle; }
    public Vec3 position() { return position; }
    public Vec3 previousPosition() { return previousPosition; }
    public Vec3 unvalidatedPosition() { return unvalidatedPosition; }
    public Vec3 velocity() { return velocity; }
    public void setVelocity(Vec3 value) { velocity = value.clone(); }
    public Vec3 afterCollision() { return afterCollision.clone(); }
    public void setAfterCollision(Vec3 value) { afterCollision = value.clone(); }
    public void setCollisionResult(boolean horizontal, boolean vertical, boolean ground) {
        horizontalCollision = horizontal;
        verticalCollision = vertical;
        onGround = ground;
    }
    public boolean hasCollision() { return predictionFlag(EntityFlag.HAS_COLLISION); }
    public Vec3 serverPosition() { return serverPosition; }
    public Vec3 serverVelocity() { return serverVelocity; }
    public boolean onGround() { return onGround; }
    public boolean horizontalCollision() { return horizontalCollision; }
    public boolean verticalCollision() { return verticalCollision; }
    public boolean clientSprinting() { return clientSprinting; }
    public boolean serverSprinting() { return serverSprinting; }
    public boolean predictionSprinting() { return predictionSprinting; }
    public boolean clientSwimming() { return clientSwimming; }
    public boolean predictionSwimming() { return predictionSwimming; }
    public boolean clientCrawling() { return clientCrawling; }
    public boolean predictionCrawling() { return predictionCrawling; }
    public boolean horizontalPoseApplied() { return pose.horizontalPoseApplied; }
    public boolean poseMetadataAwaitingAcknowledgment() { return poseMetadataAwaitingAcknowledgment; }

    public void acknowledgePoseMetadata(long revision) {
        acknowledgedPoseMetadataRevision = Math.max(acknowledgedPoseMetadataRevision, revision);
        while (!pendingPoseMetadata.isEmpty()
                && pendingPoseMetadata.peekFirst().revision() <= acknowledgedPoseMetadataRevision) {
            var pending = pendingPoseMetadata.removeFirst();
            applyMetadata(pending.metadata(), pending.sourceTick());
            if (worldDiagnostics != null) worldDiagnostics.movementMetadata("movement_metadata_applied", pending.revision(), pending.metadata(), pending.sourceTick());
        }
        poseMetadataAwaitingAcknowledgment = !pendingPoseMetadata.isEmpty();
    }
    public boolean wasdControlled() { return predictionFlag(EntityFlag.WASD_CONTROLLED); }
    public boolean flying() { return flying; }
    public boolean sneaking() { return predictionFlag(EntityFlag.SNEAKING); }
    public boolean flag6() { return trackedFlags.has(6); }
    public boolean stackable() { return predictionFlag(EntityFlag.STACKABLE); }
    public int collisionPenetratingFrames() { return collisionPenetratingFrames; }
    public boolean oneWayCollision() { return oneWayCollision; }
    public boolean gliding() { return predictionFlag(EntityFlag.GLIDING); }
    public int glideBoostTicks() { return glideBoostTicks; }
    public boolean nukkitGlideBoostPending;

    public void beginNukkitGlideBoost(int nativeFlight, boolean expectsNativeRocket) {
        final int flight = Math.max(1, nativeFlight);
        final int provisionalMaxLifetime = 10 * (flight + 1) + 11;
        if (expectsNativeRocket) nukkitGlideBoostPending = true;
        glideBoostTicks = Math.max(glideBoostTicks, provisionalMaxLifetime);
    }
    public ProtocolEntityTolerance.Tolerance entityTolerance() { return entityTolerance; }
    public boolean enforceBoatStep() { return enforceBoatStep; }
    public void setEntityTolerance(ProtocolEntityTolerance.Sample sample) {
        entityTolerance = sample.tolerance();
        enforceBoatStep = sample.enforceBoatStep();
    }
    public EntityDimensions dimensions() { return dimensions; }
    public Box boundingBox() { return boundingBox; }
    public long aabbResizeRevision() { return aabbResizeRevision; }
    public Map<String, AttributeInstance> attributes() { return Map.copyOf(attributes); }
    public Map<Integer, StatusEffect> effects() { return Map.copyOf(effects); }
    public boolean hasEffect(int id) { return effects.containsKey(id); }
    public StatusEffect effect(int id) { return effects.get(id); }
    public MovementAabbSnapshot snapshotAabb() { return new MovementAabbSnapshot(dimensions, position, boundingBox, aabbResizeRevision); }
    public InputPosition inputPosition() { return inputPosition; }
    public Vec3 previousUnvalidatedPosition() { return previousUnvalidatedPosition.clone(); }
    public Vec3 unvalidatedTickEnd() { return unvalidatedTickEnd.clone(); }

    public WireTickSnapshot snapshot(ProtocolFluidStateTracker fluidState) {
        ac.navro.proxy.util.EntityMetadataUtil.writeFlags(metadata, trackedFlags.cloneFlags());
        return new WireTickSnapshot(tick, previousTick, inputMode, interactionModel,
                previousInteractionModel, playMode, rawInputPosition, rawInputRotation, previousInputRotation,
                rawInputDelta, inputPosition, rawInputMotion, rawMoveVector, cameraOrientation, interactRotation,
                inputData, predictedVehicle, doingInventoryAction, sneakingAttributeModifier,
                input, position, previousPosition, unvalidatedPosition,
                previousUnvalidatedPosition, unvalidatedTickEnd,
                velocity, serverPosition, serverVelocity, onGround, horizontalCollision,
                verticalCollision, clientSprinting, serverSprinting, predictionSprinting,
                clientSwimming, predictionSwimming, clientCrawling, predictionCrawling, spinAttack,
                pose.horizontalPoseApplied, pose.dimensionsBeforeHorizontalPose, pose.pendingPoseDimensions,
                pose.readyPoseDimensions, pose.readyPoseBeforeMovement, pose.inputDimensions,
                pose.inputAabb == null ? null : new MovementAabbSnapshot(pose.inputAabb.dimensions(),
                        pose.inputAabb.position(), pose.inputAabb.box(), pose.inputAabb.resizeRevision()),
                pose.inputHorizontalPoseApplied, pose.inputStandingDimensions, pose.inputNonCrawlingHorizontal,
                pose.inputActive, pose.wasCrawling, pose.inputReadyPoseDimensions, pose.inputReadyPoseBeforeMovement,
                poseMetadataAwaitingAcknowledgment,
                wasdControlled(), flying(), sneaking(), flag6(),
                collisionPenetratingFrames, oneWayCollision, gliding(), glideBoostTicks,
                entityTolerance, enforceBoatStep,
                dimensions, boundingBox,
                aabbResizeRevision,
                attributes, effects, fluidState == null ? null : fluidState.snapshot(), metadata,
                poseMetadataRevision, acknowledgedPoseMetadataRevision,
                pendingPoseMetadata.stream().map(value -> WireTickSnapshot.PoseMetadata.from(
                        value.revision(), value.metadata(), value.sourceTick())).toList(), collisionPhysics.snapshot(), movementCollisionEntries, wasFlying, abilities, afterCollision, trackedFlags.cloneFlags(), pose.wasSwimming,
                new SprintSynchronization(lastSprintActionTick, sprintHistory));
    }

    private record PendingPoseMetadata(long revision, EntityDataMap metadata, long sourceTick) {}


    public void restore(WireTickSnapshot snapshot, ProtocolFluidStateTracker fluidState) {
        if (snapshot == null) return;
        var sprintState = snapshot.sprintSynchronization();
        if (sprintState != null) {
            lastSprintActionTick = sprintState.lastActionTick();
            sprintHistory.clear();sprintHistory.putAll(sprintState.history());
        }
        collisionPhysics.restore(snapshot.collisionState());
        movementCollisionEntries = snapshot.movementCollisionEntries();
        flying = snapshot.flying();
        wasFlying = snapshot.wasFlying();
        abilities = snapshot.abilities();
        tick = snapshot.tick();
        previousTick = snapshot.previousTick();
        inputMode = snapshot.inputMode();
        interactionModel = snapshot.interactionModel();
        previousInteractionModel = snapshot.previousInteractionModel();
        playMode = snapshot.playMode();
        rawInputPosition = snapshot.inputPosition();
        rawInputRotation = snapshot.inputRotation();
        previousInputRotation = snapshot.previousInputRotation();
        rawInputDelta = snapshot.inputDelta();
        inputPosition = snapshot.observedPosition();
        rawInputMotion = snapshot.inputMotion();
        rawMoveVector = snapshot.rawMoveVector();
        cameraOrientation = snapshot.cameraOrientation();
        interactRotation = snapshot.interactRotation();
        inputData = snapshot.inputData();
        predictedVehicle = snapshot.predictedVehicle();
        doingInventoryAction = snapshot.doingInventoryAction();
        sneakingAttributeModifier = snapshot.sneakingAttributeModifier();
        input = snapshot.input() == null ? Vec3.ZERO : snapshot.input().clone();
        position = snapshot.position() == null ? Vec3.ZERO : snapshot.position().clone();
        previousPosition = snapshot.previousPosition() == null ? Vec3.ZERO : snapshot.previousPosition().clone();
        unvalidatedPosition = snapshot.unvalidatedPosition() == null ? Vec3.ZERO : snapshot.unvalidatedPosition().clone();
        previousUnvalidatedPosition = snapshot.previousUnvalidatedPosition() == null ? Vec3.ZERO : snapshot.previousUnvalidatedPosition();
        unvalidatedTickEnd = snapshot.unvalidatedTickEnd() == null ? Vec3.ZERO : snapshot.unvalidatedTickEnd();
        velocity = snapshot.velocity() == null ? Vec3.ZERO : snapshot.velocity().clone();
        afterCollision = snapshot.afterCollision();
        serverPosition = snapshot.serverPosition() == null ? null : snapshot.serverPosition().clone();
        serverVelocity = snapshot.serverVelocity() == null ? null : snapshot.serverVelocity().clone();
        onGround = snapshot.onGround();
        horizontalCollision = snapshot.horizontalCollision();
        verticalCollision = snapshot.verticalCollision();
        clientSprinting = snapshot.clientSprinting();
        serverSprinting = snapshot.serverSprinting();
        predictionSprinting = snapshot.predictionSprinting();
        clientSwimming = snapshot.clientSwimming();
        predictionSwimming = snapshot.predictionSwimming();
        clientCrawling = snapshot.clientCrawling();
        predictionCrawling = snapshot.predictionCrawling();
        spinAttack = snapshot.spinAttack();
        pose.horizontalPoseApplied = snapshot.horizontalPoseApplied();
        pose.dimensionsBeforeHorizontalPose = snapshot.dimensionsBeforeHorizontalPose();
        pose.pendingPoseDimensions = snapshot.pendingPoseDimensions();
        pose.readyPoseDimensions = snapshot.readyPoseDimensions();
        pose.readyPoseBeforeMovement = snapshot.readyPoseBeforeMovement();
        pose.inputDimensions = snapshot.inputDimensions();
        MovementAabbSnapshot savedInputAabb = snapshot.inputAabb();
        pose.inputAabb = savedInputAabb == null ? null : new MovementAabbSnapshot(savedInputAabb.dimensions(),
                savedInputAabb.position(), savedInputAabb.box(), savedInputAabb.resizeRevision());
        pose.inputHorizontalPoseApplied = snapshot.inputHorizontalPoseApplied();
        pose.inputStandingDimensions = snapshot.inputStandingDimensions();
        pose.inputNonCrawlingHorizontal = snapshot.inputNonCrawlingHorizontal();
        pose.inputActive = snapshot.inputPoseActive();
        pose.wasCrawling = snapshot.inputWasCrawling();
        pose.wasSwimming = snapshot.inputWasSwimming();
        pose.inputReadyPoseDimensions = snapshot.inputReadyPoseDimensions();
        pose.inputReadyPoseBeforeMovement = snapshot.inputReadyPoseBeforeMovement();
        poseMetadataAwaitingAcknowledgment = snapshot.poseMetadataAwaitingAcknowledgment();
        poseMetadataRevision = snapshot.poseMetadataRevision();
        acknowledgedPoseMetadataRevision = snapshot.acknowledgedPoseMetadataRevision();
        pendingPoseMetadata.clear();
        for (WireTickSnapshot.PoseMetadata value : snapshot.pendingPoseMetadata()) {
            pendingPoseMetadata.addLast(new PendingPoseMetadata(value.revision(), value.toEntityDataMap(), value.sourceTick()));
        }
        dimensions = snapshot.dimensions();
        boundingBox = snapshot.boundingBox() == null ? Box.EMPTY : snapshot.boundingBox().clone();
        aabbResizeRevision = snapshot.aabbResizeRevision();
        collisionPenetratingFrames = snapshot.collisionPenetratingFrames();
        oneWayCollision = snapshot.oneWayCollision();
        glideBoostTicks = snapshot.glideBoostTicks();
        entityTolerance = snapshot.entityTolerance() == null
                ? new ProtocolEntityTolerance.Tolerance(false, false, 0, 0, 0)
                : snapshot.entityTolerance();
        enforceBoatStep = snapshot.enforceBoatStep();
        attributes.clear();
        snapshot.attributes().forEach((name, value) -> attributes.put(name, value == null ? null : value.clone()));
        effects.clear();
        snapshot.effects().forEach((id, value) -> effects.put(id, value == null ? null : value.copy()));
        metadata.clear();
        metadata.putAll(snapshot.entityMetadata());
        trackedFlags.restoreFlags(snapshot.trackedFlags());
        if (fluidState != null) fluidState.restore(snapshot.fluidState());
    }
}
