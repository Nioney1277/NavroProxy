package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimerBudgetTest {
    private record Fixture(Timer timer,AtomicLong clock,List<String> flags) {
        static Fixture create() {
            var player=new WirePlayerState();player.inLoadingScreen=false;player.sinceLoadingScreen=200;
            var now=new AtomicLong(1_000_000_000L);var flags=new ArrayList<String>();
            var timer=new Timer(player,now::get,flags::add);assertFalse(timer.isInvalid());
            return new Fixture(timer,now,flags);
        }
    }
    @Test void oneArrivalCostsOneTickAndLimitUsesPreviousBalance() {
        var f=Fixture.create();
        assertFalse(f.timer.isInvalid());assertEquals(50_000_000L,f.timer.budget.balance);
        assertFalse(f.timer.isInvalid());assertEquals(100_000_000L,f.timer.budget.balance);
        assertTrue(f.timer.isInvalid());assertEquals(1,f.flags.size());assertEquals(100_000_000L,f.timer.budget.balance);
    }
    @Test void lateArrivalCannotHidePreviouslyAccumulatedAdvantage() {
        var f=Fixture.create();f.timer.budget.balance=97_322_500L;f.clock.set(1_086_471_354L);
        assertTrue(f.timer.isInvalid());
    }
    @Test void latencyAckRaisesNegativeBalanceWithoutDiscardingAcceleration() {
        var f=Fixture.create();var b=f.timer.budget;f.clock.set(2_000_000_000L);
        b.balance=-1_000_000_000L;b.beforeAuthInput=true;
        var latency=new LatencyUtil.Latency(1,1950,1_950_000_000L,true,List.of());
        f.timer.onLatencyAccepted(latency);assertEquals(-75_000_000L,b.balance);assertFalse(b.beforeAuthInput);
        b.balance=70_000_000L;b.beforeAuthInput=true;f.timer.onLatencyAccepted(latency);assertEquals(70_000_000L,b.balance);
    }
}
