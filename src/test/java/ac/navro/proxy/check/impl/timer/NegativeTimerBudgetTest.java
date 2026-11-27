package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.check.impl.timer.Timer.TimerBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


class NegativeTimerBudgetTest {
    @Test void normalTwentyTpsDoesNotLoseBalance() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        for (int tick = 1; tick <= 200; tick++) {
            long now = 1_000_000_000L + tick * 50_000_000L;
            var result = budget.accountNegative(now, tick, NegativeTimer.DRIFT_NS, false);
            assertFalse(result.flag());
            assertEquals(NegativeTimer.DRIFT_NS, result.evaluatedBalance());
            budget.acknowledgeNegative(now, now);
        }
    }

    @Test void timerDetailsUseUnpaddedMillisecondsWithoutBalancePrefix() {
        assertEquals("1000ms", Timer.formatTimerMillis(1_000_000_000L));
        assertEquals("-1000ms", Timer.formatTimerMillis(-1_000_000_000L));
        assertEquals("50ms", Timer.formatTimerMillis(50_000_000L));
    }

    @Test void driftBoundaryIsStrictAndEachFlagReturnsOneMovementOfCredit() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        budget.accountNegative(1_050_000_000L, 1, NegativeTimer.DRIFT_NS, false);
        budget.acknowledgeNegative(3_500_000_000L, 3_500_000_000L);
        var boundary = budget.accountNegative(3_500_000_000L, 2, NegativeTimer.DRIFT_NS, false);
        assertEquals(-1_200_000_000L, boundary.evaluatedBalance());
        assertFalse(boundary.flag());

        budget.acknowledgeNegative(3_550_000_001L, 3_700_000_000L);
        var over = budget.accountNegative(3_700_000_000L, 3, NegativeTimer.DRIFT_NS, false);
        assertTrue(over.flag());
        assertEquals(2_400_000_000L, 3_700_000_000L + budget.balance);
        assertTrue(3_700_000_000L + budget.balance
                >= budget.lastMovementPlayerClock - NegativeTimer.DRIFT_NS);
    }

    @Test void slowInputsNeedAnAcknowledgedClientClockToFlag() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        for (int tick = 1; tick <= 100; tick++) {
            assertFalse(budget.accountNegative(1_000_000_000L + tick * 100_000_000L,
                    tick, NegativeTimer.DRIFT_NS, false).flag());
        }
        budget.acknowledgeNegative(11_000_000_000L, 11_000_000_000L);
        assertTrue(budget.accountNegative(11_100_000_000L, 101,
                NegativeTimer.DRIFT_NS, false).flag());
    }

    @Test void onlyTheFirstValidAckAfterMovementAdvancesTheClock() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        budget.accountNegative(1_050_000_000L, 1, NegativeTimer.DRIFT_NS, false);
        budget.acknowledgeNegative(1_100_000_001L, 1_100_000_000L);
        assertTrue(budget.beforeAuthInput, "a future timestamp cannot consume eligibility");
        budget.acknowledgeNegative(1_040_000_000L, 1_100_000_000L);
        budget.acknowledgeNegative(1_090_000_000L, 1_100_000_000L);
        assertEquals(1_040_000_000L, budget.lastMovementPlayerClock);
        assertFalse(budget.beforeAuthInput);
        budget.accountNegative(1_150_000_000L, 2, NegativeTimer.DRIFT_NS, false);
        budget.acknowledgeNegative(1_030_000_000L, 1_150_000_000L);
        assertEquals(1_040_000_000L, budget.lastMovementPlayerClock, "older ACKs cannot move the bound backward");
    }

    @Test void delayedNetworkBatchCanCatchUpWithoutBeingTreatedAsSlowSimulation() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        budget.accountNegative(1_050_000_000L, 1, NegativeTimer.DRIFT_NS, false);
        budget.acknowledgeNegative(1_040_000_000L, 1_050_000_000L);
        for (int tick = 2; tick <= 41; tick++) {
            assertFalse(budget.accountNegative(3_050_000_000L, tick,
                    NegativeTimer.DRIFT_NS, false).flag());
        }
        budget.acknowledgeNegative(3_040_000_000L, 3_050_000_000L);
        assertFalse(budget.accountNegative(3_100_000_000L, 42,
                NegativeTimer.DRIFT_NS, false).flag());
    }

    @Test void idleAndUncheckedIntervalsResetTheSlowTimerWindow() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        budget.accountNegative(1_100_000_000L, 1, NegativeTimer.DRIFT_NS, false);
        budget.acknowledgeNegative(10_000_000_000L, 10_000_000_000L);
        assertFalse(budget.accountNegative(20_000_000_000L, 2,
                NegativeTimer.DRIFT_NS, true).flag());
        assertEquals(21_200_000_000L, 20_000_000_000L + budget.balance);
        assertFalse(budget.beforeAuthInput);
        assertFalse(budget.accountNegative(20_050_000_000L, 3,
                NegativeTimer.DRIFT_NS, false).flag());
    }

    @Test void claimedTickGapsDoNotGrantCreditForUnsimulatedMovement() {
        var budget = new TimerBudget();
        budget.resetNegative(1_000_000_000L, 0, NegativeTimer.DRIFT_NS);
        var gap = budget.accountNegative(1_500_000_000L, 10, NegativeTimer.DRIFT_NS, false);
        assertEquals(750_000_000L, gap.evaluatedBalance());
        assertEquals(2_250_000_000L, 1_500_000_000L + budget.balance);
        budget.accountNegative(1_500_000_000L, 10, NegativeTimer.DRIFT_NS, false);
        budget.accountNegative(1_500_000_000L, 9, NegativeTimer.DRIFT_NS, false);
        assertEquals(2_250_000_000L, 1_500_000_000L + budget.balance);
    }

}
