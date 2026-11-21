package ac.navro.proxy.check.impl.sprint;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.state.WirePlayerState;

@CheckInfo(name = "Sprint", type = "C", setback = 5)
public final class SprintC extends OffsetHandlerCheck {
    public SprintC(WirePlayerState player, PredictionSample sample) {
        super(player, sample);
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (!isPredictionChecked() || !sample.getItemUseTracker().hasPredictionItemSlowdown()) return;
        if (player.predictionSprinting()) flagWithSetback();
        else reward();
    }
}
