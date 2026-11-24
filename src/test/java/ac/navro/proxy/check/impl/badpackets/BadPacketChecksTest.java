package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.SessionState;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BadPacketChecksTest {
    @Test void nonFiniteInputAndOutOfRangeRotationFlagWithoutCancellingLikeNavro() {
        WirePlayerState player = new WirePlayerState();
        List<String> alerts = new ArrayList<>();
        player.setAlertSink(alerts::add);
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        input.setPosition(Vector3f.from(Float.NaN, 0, 0));
        input.setDelta(Vector3f.ZERO);
        input.setRotation(Vector3f.from(91, 180, 0));
        assertFalse(new BadPacketA(player).onPacketReceive(input, false));
        assertFalse(new BadPacketB(player).onPacketReceive(input, false));
        assertEquals(2, alerts.size());
        assertTrue(alerts.get(0).contains("BadPacketsA"));
        assertTrue(alerts.get(1).contains("claimedYaw=180.0, wrappedYaw=-180.0"));
    }

    @Test void aliveRespawnRequestsFlagButBothPacketTypesStillReachSessionObservation() {
        SessionState state = new SessionState();
        state.setDetectionEnabled(true);
        List<String> alerts = new ArrayList<>();
        state.player().setAlertSink(alerts::add);
        RespawnPacket packet = new RespawnPacket();
        packet.setState(RespawnPacket.State.CLIENT_READY);
        PlayerActionPacket action = new PlayerActionPacket();
        action.setAction(PlayerActionType.RESPAWN);
        assertTrue(state.receiveClient(packet, 8500));
        assertTrue(state.receiveClient(action, 8500));
        assertEquals(2, state.packetsFromClient());
        assertTrue(state.anomalies().isEmpty());
        assertEquals(2, alerts.size());
        assertTrue(alerts.get(0).contains("BadPacketsC"));
        assertTrue(alerts.get(0).contains("§7x§a1"));
        assertTrue(alerts.get(1).contains("BadPacketsC"));
        assertTrue(alerts.get(1).contains("§7x§a2"));

        state.player().acknowledgedDead = true;
        assertTrue(state.receiveClient(packet, 8500));
        assertTrue(state.receiveClient(action, 8500));
        assertEquals(4, state.packetsFromClient());
        assertEquals(2, alerts.size());
    }
}
