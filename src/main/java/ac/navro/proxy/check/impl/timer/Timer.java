package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.annotations.Experimental;
import ac.navro.proxy.check.api.impl.PingBasedCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

@Experimental
@CheckInfo(name = "Timer")
public class Timer extends PingBasedCheck {
    protected final TimerBudget budget = new TimerBudget();
    protected final LongSupplier clock;
    protected final Consumer<String> report;

    public Timer(final WirePlayerState player) {
        this(player, System::nanoTime, null);
    }

    protected Timer(final WirePlayerState player, final LongSupplier clock, final Consumer<String> report) {
        super(player);
        this.clock = clock;
        this.report = report == null ? this::fail : report;
    }

    @Override
    public void onLatencyAccepted(LatencyUtil.Latency latency) {
        acknowledgeLatency(latency, clock.getAsLong());
    }

    protected void acknowledgeLatency(final LatencyUtil.Latency latency, final long now) {
        if (!budget.beforeAuthInput) {

            return;
        }

        budget.acknowledge(latency.ns(), now);

    }

    public boolean isInvalid() {
        return isInvalid(player.tick());
    }


    public boolean isInvalid(long inputTick) {
        final long now = clock.getAsLong();

        if (needsTimerReset()) {

            budget.reset(now, inputTick);

            return false;
        }

        final boolean grace = timerGrace();
        final TimerBudget.Accounting accounting = budget.account(now, inputTick, grace,
                player.maxBalanceAdvantageMs());
        if (accounting.invalid()) {

            if (accounting.flag()) {
                report.accept(formatTimerMillis(accounting.evaluatedBalance()));
            } else {

            }

            pullBack();
        }

        return accounting.invalid();
    }

    protected final void pullBack() {
        player.requestTeleportToLastValid();
    }

    protected final boolean needsTimerReset() {
        return budget.lastNS == 0 || player.inLoadingScreen;
    }

    protected final boolean timerGrace() {
        return player.sinceLoadingScreen < 20;
    }

    protected static String formatTimerMillis(final long nanos) {
        return String.format(Locale.ROOT, "%dms", nanos / 1_000_000L);
    }


    static final class TimerBudget {
        static final long TICK_NS = 50_000_000L;
        static final long LIMIT_NS = 63_000_000L;
        long lastNS, balance, prevTick, loseBalance;
        boolean beforeAuthInput;
        long lastMovementPlayerClock;

        void reset(final long now, final long tick) {
            lastNS = now;
            prevTick = tick;
            balance = loseBalance = 0;
            beforeAuthInput = false;
        }

        record Accounting(long elapsedNS, long requiredNS, long evaluatedBalance,
                          boolean invalid, boolean flag) { }

        Accounting account(final long now, final long tick, final boolean grace,
                           final long maxBalanceAdvantageMS) {
            final long elapsed = Math.max(0, now - lastNS);
            final long required = Math.max(0, tick - prevTick) * TICK_NS;


            balance += required - elapsed;


            if (grace) balance = Math.min(0, balance);
            final long maxAdvantage = Math.max(0, maxBalanceAdvantageMS) * 1_000_000L;
            if (maxAdvantage > 0 && balance <= -(maxAdvantage + TICK_NS)) {
                loseBalance = Math.abs(balance);
                balance = -TICK_NS;
            }
            final long evaluated = balance;
            final boolean invalid = !grace && evaluated > LIMIT_NS;
            final boolean flag = invalid && evaluated - loseBalance > LIMIT_NS;
            if (invalid) {
                if (!flag) loseBalance = Math.max(0, loseBalance - TICK_NS);
                balance -= TICK_NS;
            }
            lastNS = Math.max(lastNS, now);
            prevTick = tick;
            beforeAuthInput = !grace;
            return new Accounting(elapsed, required, evaluated, invalid, flag);
        }

        void acknowledge(final long sentNS, final long now) {
            if (!beforeAuthInput) return;
            beforeAuthInput = false;
            if (sentNS > now + balance) {
                final long adjustment = sentNS - (now + balance) - TICK_NS / 2;
                balance += adjustment;
                loseBalance = Math.max(0, loseBalance - adjustment);
            }
        }

        void resetNegative(final long now, final long tick, final long driftNS) {
            lastNS = now;
            prevTick = tick;
            balance = driftNS;
            lastMovementPlayerClock = now - 60_000_000_000L;
            beforeAuthInput = false;
        }

        record NegativeAccounting(long evaluatedBalance, boolean flag) { }

        NegativeAccounting accountNegative(final long now, final long tick,
                                           final long driftNS, final boolean reset) {
            if (reset) {
                resetNegative(now, tick, driftNS);
                return new NegativeAccounting(driftNS, false);
            }
            if (tick <= prevTick) {
                return new NegativeAccounting(balance - (now - lastNS), false);
            }
            balance += TICK_NS - (now - lastNS);
            lastNS = now;
            prevTick = tick;
            beforeAuthInput = true;
            final long evaluated = balance;
            final boolean flag = now + evaluated < lastMovementPlayerClock - driftNS;
            if (flag) balance += TICK_NS;
            final long lowerBound = lastMovementPlayerClock - driftNS - now;
            balance = Math.max(balance, lowerBound);
            return new NegativeAccounting(evaluated, flag);
        }

        void acknowledgeNegative(final long sentNS, final long now) {
            if (!beforeAuthInput || sentNS > now) return;
            lastMovementPlayerClock = Math.max(lastMovementPlayerClock, sentNS);
            beforeAuthInput = false;
        }
    }
}
