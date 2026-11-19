package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.check.impl.timer.Timer.TimerBudget;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimerBudgetTest {
    @Test void acknowledgedClockBoundsIdleCreditWithoutErasingPositiveAcceleration() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        budget.balance = -1_000_000_000L;
        budget.beforeAuthInput = true;
        budget.acknowledge(1_950_000_000L, 2_000_000_000L);
        assertEquals(-75_000_000L, budget.balance);
        assertFalse(budget.beforeAuthInput);
        budget.balance = 70_000_000L;
        budget.beforeAuthInput = true;
        budget.acknowledge(1_950_000_000L, 2_000_000_000L);
        assertEquals(70_000_000L, budget.balance);
    }

    @Test void configuredIdleLimitStillCancelsExcessCatchupWithoutTreatingLagAsCheating() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        budget.account(11_000_000_000L, 1, false, 8500);
        assertEquals(-50_000_000L, budget.balance);
        assertEquals(9_950_000_000L, budget.loseBalance);
        TimerBudget.Accounting input = null;
        for (int tick = 2; tick <= 4; tick++) input = budget.account(11_000_000_000L, tick, false, 8500);
        assertNotNull(input);
        assertTrue(input.invalid());
        assertFalse(input.flag());
    }

    @Test void currentSlowArrivalConsumesTheEarlierBurstBeforeTheLimitIsApplied() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 83);

        budget.balance = 97_322_500L;
        final var input = budget.account(1_086_471_354L, 84, false, 8500);
        assertEquals(60_851_146L, input.evaluatedBalance());
        assertFalse(input.invalid());
        assertFalse(input.flag());
    }

    @Test void recordedPostLoadingCatchupRetainsTheTimeSpentWaiting() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(133656609240157L, 59);
        final long[] arrivals = {
                133657025245209L, 133657027116146L, 133657098399844L, 133657100962969L,
                133657148836146L, 133657150795678L, 133657433802188L, 133657448253125L,
                133657500975469L, 133657515128282L, 133657528514532L, 133657598358490L,
                133657648062136L, 133657956790313L, 133657989830625L, 133658009767084L,
                133658022571459L, 133658033100000L, 133658043428438L,

                133658054904271L, 133658080396094L, 133658118573542L,
                133658134014636L, 133658146105938L, 133658232577292L
        };
        for (int i = 0; i < arrivals.length; i++) {
            final int sinceLoading = i + 1;
            final var input = budget.account(arrivals[i], 60 + i, sinceLoading < 20, 8500);
            assertFalse(input.invalid(), "A real-time backed startup catchup is valid at input " + sinceLoading);
            assertFalse(input.flag());
        }
    }

    @Test void aBurstWithNoPriorWaitingStillTriggersTimerAfterTheSameGracePeriod() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        boolean flagged = false;
        for (int i = 1; i <= 25; i++) {
            final var input = budget.account(1_000_000_000L + i * 25_000_000L, i, i < 20, 8500);
            if (i < 20) {
                assertFalse(input.invalid());
                assertEquals(0, budget.balance, "Grace does not bank a speed advantage");
            } else flagged |= input.flag();
        }
        assertTrue(flagged, "Sustained 40TPS input remains detectable");
    }

    @Test void idleThenARealTimeBackedPacketBatchDoesNotLookLikeAcceleration() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        for (int tick = 1; tick <= 30; tick++) {
            final var input = budget.account(3_000_000_000L, tick, tick < 20, 8500);
            assertFalse(input.invalid());
        }
        assertEquals(-500_000_000L, budget.balance);
    }

    @Test void ordinaryTwentyTpsHasNoBalanceDrift() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        for (int tick = 1; tick <= 100; tick++) {
            assertFalse(budget.account(1_000_000_000L + tick * TimerBudget.TICK_NS,
                    tick, tick < 20, 8500).invalid());
            assertEquals(0, budget.balance);
        }
    }

    @Test void enteringLoadingClearsOldLagCreditAndLatencyEligibility() {
        final TimerBudget budget = new TimerBudget();
        budget.reset(1_000_000_000L, 0);
        budget.balance = 90_000_000L;
        budget.loseBalance = 30_000_000L;
        budget.beforeAuthInput = true;
        budget.reset(2_000_000_000L, 20);
        assertEquals(0, budget.balance);
        assertEquals(0, budget.loseBalance);
        assertFalse(budget.beforeAuthInput);
        budget.acknowledge(2_000_000_000L, 2_100_000_000L);
        assertEquals(0, budget.balance);
    }
}
