package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.anticheat.util.math.Vec3;


public final class Vertical extends PredictionSubCheck {
    public Vertical(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "Vertical");
    }

    void check(final float offset, final Vec3 actual, final Vec3 predicted) {
        if (!canCheck(offset)) {
            return;
        }
        final float error = verticalError(actual, predicted);
        if (error > sample.getMaxOffset()) {
            failCheck("o: " + error);
        }
    }
}
