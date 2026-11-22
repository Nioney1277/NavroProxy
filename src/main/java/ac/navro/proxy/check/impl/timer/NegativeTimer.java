package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.util.function.Consumer;
import java.util.function.LongSupplier;


@CheckInfo(name = "NegativeTimer", setback = 0)
public final class NegativeTimer extends Timer {
    static final long DRIFT_NS = 1_200_000_000L;
    private boolean awaitFirstInputAfterLifecycle;

    public NegativeTimer(final WirePlayerState player) {
        this(player, System::nanoTime, null);
    }

    NegativeTimer(final WirePlayerState player, final LongSupplier clock, final Consumer<String> report) {
        super(player, clock, report);
    }

    @Override
    protected void acknowledgeLatency(final LatencyUtil.Latency latency, final long now) {
        budget.acknowledgeNegative(latency.ns(), now);
    }

    public void onDeath() {
        resetAfterLifecycle();
    }

    public void onRespawn() {
        resetAfterLifecycle();
    }

    private void resetAfterLifecycle() {
        awaitFirstInputAfterLifecycle = true;
        budget.resetNegative(clock.getAsLong(), player.tick(), DRIFT_NS);
    }


    public void onAuthInput(final PlayerAuthInputPacket input) {
        final long now = clock.getAsLong();
        if (needsTimerReset() || !player.isAlive()) {
            budget.resetNegative(now, player.tick(), DRIFT_NS);
            awaitFirstInputAfterLifecycle = true;
            return;
        }
        if (awaitFirstInputAfterLifecycle) {
            budget.resetNegative(now, player.tick(), DRIFT_NS);
            awaitFirstInputAfterLifecycle = false;
            return;
        }
        final TimerBudget.NegativeAccounting accounting = budget.accountNegative(
                now, player.tick(), DRIFT_NS, timerGrace());
        if (accounting.flag()) {
            report.accept(formatTimerMillis(accounting.evaluatedBalance()));
            pullBack();
        }
    }
}
