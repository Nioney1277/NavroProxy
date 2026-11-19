package ac.navro.proxy.check.impl.prediction;


import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.anticheat.util.math.Vec3;


abstract class PredictionSubCheck extends Check {
    protected final PredictionSample sample;
    private final String checkName;

    PredictionSubCheck(final WirePlayerState player, PredictionSample sample, final String checkName) {
        super(player, checkName, "", false);
        this.checkName = checkName;
        this.sample=sample;
    }

    final boolean canCheck(final float offset) {
        return canCheckState(offset)
                && sample.bestPossibility.getType() != VectorType.VELOCITY;
    }

    final boolean canCheckState(final float offset) {
        return sample.tick >= 10
                && Float.isFinite(offset)
                && offset >= sample.getMaxOffset()
                && sample.tickSinceBlockResync <= 0
                && !sample.insideUnloadedChunk
                && !sample.getTeleportUtil().isTeleporting()
                && sample.sinceLoadingScreen > 5
                && sample.compensatedWorld.isChunkLoadedAt(sample.position.x, sample.position.z);
    }

    final void failCheck(final String verbose) {
        if (sample.disabledChecks.contains(checkName)) {
            return;
        }

        fail(verbose);
    }

    static float horizontalError(final Vec3 actual, final Vec3 predicted) {
        return actual.subtract(predicted).horizontalLength();
    }

    static float verticalError(final Vec3 actual, final Vec3 predicted) {
        return Math.abs(actual.y - predicted.y);
    }
}
