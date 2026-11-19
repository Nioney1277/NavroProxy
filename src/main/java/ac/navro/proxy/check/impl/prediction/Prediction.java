package ac.navro.proxy.check.impl.prediction;


import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.OffsetHandlerCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.anticheat.util.math.Vec3;

import java.util.function.BooleanSupplier;

@CheckInfo(name = "Prediction")
public class Prediction extends OffsetHandlerCheck {
    private final Phase phase;
    private final GroundSpoof groundSpoof;
    private final NoSlow noSlow;
    private final AntiKB antiKB;
    private final Collisions collisions;
    private final Horizonal horizonal;
    private final Vertical vertical;

    public Prediction(WirePlayerState player, PredictionSample sample) {
        super(player,sample);

        this.phase = new Phase(player,sample);
        this.groundSpoof = new GroundSpoof(player,sample);
        this.noSlow = new NoSlow(player,sample);
        this.antiKB = new AntiKB(player,sample);
        this.collisions = new Collisions(player,sample);
        this.horizonal = new Horizonal(player,sample);
        this.vertical = new Vertical(player,sample);
    }

    @Override
    public void onPredictionComplete(float offset) {

        if (sample.tick < 10 || offset < sample.getMaxOffset()) {
            return;
        }

        if (!shouldDoFail() || offset < sample.alertThreshold) {

            sample.getTeleportUtil().rewind(sample.tick);
            return;
        }

        Vec3 actual = sample.unvalidatedPosition.subtract(sample.prevUnvalidatedPosition);
        Vec3 predicted = sample.position.subtract(sample.prevUnvalidatedPosition);
        routeChecks(() -> phase.check(offset), () -> groundSpoof.check(offset),
                () -> noSlow.check(offset, actual, predicted), () -> antiKB.check(offset),
                () -> {
                    collisions.check(offset);
                    horizonal.check(offset, actual, predicted);
                    vertical.check(offset, actual, predicted);
                });
        sample.getTeleportUtil().rewind(sample.tick);
    }


    static void routeChecks(final BooleanSupplier phase, final BooleanSupplier groundSpoof,
                            final BooleanSupplier noSlow, final BooleanSupplier antiKB,
                            final Runnable genericChecks) {


        if (phase.getAsBoolean() || groundSpoof.getAsBoolean()
                || noSlow.getAsBoolean() || antiKB.getAsBoolean()) {
            return;
        }
        genericChecks.run();
    }

    public boolean shouldDoFail() {
        return sample.tickSinceBlockResync <= 0 && !sample.insideUnloadedChunk && !sample.getTeleportUtil().isTeleporting() && sample.sinceLoadingScreen > 5
                && sample.compensatedWorld.isChunkLoadedAt(sample.position.x, sample.position.z);
    }

}
