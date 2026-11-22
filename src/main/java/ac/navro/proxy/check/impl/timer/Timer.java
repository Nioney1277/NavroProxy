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


    public boolean isInvalid(long ignoredClientTick) {
        long now=clock.getAsLong();
        if (budget.lastNS==0 || (player.inLoadingScreen
                ? wallClock.getAsLong()-player.loadingScreenStartMs<10_000L
                : player.sinceLoadingScreen<200)) {
            budget.resetBoar(now);
            return false;
        }
        TimerBudget.Accounting accounting=budget.accountBoar(now,player.maxBalanceAdvantageMs());
        if(accounting.flag())report.accept("balance="+accounting.evaluatedBalance());
        budget.lastNS=Math.max(budget.lastNS,clock.getAsLong());
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

        void resetBoar(long now) {
            lastNS=now;
            balance=0;
        }

        record Accounting(long elapsedNS,long requiredNS,long evaluatedBalance,
                          boolean invalid,boolean flag) { }

        Accounting accountBoar(long now,long maxBalanceAdvantageMS) {
            long distance=now-lastNS;
            long evaluated=balance;
            boolean invalid=balance>LIMIT_NS;
            boolean flag=invalid && balance-loseBalance>LIMIT_NS;
            if(invalid) {
                if(!flag)loseBalance-=TICK_NS;
                balance-=TICK_NS;
            } else {
                long maximum=(long)Math.max(0,maxBalanceAdvantageMS*1e6);
                if(balance<=-Math.abs(maximum+TICK_NS) && maxBalanceAdvantageMS>0) {
                    loseBalance=Math.abs(balance);
                    balance=-TICK_NS;
                }
            }
            balance-=distance-TICK_NS;
            lastNS=Math.max(lastNS,now);
            beforeAuthInput=true;
            return new Accounting(distance,TICK_NS,evaluated,invalid,flag);
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
