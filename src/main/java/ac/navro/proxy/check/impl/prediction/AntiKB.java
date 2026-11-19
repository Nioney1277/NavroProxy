package ac.navro.proxy.check.impl.prediction;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;


public final class AntiKB extends PredictionSubCheck {
    public AntiKB(final WirePlayerState player, PredictionSample sample) {
        super(player, sample, "AntiKB");
    }

    boolean check(final float offset) {
        if (!canCheckState(offset)
                || sample.bestPossibility.getType() != ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY) {

            return false;
        }

        failCheck("o: " + offset);
        return true;
    }
}
