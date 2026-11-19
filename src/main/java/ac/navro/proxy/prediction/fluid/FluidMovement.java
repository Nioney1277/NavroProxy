package ac.navro.proxy.prediction.fluid;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.prediction.ClimbableBlockPhysics;

import ac.navro.proxy.prediction.live.*;
import ac.navro.anticheat.util.math.*;
import ac.navro.anticheat.collision.*;
import ac.navro.anticheat.util.MathUtil;


import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public final class FluidMovement {
    private FluidMovement() { }






    public interface Context {
        FluidPoseState fluidPrediction();
        EntityDimensions dimensions();
        Box boundingBox();
        MovementAabbSnapshot snapshotAabb();
        void restoreAabb(MovementAabbSnapshot saved, Vec3 target);
        void applyDimensions(EntityDimensions dimensions);
        boolean hasFlag(int nativeFlag);
        void setClientSwimming(boolean swimming);
        boolean isLivePlayer();
        void movementSamplerPoseDimensions(EntityDimensions before, Box boxBefore, boolean horizontal);
        void trapdoorSamplerPoseDimensions(EntityDimensions before, Box boxBefore, boolean horizontal);
        void trace(String name, Object... values);
    }

    public static void beginInput(Context player) {
        player.trace("POSE_INPUT_BEGIN_BEFORE");
        finishInput(player);


        if (player.fluidPrediction().pendingPoseDimensions != null) {
            player.fluidPrediction().readyPoseDimensions = player.fluidPrediction().pendingPoseDimensions;
            player.fluidPrediction().readyPoseBeforeMovement = false;
        }
        player.fluidPrediction().pendingPoseDimensions = null;
        player.fluidPrediction().captureInputState(
                player.hasFlag(57),
                player.hasFlag(114));
        player.fluidPrediction().inputDimensions = player.dimensions();
        player.fluidPrediction().inputAabb = player.snapshotAabb();
        player.fluidPrediction().inputHorizontalPoseApplied = player.fluidPrediction().horizontalPoseApplied;
        player.fluidPrediction().inputStandingDimensions = player.fluidPrediction().dimensionsBeforeHorizontalPose;
        player.fluidPrediction().inputNonCrawlingHorizontal = nonCrawlingHorizontal(player);
        player.fluidPrediction().inputActive = true;
        player.fluidPrediction().inputReadyPoseDimensions = player.fluidPrediction().readyPoseDimensions;
        player.fluidPrediction().inputReadyPoseBeforeMovement = player.fluidPrediction().readyPoseBeforeMovement;
        player.trace("POSE_INPUT_BEGIN_AFTER");
    }

    private static boolean nonCrawlingHorizontal(Context player) {
        return player.hasFlag(57)
                || player.hasFlag(32)
                || player.hasFlag(56);
    }

    public static void applySwimmingAction(Context player, boolean swimming) {
        player.trace("SWIM_ACTION_BEFORE","requestedSwimming",swimming);

        player.setClientSwimming(swimming);
        updatePose(player);
        player.trace("SWIM_ACTION_AFTER","requestedSwimming",swimming);
    }

    public static void updatePose(Context player) {
        updatePose(player, false);
    }


    public static void updatePoseForMetadata(Context player) {
        updatePose(player, true);
    }

    private static void updatePose(Context player, boolean fromMetadata) {
        player.trace("POSE_UPDATE_BEGIN","fromMetadata",fromMetadata);
        final EntityDimensions dimensionsBefore = player.dimensions();
        final var boxBefore = player.boundingBox();
        final boolean horizontal = player.hasFlag(57)
                || player.hasFlag(114)
                || player.hasFlag(32)
                || player.hasFlag(56);
        final FluidPoseState state = player.fluidPrediction();
        final boolean metadataCrawlRequest = fromMetadata && hasCrawlingSizeRequest(player);





        final boolean preserveClientPhysicalHeight = player.isLivePlayer()
                && nonCrawlingHorizontal(player);
        final EntityDimensions requested = horizontalPose(state,
                state.pendingPoseDimensions != null ? state.pendingPoseDimensions
                        : state.readyPoseDimensions != null ? state.readyPoseDimensions : player.dimensions(),
                horizontal, preserveClientPhysicalHeight);
        final boolean crawlingChanged = state.wasCrawling != player.hasFlag(114);
        player.trace("POSE_REQUEST_COMPUTED","fromMetadata",fromMetadata,
                "requestedDimensions",requested,"horizontalRequested",horizontal,
                "crawlingChanged",crawlingChanged,"metadataCrawlRequest",metadataCrawlRequest);


        if ((metadataCrawlRequest || state.inputActive && crawlingChanged || state.readyPoseDimensions != null
                || state.pendingPoseDimensions != null) && !state.inputNonCrawlingHorizontal
                && !nonCrawlingHorizontal(player)) {
            if (state.inputActive && crawlingChanged && state.readyPoseDimensions != null) {


                state.readyPoseDimensions = requested;
                state.pendingPoseDimensions = null;
                player.trace("POSE_CACHE_REPLACED","reason","crawlingChangedWithReadyRequest");
            } else {
                state.pendingPoseDimensions = requested.equals(state.readyPoseDimensions) ? null : requested;
                player.trace("POSE_CACHE_STAGED","reason","pendingRequestComparedWithReadyRequest");
            }
            if (state.inputActive && state.readyPoseBeforeMovement && state.readyPoseDimensions != null) {
                player.trace("POSE_COMMIT_BEGIN","reason","readyBeforeMovement",
                        "committedDimensions",state.readyPoseDimensions);
                player.applyDimensions(state.readyPoseDimensions);
                state.readyPoseDimensions = null;
                state.readyPoseBeforeMovement = false;
                player.trace("POSE_COMMIT_END","reason","readyBeforeMovement");
            }
        } else {
            player.trace("POSE_COMMIT_BEGIN","reason","immediatePoseBranch",
                    "committedDimensions",requested);
            state.pendingPoseDimensions = null;
            state.readyPoseDimensions = null;
            state.readyPoseBeforeMovement = false;
            player.applyDimensions(requested);
            player.trace("POSE_COMMIT_END","reason","immediatePoseBranch");
        }
        if (!fromMetadata && (dimensionsBefore.width() != player.dimensions().width()
                || dimensionsBefore.height() != player.dimensions().height()) && player.isLivePlayer()) {
            player.movementSamplerPoseDimensions(dimensionsBefore, boxBefore, horizontal);
            player.trapdoorSamplerPoseDimensions(dimensionsBefore, boxBefore, horizontal);
        }
        player.trace("POSE_UPDATE_END","fromMetadata",fromMetadata);
    }


    public static void finishInput(Context player) {
        final FluidPoseState state = player.fluidPrediction();
        if (!state.inputActive) return;
        player.trace("POSE_FINISH_BEGIN");
        final EntityDimensions pending = state.readyPoseDimensions;
        state.inputActive = false;
        state.readyPoseDimensions = null;
        state.readyPoseBeforeMovement = false;
        if (pending != null) {
            player.trace("POSE_COMMIT_BEGIN","reason","finishInput","committedDimensions",pending);
            final EntityDimensions before = player.dimensions();
            final var boxBefore = player.boundingBox();
            player.applyDimensions(pending);
            if (player.isLivePlayer()) {
                player.movementSamplerPoseDimensions(before, boxBefore, state.horizontalPoseApplied);
                player.trapdoorSamplerPoseDimensions(before, boxBefore, state.horizontalPoseApplied);
            }
            player.trace("POSE_COMMIT_END","reason","finishInput");
        }
        player.trace("POSE_FINISH_END");
    }

    public static void acknowledgeDimensions(Context player) {
        player.trace("POSE_CACHE_CLEAR_BEGIN","reason","acknowledgeDimensions");

        player.fluidPrediction().pendingPoseDimensions = null;
        player.fluidPrediction().readyPoseDimensions = null;
        player.fluidPrediction().readyPoseBeforeMovement = false;
        player.trace("POSE_CACHE_CLEAR_END","reason","acknowledgeDimensions");
    }

    public static boolean hasCrawlingSizeRequest(Context player) {
        final var state = player.fluidPrediction();
        return !nonCrawlingHorizontal(player) && (player.hasFlag(114)
                || state.wasCrawling || state.pendingPoseDimensions != null || state.readyPoseDimensions != null);
    }


    public static void queueMetadataPose(Context player, EntityDimensions requested) {
        player.trace("POSE_METADATA_QUEUE_BEGIN","requestedDimensions",requested);
        final var state = player.fluidPrediction();
        state.pendingPoseDimensions = null;
        state.readyPoseDimensions = requested;
        state.readyPoseBeforeMovement = true;
        player.trace("POSE_METADATA_QUEUE_END","requestedDimensions",requested);
    }



    public static boolean finishMetadataPose(Context player, MovementAabbSnapshot physicalBefore,
                                             boolean crawlingBefore, Float width, Float height, Float scale,
                                             boolean flagsAcknowledged) {
        if (!(crawlingBefore || hasCrawlingSizeRequest(player) || flagsAcknowledged)
                || player.fluidPrediction().horizontalPoseApplied) return false;
        final var state = player.fluidPrediction();
        final boolean explicitHeight = height != null && Float.isFinite(height) && height > 0F
                || scale != null && Float.isFinite(scale) && scale > 0F;
        EntityDimensions requested = explicitHeight ? player.dimensions()
                : state.pendingPoseDimensions != null ? state.pendingPoseDimensions
                : state.readyPoseDimensions != null ? state.readyPoseDimensions : player.dimensions();
        final boolean explicitWidth = width != null && Float.isFinite(width) && width > 0F;
        final float acknowledgedWidth = player.dimensions().width();
        if (explicitWidth) requested = withWidth(requested, acknowledgedWidth);
        player.restoreAabb(physicalBefore, physicalBefore.position());


        if (explicitWidth) player.applyDimensions(withWidth(player.dimensions(), acknowledgedWidth));
        queueMetadataPose(player, requested);
        return true;
    }

    public static void acknowledgeWidth(Context player) {
        final FluidPoseState state = player.fluidPrediction();
        if (state.pendingPoseDimensions != null) state.pendingPoseDimensions = withWidth(
                state.pendingPoseDimensions, player.dimensions().width());
        if (state.readyPoseDimensions != null) state.readyPoseDimensions = withWidth(
                state.readyPoseDimensions, player.dimensions().width());
    }

    private static EntityDimensions withWidth(EntityDimensions dimensions, float width) {
        return new EntityDimensions(width, dimensions.height(), dimensions.eyeHeight(), dimensions.fixed());
    }


    public static EntityDimensions horizontalPose(FluidPoseState state,
                                                   EntityDimensions dimensions, boolean horizontal) {
        return horizontalPose(state, dimensions, horizontal, false);
    }

    public static EntityDimensions horizontalPose(FluidPoseState state,
                                                   EntityDimensions dimensions, boolean horizontal,
                                                   boolean preservePhysicalHeight) {
        if (horizontal) {
            if (!state.horizontalPoseApplied) {
                state.dimensionsBeforeHorizontalPose = dimensions;
                state.horizontalPoseApplied = true;
                if (preservePhysicalHeight) return dimensions.withEyeHeight(0.42F);
                return EntityDimensions.fixed(dimensions.width(), 0.6F).withEyeHeight(0.42F);
            }

            return dimensions.withEyeHeight(0.42F);
        }
        if (!state.horizontalPoseApplied) return dimensions;
        final EntityDimensions previous = state.dimensionsBeforeHorizontalPose;
        state.horizontalPoseApplied = false;
        state.dimensionsBeforeHorizontalPose = null;
        return EntityDimensions.fixed(dimensions.width(), previous == null ? 1.8F : previous.height())
                .withEyeHeight(previous == null ? 1.62F : previous.eyeHeight());
    }


 public static void prepare(PredictionContext p){
     p.wire.prepareFluidInput(p.session.world(),p.session.fluidState(),p.position);
     p.fluidPrediction.load(p.session.fluidState());
     p.boundingBox=p.wire.boundingBox().clone();p.dimensions=p.wire.dimensions();
 }
 public static void finishInput(PredictionContext p){p.syncWire();p.wire.finishInput();p.dimensions=p.wire.dimensions();p.boundingBox=p.wire.boundingBox().clone();p.position=p.wire.position().clone();p.fluidPrediction.inputActive=false;}
 public static void updatePose(PredictionContext p){p.wire.updateInputPose();p.boundingBox=p.wire.boundingBox().clone();p.dimensions=p.wire.dimensions();}
 public static void sampleTravelLiquids(PredictionContext p,Object unused){
     p.session.fluidState().sampleTravelLiquids(p.session.world(),p.boundingBox,p.wire.flying());
     p.fluidPrediction.load(p.session.fluidState());
 }
    public static Vec3 prepareVelocity(PredictionContext player, Vec3 velocity) {
        Vec3 prepared = velocity.add(player.fluidPrediction.liquids.selectedPush());
        if (!player.getFlagTracker().isFlying() && player.fluidPrediction.wasInWater && !player.isInLava()
                && (player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.WANT_DOWN_SLOW)
                || player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.WANT_DOWN))) {
            prepared = prepared.add(0, -0.04F, 0);
        }
        return prepared;
    }

    public static boolean jumpingInLiquid(PredictionContext player) {
        return player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMPING)
                || player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
    }

    public static Vec3 swimControl(PredictionContext player, Vec3 velocity) {
        final FluidPredictionState state = player.fluidPrediction;


        if (!state.wasInWater) return velocity;
        return swimControl(velocity, MathUtil.getRotationVector(player.pitch, player.yaw).y,
                player.isPredictionSwimming(), jumpingInLiquid(player), player.getFlagTracker().isFlying(),
                state.breathingInLiquid, state.breathingInAir,
                player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.WANT_DOWN_SLOW));
    }

    public static Vec3 swimControl(Vec3 velocity, float targetY, boolean swimming, boolean jumping,
                                  boolean flying, boolean breathingLiquid, boolean breathingAir,
                                  boolean wantDownSlow) {
        if (!swimming || jumping) return velocity;
        if (targetY > 0 && !breathingLiquid && (!flying || breathingAir && !wantDownSlow)) {
            return new Vec3(velocity.x, 0, velocity.z);
        }
        final float response = flying ? 1.3F : targetY < -0.2F ? 0.085F : 0.06F;
        return new Vec3(velocity.x, velocity.y + (targetY - velocity.y) * response, velocity.z);
    }





    public static Vec3 swimmingJump(Vec3 velocity, boolean inWater, boolean swimming,
                                    boolean headInWater, float swimAmount) {
        if (!(swimming && !headInWater) && !(swimAmount > 0 && swimAmount < 1)) return null;
        return inWater ? new Vec3(velocity.x, 0, velocity.z) : velocity;
    }

    public static Vec3 liquidJump(Vec3 velocity, boolean inWater, boolean touchingLava,
                                  boolean jumping, boolean flying) {
        if (flying || !jumping || !(inWater || touchingLava)) return velocity;
        return velocity.add(0, 0.04F, 0);
    }

    public record WaterParameters(float strength, float depthRatio, float horizontalDrag) { }

    public static WaterParameters waterParameters(int depthLevel, boolean onGround, float movementSpeed,
                                                   float underwaterSpeed, float swimMultiplier,
                                                   boolean sprinting, float waterDrag) {
        float ratio = Math.min(Math.max(0, depthLevel), 3) / 3.0F;
        final float strength;
        if (swimMultiplier > 1) {
            strength = underwaterSpeed * swimMultiplier * (0.7F + 0.3F * ratio);
        } else {
            if (!onGround) ratio *= 0.5F;
            strength = underwaterSpeed + (movementSpeed - underwaterSpeed) * ratio;
        }
        final float baseDrag = sprinting ? 0.9F : Float.isFinite(waterDrag) ? waterDrag : 0.8F;
        final float drag = swimMultiplier > 1 ? baseDrag : baseDrag + (0.54600006F - baseDrag) * ratio;
        return new WaterParameters(strength, ratio, drag);
    }

    public static Vec3 moveRelative(Vec3 velocity, Vec3 input, float strength, float yaw) {
        final float lengthSquared = input.lengthSquared();
        if (lengthSquared < 0.0001F) return velocity;
        return velocity.add(MathUtil.getInputVector(input, strength, yaw));
    }

    public static boolean isClimbing(PredictionContext player) {
        return ClimbableBlockPhysics.isClimbing(player);
    }

    public static float lavaClimbVelocity(float vertical, boolean climbing, boolean sneaking,
                                          boolean climbHaltAtFeet) {
        if (!climbing) return vertical;
        vertical = Math.max(vertical, -0.2F);
        return vertical < 0 && sneaking && climbHaltAtFeet ? 0 : vertical;
    }


}
