package ac.navro.proxy.check.api.impl;

import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;

public class OffsetHandlerCheck extends Check {
    protected final PredictionSample sample;
    private boolean predictionChecked;
    public OffsetHandlerCheck(WirePlayerState player,PredictionSample sample){super(player);this.sample=sample;}
    public void onPredictionComplete(float offset){ }
    public final void onPredictionComplete(float offset,boolean checked){predictionChecked=checked;onPredictionComplete(offset);}
    protected final boolean isPredictionChecked(){return predictionChecked;}
}
