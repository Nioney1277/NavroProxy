package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.data.vanilla.StatusEffect;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;

import java.util.Map;
import java.util.Set;
import java.util.List;


public record WireTickSnapshot(
        long tick,
        long previousTick,
        InputMode inputMode,
        InputInteractionModel interactionModel,
        InputInteractionModel previousInteractionModel,
        ClientPlayMode playMode,
        WireVec3 inputPosition,
        WireVec3 inputRotation,
        WireVec3 previousInputRotation,
        WireVec3 inputDelta,
        InputPosition observedPosition,
        WireVec2 inputMotion,
        WireVec2 rawMoveVector,
        WireVec3 cameraOrientation,
        WireVec2 interactRotation,
        Set<PlayerAuthInputData> inputData,
        long predictedVehicle,
        boolean doingInventoryAction,
        float sneakingAttributeModifier,
        Vec3 input,
        Vec3 position,
        Vec3 previousPosition,
        Vec3 unvalidatedPosition,
        Vec3 previousUnvalidatedPosition,
        Vec3 unvalidatedTickEnd,
        Vec3 velocity,
        Vec3 serverPosition,
        Vec3 serverVelocity,
        boolean onGround,
        boolean horizontalCollision,
        boolean verticalCollision,
        boolean clientSprinting,
        boolean serverSprinting,
        boolean predictionSprinting,
        boolean clientSwimming,
        boolean predictionSwimming,
        boolean clientCrawling,
        boolean predictionCrawling,
        boolean spinAttack,
        boolean horizontalPoseApplied,
        EntityDimensions dimensionsBeforeHorizontalPose,
        EntityDimensions pendingPoseDimensions,
        EntityDimensions readyPoseDimensions,
        boolean readyPoseBeforeMovement,
        EntityDimensions inputDimensions,
        MovementAabbSnapshot inputAabb,
        boolean inputHorizontalPoseApplied,
        EntityDimensions inputStandingDimensions,
        boolean inputNonCrawlingHorizontal,
        boolean inputPoseActive,
        boolean inputWasCrawling,
        EntityDimensions inputReadyPoseDimensions,
        boolean inputReadyPoseBeforeMovement,
        boolean poseMetadataAwaitingAcknowledgment,
        boolean wasdControlled,
        boolean flying,
        boolean sneaking,
        boolean flag6,
        int collisionPenetratingFrames,
        boolean oneWayCollision,
        boolean gliding,
        int glideBoostTicks,
        ProtocolEntityTolerance.Tolerance entityTolerance,
        boolean enforceBoatStep,
        EntityDimensions dimensions,
        Box boundingBox,
        long aabbResizeRevision,
        Map<String, AttributeInstance> attributes,
        Map<Integer, StatusEffect> effects,
        ProtocolFluidStateTracker.Frame fluidState,
        EntityDataMap entityMetadata,
        long poseMetadataRevision,
        long acknowledgedPoseMetadataRevision,
        List<PoseMetadata> pendingPoseMetadata,
        ac.navro.proxy.collision.CollisionPhysicsState.Snapshot collisionState,
        List<ac.navro.proxy.collision.CollisionShapeEntry> movementCollisionEntries,
        boolean wasFlying,
        Set<org.cloudburstmc.protocol.bedrock.data.Ability> abilities,
        Vec3 afterCollision,
        Set<Integer> trackedFlags,
        boolean inputWasSwimming,
        WirePlayerState.SprintSynchronization sprintSynchronization
) {
    public WireTickSnapshot {
        trackedFlags = trackedFlags == null ? Set.of() : Set.copyOf(trackedFlags);
        afterCollision = afterCollision == null ? Vec3.ZERO.clone() : afterCollision.clone();
        abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
        movementCollisionEntries = movementCollisionEntries == null ? List.of() : List.copyOf(movementCollisionEntries);
        inputData = inputData == null ? Set.of() : Set.copyOf(inputData);
        pendingPoseMetadata = pendingPoseMetadata == null ? List.of() : List.copyOf(pendingPoseMetadata);
        entityMetadata = copyEntityDataMap(entityMetadata);
        input = input == null ? null : input.clone();
        position = position == null ? null : position.clone();
        previousPosition = previousPosition == null ? null : previousPosition.clone();
        unvalidatedPosition = unvalidatedPosition == null ? null : unvalidatedPosition.clone();
        previousUnvalidatedPosition = previousUnvalidatedPosition == null ? null : previousUnvalidatedPosition.clone();
        unvalidatedTickEnd = unvalidatedTickEnd == null ? null : unvalidatedTickEnd.clone();
        velocity = velocity == null ? null : velocity.clone();
        serverPosition = serverPosition == null ? null : serverPosition.clone();
        serverVelocity = serverVelocity == null ? null : serverVelocity.clone();
        boundingBox = boundingBox == null ? null : boundingBox.clone();
        if (attributes == null) {
            attributes = Map.of();
        } else {
            java.util.Map<String, AttributeInstance> copy = new java.util.HashMap<>();
            attributes.forEach((name, value) -> copy.put(name, value == null ? null : value.clone()));
            attributes = Map.copyOf(copy);
        }
        if (effects == null) {
            effects = Map.of();
        } else {
            java.util.Map<Integer, StatusEffect> copy = new java.util.HashMap<>();
            effects.forEach((id, value) -> copy.put(id, value == null ? null : value.copy()));
            effects = Map.copyOf(copy);
        }
    }

    @Override public Vec3 position() { return position == null ? null : position.clone(); }
    @Override public Vec3 input() { return input == null ? null : input.clone(); }
    @Override public Map<String, AttributeInstance> attributes() {
        Map<String, AttributeInstance> copy = new java.util.HashMap<>();
        attributes.forEach((name, value) -> copy.put(name, value.clone()));
        return Map.copyOf(copy);
    }
    @Override public Map<Integer, StatusEffect> effects() {
        Map<Integer, StatusEffect> copy = new java.util.HashMap<>();
        effects.forEach((id, value) -> copy.put(id, value.copy()));
        return Map.copyOf(copy);
    }
    @Override public Vec3 previousPosition() { return previousPosition == null ? null : previousPosition.clone(); }
    @Override public Vec3 unvalidatedPosition() { return unvalidatedPosition == null ? null : unvalidatedPosition.clone(); }
    @Override public Vec3 previousUnvalidatedPosition() { return previousUnvalidatedPosition == null ? null : previousUnvalidatedPosition.clone(); }
    @Override public Vec3 unvalidatedTickEnd() { return unvalidatedTickEnd == null ? null : unvalidatedTickEnd.clone(); }
    @Override public Vec3 velocity() { return velocity == null ? null : velocity.clone(); }
    @Override public Vec3 afterCollision() { return afterCollision.clone(); }
    @Override public Vec3 serverPosition() { return serverPosition == null ? null : serverPosition.clone(); }
    @Override public Vec3 serverVelocity() { return serverVelocity == null ? null : serverVelocity.clone(); }
    @Override public Box boundingBox() { return boundingBox == null ? null : boundingBox.clone(); }

    @Override public EntityDataMap entityMetadata() { return copyEntityDataMap(entityMetadata); }

    private static EntityDataMap copyEntityDataMap(EntityDataMap source) {
        EntityDataMap copy = new EntityDataMap();
        if (source != null) {
            source.forEach((type, value) -> copy.put(type, copyMetadataValue(value)));
        }
        return copy;
    }

    private static Object copyMetadataValue(Object value) {
        if (value instanceof java.util.EnumMap<?, ?> enumMap) return enumMap.clone();
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value instanceof int[] ints) return ints.clone();
        if (value instanceof long[] longs) return longs.clone();
        return value;
    }

    public record PoseMetadata(long revision, Map<EntityDataType<?>, Object> values, long sourceTick) {
        public PoseMetadata(long revision, Map<EntityDataType<?>,Object> values) { this(revision, values, -1); }
        public PoseMetadata {
            if (values == null || values.isEmpty()) {
                values = Map.of();
            } else {
                java.util.Map<EntityDataType<?>, Object> copy = new java.util.HashMap<>();
                values.forEach((type, value) -> copy.put(type, copyMetadataValue(value)));
                values = Map.copyOf(copy);
            }
        }

        @Override public Map<EntityDataType<?>, Object> values() {
            java.util.Map<EntityDataType<?>, Object> copy = new java.util.HashMap<>();
            values.forEach((type, value) -> copy.put(type, copyMetadataValue(value)));
            return Map.copyOf(copy);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public EntityDataMap toEntityDataMap() {
            EntityDataMap map = new EntityDataMap();
            values.forEach((type, value) -> map.put((EntityDataType) type, copyMetadataValue(value)));
            return map;
        }

        public static PoseMetadata from(long revision, EntityDataMap metadata) {
            return from(revision,metadata,-1);
        }
        public static PoseMetadata from(long revision, EntityDataMap metadata, long sourceTick) {
            java.util.Map<EntityDataType<?>, Object> values = new java.util.HashMap<>();
            metadata.forEach(values::put);
            return new PoseMetadata(revision, values, sourceTick);
        }
    }
}
