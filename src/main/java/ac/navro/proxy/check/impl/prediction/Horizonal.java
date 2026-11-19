package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.anticheat.util.math.Vec3;


public final class Horizonal extends PredictionSubCheck {
    public Horizonal(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "Horizonal");
    }

    void check(final float offset, final Vec3 actual, final Vec3 predicted) {
        if (!canCheck(offset)) {
            return;
        }
        final float error = horizontalError(actual, predicted);
        if (error > sample.getMaxOffset()) {
            failCheck("o: " + error + ", expected: " + predicted.horizontalLengthSquared()
                    + ", actual: " + actual.horizontalLengthSquared());
        }
    }
}
