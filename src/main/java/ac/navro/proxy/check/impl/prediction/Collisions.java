package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;


public final class Collisions extends PredictionSubCheck {
    public Collisions(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "Collisions");
    }

    void check(final float offset) {
        if (!canCheck(offset)) {
            return;
        }
        if (sample.unvalidatedTickEnd.distanceTo(sample.velocity) < sample.getMaxOffset()) {
            failCheck("o: " + offset);
        }
    }
}
