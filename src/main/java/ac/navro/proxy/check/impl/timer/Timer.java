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
    protected final LongSupplier wallClock;

    public Timer(final WirePlayerState player) {
        this(player, System::nanoTime, null);
    }

    protected Timer(final WirePlayerState player, final LongSupplier clock, final Consumer<String> report) {
        this(player,clock,report,System::currentTimeMillis);
    }
    protected Timer(final WirePlayerState player,final LongSupplier clock,final Consumer<String> report,LongSupplier wallClock) {
        super(player);
        this.wallClock=wallClock;
        this.clock = clock;
        this.report = report == null ? this::fail : report;
    }

    @Override
    public void onLatencyAccepted(LatencyUtil.Latency latency) {
        if (!budget.beforeAuthInput) return;
        budget.beforeAuthInput = false;
        if (latency.ns() > clock.getAsLong() + budget.balance) {
            long distance = (latency.ns() - (clock.getAsLong() + budget.balance))
                    - (TimerBudget.TICK_NS / 2);
            budget.balance += distance;
            budget.loseBalance = Math.max(0, budget.loseBalance - distance);
        }
    }

    public boolean isInvalid() {
        return isInvalid(player.tick());
    }


    public boolean isInvalid(long ignoredClientTick) {
        final boolean skipLoadingScreen = player.inLoadingScreen
                ? wallClock.getAsLong() - player.loadingScreenStartMs < 10_000L
                : player.sinceLoadingScreen < 200;
        if (budget.lastNS == 0 || skipLoadingScreen) {
            budget.resetBoar(clock.getAsLong());
            return false;
        }

        boolean valid = true;
        long distance = clock.getAsLong() - budget.lastNS;
        if (budget.balance > TimerBudget.LIMIT_NS) {
            if (budget.balance - budget.loseBalance <= TimerBudget.LIMIT_NS) {
                budget.loseBalance -= TimerBudget.TICK_NS;
            } else {
                report.accept("balance=" + budget.balance);
            }
            budget.balance -= TimerBudget.TICK_NS;
            valid = false;
        } else {
            long maximum = (long) Math.max(0, player.maxBalanceAdvantageMs() * 1e6);
            if (budget.balance <= -Math.abs(maximum + TimerBudget.TICK_NS)
                    && player.maxBalanceAdvantageMs() > 0) {
                budget.loseBalance = Math.abs(budget.balance);
                budget.balance = -TimerBudget.TICK_NS;
            }
        }
        budget.balance -= distance - TimerBudget.TICK_NS;
        budget.lastNS = Math.max(budget.lastNS, clock.getAsLong());
        budget.beforeAuthInput = true;
        return !valid;
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

        void resetBoar(long now) {
            lastNS=now;
            balance=0;
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
