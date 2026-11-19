package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BadPacketsCTest {
    private static PlayerAuthInputPacket input(long tick) {
        PlayerAuthInputPacket result = new PlayerAuthInputPacket();
        result.setTick(tick);
        return result;
    }

    @Test void discardedTimerCandidateCannotBeCommittedAndNextInputStillSeesOldTick() {
        WirePlayerState player = new WirePlayerState();
        player.commitInputTick(0);
        player.sinceAuthInput = 1_000;
        AtomicLong now = new AtomicLong(1_020);
        BadPacketsC check = new BadPacketsC(player, now::get);
        assertFalse(check.onPacketReceive(input(1), false));
        assertEquals(0, player.tick());
        check.discard();
        check.commit();
        assertEquals(0, player.tick());
        now.set(1_040);
        assertTrue(check.onPacketReceive(input(2), false));
        check.commit();
        assertEquals(0, player.tick());
    }

    @Test void arrivalRulesAndAlertFormattingMatchNavro() {
        WirePlayerState player = new WirePlayerState();
        player.setName("BedrockPlayer");
        List<String> alerts = new ArrayList<>();
        player.setAlertSink(message -> alerts.add("§aNavro §7>§r §3" + message));
        player.commitInputTick(0);
        player.sinceAuthInput = 1_000;
        AtomicLong now = new AtomicLong(1_030);
        BadPacketsC check = new BadPacketsC(player, now::get);
        assertTrue(check.onPacketReceive(input(2), false));
        assertEquals("§aNavro §7>§r §3§fBedrockPlayer§7 failed§f BadPacketsC §7x§a1 §7expected=1,actual=2,arrivalMs=30", alerts.get(0));
        now.set(1_060);
        assertFalse(check.onPacketReceive(input(2), false));
        check.commit();
        assertEquals(2, player.tick());
        assertEquals(0, player.previousTick());
        assertEquals(60, player.lastAuthInputGapMs);
        assertTrue(check.onPacketReceive(input(2), false));
    }
}
