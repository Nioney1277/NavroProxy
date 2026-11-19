package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;


class TimerLifecycleTest {
    private record Fixture(WirePlayerState player, AtomicLong clock, List<String> flags, NegativeTimer check) {
        static Fixture create() {
            WirePlayerState player = new WirePlayerState();
            player.sinceLoadingScreen = 20;
            player.inLoadingScreen = false;
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            List<String> flags = new ArrayList<>();
            return new Fixture(player, clock, flags, new NegativeTimer(player, clock::get, flags::add));
        }
        void input(long tick) {
            PlayerAuthInputPacket input = new PlayerAuthInputPacket();
            input.setTick(tick);
            check.onAuthInput(input);
        }
        void ack() {
            check.onLatencyAccepted(new LatencyUtil.Latency(77, clock.get() / 1_000_000L,
                    clock.get(), true, List.of()));
        }
    }

    @Test void tenTpsFlagsUsingTheAcknowledgedClockAndRequestsTheOriginalSetback() {
        Fixture f = Fixture.create();
        f.input(0);
        for (int tick = 1; tick <= 80; tick++) {
            f.clock.set(1_000_000_000L + tick * 100_000_000L);
            f.input(tick);
            f.ack();
            if (tick <= 50) assertTrue(f.flags.isEmpty());
        }
        assertFalse(f.flags.isEmpty());
        assertTrue(f.flags.stream().allMatch(s -> s.matches("-\\d+ms")));
        assertTrue(f.player.pendingCorrectionRequests() > 0);
    }

    @Test void loadingAndDeathResetRatherThanDeductBalanceOrRequestCorrections() {
        Fixture f = Fixture.create();
        f.input(0);
        f.player.inLoadingScreen = true;
        for (int tick = 1; tick <= 100; tick++) {
            f.clock.addAndGet(500_000_000L);
            f.input(tick);
            f.ack();
        }
        assertTrue(f.flags.isEmpty());
        f.player.inLoadingScreen = false;
        f.player.acknowledgedDead = true;
        for (int tick = 101; tick <= 200; tick++) {
            f.clock.addAndGet(500_000_000L);
            f.input(tick);
            f.ack();
        }
        assertTrue(f.flags.isEmpty());
        assertEquals(0, f.player.pendingCorrectionRequests());
        assertEquals(NegativeTimer.DRIFT_NS, f.check.budget.balance);
    }

    @Test void deathAndRespawnResetBeforeAnyNewMovementArrives() {
        Fixture f = Fixture.create();
        f.input(0);
        f.check.onDeath();
        f.clock.addAndGet(10_000_000_000L);
        f.check.onRespawn();
        assertEquals(NegativeTimer.DRIFT_NS, f.check.budget.balance);
        f.input(1);
        f.ack();
        assertTrue(f.flags.isEmpty());
        assertEquals(0, f.player.pendingCorrectionRequests());
        assertEquals(NegativeTimer.DRIFT_NS, f.check.budget.balance);
    }

    @Test void acceleratedPositiveTimerStillRequestsCorrectionWhenLagCreditSuppressesTheAlert() {
        WirePlayerState player = new WirePlayerState();
        player.inLoadingScreen = false;
        player.sinceLoadingScreen = 20;
        player.setMaxBalanceAdvantageMs(8500);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        List<String> flags = new ArrayList<>();
        Timer check = new Timer(player, clock::get, flags::add);
        assertFalse(check.isInvalid(0));
        clock.set(11_000_000_000L);
        assertFalse(check.isInvalid(1));
        assertFalse(check.isInvalid(2));
        assertFalse(check.isInvalid(3));
        assertTrue(check.isInvalid(4));
        assertTrue(flags.isEmpty());
        assertEquals(1, player.pendingCorrectionRequests());
    }
}
