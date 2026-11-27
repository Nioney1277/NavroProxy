package ac.navro.proxy.check.impl.prediction;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.state.WirePlayerState;


@CheckInfo(name = "VehiclePrediction")
public final class VehiclePredictionCheck extends Check {
    private final PredictionSample sample;
    public VehiclePredictionCheck(WirePlayerState player, PredictionSample sample) {
        super(player);this.sample=sample;
    }
    public void check(float offset, Vec3 expected, Vec3 actual) {
        if (!Float.isFinite(offset) || offset<=sample.getMaxOffset() || offset<sample.alertThreshold
                || sample.tick<10 || sample.tickSinceBlockResync>0 || sample.sinceLoadingScreen<=5
                || sample.insideUnloadedChunk || sample.getTeleportUtil().isTeleporting()) return;
        fail("o: "+offset+", expected: "+expected.toVector3f()+", actual: "+actual.toVector3f());
    }
}
