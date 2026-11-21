package ac.navro.proxy.check.impl.sprint;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.state.WirePlayerState;

@CheckInfo(name = "Sprint", type = "A", setback = 0)
public final class SprintA extends OffsetHandlerCheck {
    public SprintA(WirePlayerState player, PredictionSample sample) {
        super(player, sample);
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (!isPredictionChecked()) return;
        final var hunger = player.attributes().get("minecraft:player.hunger");
        if (hunger == null || !Float.isFinite(hunger.getValue()) || hunger.getValue() > 6F) return;
        if (player.predictionSprinting()) flagWithSetback();
        else reward();
    }
}
