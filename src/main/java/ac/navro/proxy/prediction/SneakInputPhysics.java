package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public final class SneakInputPhysics {
    private SneakInputPhysics() { }

    public static boolean shouldScale(final PredictionContext player) {
        final boolean sneaking = player.getFlagTracker().has("SNEAKING")
                || player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.STOP_SNEAKING);


        final boolean crawling = player.fluidPrediction.inputActive ? player.fluidPrediction.wasCrawling
                : player.getFlagTracker().has("CRAWLING");

        return (sneaking || crawling
                || player.getFlagTracker().has("GLIDING")) && !player.fluidPrediction.waterTravel;
    }

    public static Vec3 scale(final PredictionContext player, final Vec3 input,
                             final boolean swiftSneak, final boolean commit) {
        final int ticks = shouldScale(player) ? player.ticksSinceCanSlowdown + 1 : 0;
        if (commit) player.ticksSinceCanSlowdown = ticks;
        if (ticks == 0) return input;
        final float multiplier = 0.3F + (ticks > 2 && swiftSneak ? player.sneakingAttributeModifier : 0);
        return input.multiply(MathUtil.clamp(multiplier, 0, 1));
    }
}
