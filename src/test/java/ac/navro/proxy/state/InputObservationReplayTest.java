package ac.navro.proxy.state;

import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.prediction.ProtocolPredictionReplay;
import ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InputObservationReplayTest {
    private static SessionState session() {
        SessionState result = new SessionState();
        StartGamePacket start = new StartGamePacket();
        start.setRuntimeEntityId(7);
        start.setPlayerPosition(Vector3f.from(143.5F, 65, 105.5F));
        result.observeBackend(start);
        return result;
    }

    private static PlayerAuthInputPacket input(long tick, float y) {
        PlayerAuthInputPacket result = new PlayerAuthInputPacket();
        result.setTick(tick);
        result.setPosition(Vector3f.from(143.5F, y, 105.5F));
        result.setRotation(Vector3f.ZERO);
        result.setDelta(Vector3f.from(0, -0.0784F, 0));
        return result;
    }

    @Test void preSpawnEchoAndInitializedMovementRetainDifferentCoordinateConventions() {
        SessionState state = session();
        state.observeClient(input(0, 65));
        assertEquals(0, state.snapshotAt(0).observedPosition().yOffset());
        assertEquals(65, state.player().unvalidatedPosition().y);
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(7);
        state.observeClient(initialized);
        state.observeClient(input(1, 66.62001F));
        assertEquals(1.62001F, state.snapshotAt(1).observedPosition().yOffset());
        assertEquals(65, state.player().unvalidatedPosition().y, 1.0E-5F);
        assertEquals(65, state.player().previousUnvalidatedPosition().y);
        assertEquals(-0.0784F, state.player().unvalidatedTickEnd().y);
        state.restoreTick(0);
        assertEquals(0, state.player().inputPosition().yOffset());
        assertEquals(65, state.player().inputPositionTarget().y);
    }

    @Test void earlyTeleportConfirmationUsesMovementCoordinatesBeforeActorInitialization() {
        SessionState state = session();
        PlayerAuthInputPacket packet = input(0, 66.62001F);
        packet.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        state.observeClient(packet);
        assertEquals(1.62001F, state.snapshotAt(0).observedPosition().yOffset());
        assertEquals(65, state.player().unvalidatedPosition().y, 1.0E-5F);
    }

    @Test void historicalScoreUsesCapturedOffsetInsteadOfTheCallersCurrentOffset() {
        SessionState state = session();
        WirePlayerState player = state.player();
        player.apply(input(0, 66.62001F), new CompensatedWorld(), new ProtocolFluidStateTracker(), 1.62001F);
        WireTickSnapshot snapshot = player.snapshot(null);
        var selection = state.predictionReplay().selectCandidate(snapshot,
                List.of(new ProtocolPredictionReplay.VelocityCandidate("NORMAL", Vec3.ZERO, 0.375F)),
                Vec3.ZERO);
        assertNotNull(selection.selected());
        assertEquals(0, selection.distanceSquared());
        InputPosition observation = snapshot.observedPosition();
        Vec3 physical = new Vec3(snapshot.position().x, snapshot.position().y, snapshot.position().z);
        assertEquals(Float.floatToRawIntBits(physical.y), Float.floatToRawIntBits(observation.physicalTarget(physical).y));
    }
}
