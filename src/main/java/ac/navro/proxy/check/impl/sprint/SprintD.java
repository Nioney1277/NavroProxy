package ac.navro.proxy.check.impl.sprint;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.prediction.live.NativeIds;
import ac.navro.proxy.state.WirePlayerState;

@CheckInfo(name = "Sprint", type = "D", setback = 5)
public final class SprintD extends OffsetHandlerCheck {
    public SprintD(WirePlayerState player, PredictionSample sample) {
        super(player, sample);
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (!isPredictionChecked()) return;
        if (!player.hasEffect(NativeIds.Effect.BLINDNESS)) return;
        if (player.predictionSprinting()) flagWithSetback();
        else reward();
    }
}
