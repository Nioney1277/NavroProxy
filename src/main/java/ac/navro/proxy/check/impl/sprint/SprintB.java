package ac.navro.proxy.check.impl.sprint;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;

@CheckInfo(name = "Sprint", type = "B", setback = 5)
public final class SprintB extends OffsetHandlerCheck {
    private final PredictionContext prediction;

    public SprintB(WirePlayerState player, PredictionContext prediction) {
        super(player, prediction);
        this.prediction = prediction;
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (!isPredictionChecked()) return;
        final boolean sneaking = player.predictionFlag(EntityFlag.SNEAKING)
                || prediction.getInputData().contains(PlayerAuthInputData.STOP_SNEAKING);
        final var pose = player.fluidPrediction();
        final boolean crawling = pose.inputActive ? pose.wasCrawling
                : player.predictionFlag(EntityFlag.CRAWLING);
        final float multiplier = 0.3F + (prediction.ticksSinceCanSlowdown > 2 ? prediction.sneakingAttributeModifier : 0F);
        if (!(sneaking || crawling) || multiplier >= 0.8F) return;
        if (player.predictionSprinting()) flagWithSetback();
        else reward();
    }
}
