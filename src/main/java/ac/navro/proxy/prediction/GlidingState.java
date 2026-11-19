package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;


public final class GlidingState {
    private GlidingState() {}


    public static boolean filterInvalidState(final PredictionContext player) {
        if (!player.getFlagTracker().has("GLIDING")
                || !(player.onGround || player.vehicleData != null
                || player.hasEffect(NativeIds.Effect.LEVITATION))) {
            return false;
        }

        player.getFlagTracker().set("GLIDING", false);
        FluidMovement.updatePose(player);
        return true;
    }
}
