package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SpawnPositionLifecycleTest {
    private static SessionState session(String engine, float startY) {
        var state = new SessionState();
        state.setProtocolVersion(2225);
        state.player().setProtocolVersion(2225);
        state.setDetectionEnabled(true);
        var start = new StartGamePacket();
        start.setRuntimeEntityId(7);
        start.setUniqueEntityId(70);
        start.setServerEngine(engine);
        start.setPlayerPosition(Vector3f.from(8, startY, 8));
        state.observeBackend(start);
        var world = state.world();
        world.put(0, 0, CompensatedWorld.CachedChunk.network(-64, 320, 24, world.airNetworkId()));
        int grass = NetworkBlockStateRegistry.forProtocol(2225, false).states().values().stream()
                .filter(block -> block.is("minecraft:grass_block")).findFirst().orElseThrow().networkId();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) world.updateBlock(x, 3, z, 0, grass);
        return state;
    }

    @Test void offsetStartupEchoAndFirstGroundedFramesDoNotCreateAnAirborneBaseline() {
        // Live 2026-11-22 sequence: five pre-initialization echoes at Y=5.621,
        // then one movement frame at that Y, then grounded input at Y=5.62001.
        var state = session("Unrecognized core", 5.621F);
        var alerts = new ArrayList<String>();
        state.player().setAlertSink(alerts::add);
        var corrections = AuthInputPacketsTest.transport(state);
        for (int tick = 0; tick < 5; tick++) {
            var input = AuthInputPacketsTest.input(40 + tick, 8, 4, 8, Vec3.ZERO);
            input.setPosition(Vector3f.from(8, 5.621F, 8));
            state.observeClient(input);
            assertEquals(5.621F, state.player().position().y);
            assertEquals(5.621F, state.player().unvalidatedPosition().y);
        }
        state.observeClient(new SetLocalPlayerAsInitializedPacket());
        state.player().inLoadingScreen = false;
        state.player().sinceLoadingScreen = 0;
        for (int tick = 5; tick <= 18; tick++) {
            var input = AuthInputPacketsTest.input(40 + tick, 8, 4, 8, new Vec3(0, -.0784F, 0));
            if (tick == 5) input.setPosition(Vector3f.from(8, 5.621F, 8));
            else input.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
            state.observeClient(input);
            var prediction = state.authInputPackets().lastResult();
            assertNotNull(prediction);
            assertTrue(prediction.rawOffset() < .0001F, prediction.toString());
            assertEquals(tick == 5 ? 4.00099F : 4, state.player().position().y, .00001F);
        }
        assertTrue(alerts.isEmpty(), alerts.toString());
        assertTrue(corrections.isEmpty(), corrections.toString());
        // A later claim matching the other convention cannot switch origins.
        state.observeClient(AuthInputPacketsTest.input(59, 8, 5.62001F, 8, Vec3.ZERO));
        assertEquals(4, state.player().position().y, .00001F);
        assertEquals(1.62001F, state.authInputPackets().lastResult().rawOffset(), .00001F);
        assertFalse(alerts.isEmpty());
        assertEquals(1, corrections.size());
    }

    @Test void zeroOffsetStartupKeepsItsFootCoordinateConvention() {
        for (String engine : new String[]{"Nukkit-MOT", "", "Unrecognized core"}) {
            var state = session(engine, 4);
            assertEquals(4, state.player().position().y);
            assertEquals(5.62001F, state.teleports().getLastKnowValid().y);
            var echo = AuthInputPacketsTest.input(40, 8, 4, 8, Vec3.ZERO);
            echo.setPosition(Vector3f.from(8, 4, 8));
            state.observeClient(echo);
            assertEquals(4, state.player().unvalidatedPosition().y);
            assertEquals(0, state.player().inputPosition().yOffset());
            state.observeClient(new SetLocalPlayerAsInitializedPacket());
            state.player().inLoadingScreen = false;
            state.player().sinceLoadingScreen = 20;
            for (int tick = 41; tick <= 53; tick++) {
                state.observeClient(AuthInputPacketsTest.input(tick, 8, 4, 8, new Vec3(0, -.0784F, 0)));
                assertTrue(state.authInputPackets().lastResult().rawOffset() < .0001F);
                assertEquals(4, state.player().position().y);
            }
        }
    }

    @Test void firstInputCannotIntroduceAnArbitrarySpawnPosition() {
        var state = session("Unrecognized core", 5.621F);
        state.observeClient(new SetLocalPlayerAsInitializedPacket());
        state.player().inLoadingScreen = false;
        state.player().sinceLoadingScreen = 0;
        state.observeClient(AuthInputPacketsTest.input(40, 900, 100, 900, Vec3.ZERO));
        assertEquals(8, state.player().position().x);
        assertEquals(8, state.player().position().z);
        assertEquals(5.621F, state.player().position().y);
        assertTrue(state.authInputPackets().lastResult().rawOffset() > 100);
    }

    @Test void authoritativeMoveBeforeInitializationSupersedesTheStartGameCandidates() {
        var state = session("Unrecognized core", 5.621F);
        var move = new MovePlayerPacket();
        move.setRuntimeEntityId(7);
        move.setMode(MovePlayerPacket.Mode.TELEPORT);
        move.setPosition(Vector3f.from(8, 5.62001F, 8));
        move.setRotation(Vector3f.ZERO);
        state.observeBackend(move);
        state.player().getLatencyUtil().onOrderedResponse();
        state.observeClient(new SetLocalPlayerAsInitializedPacket());
        var input = AuthInputPacketsTest.input(40, 8, 4, 8, Vec3.ZERO);
        input.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        state.observeClient(input);
        assertEquals(4, state.player().position().y);
        assertFalse(state.teleports().isTeleporting());
        // No deferred candidate adjustment may run after the real teleport.
        state.observeClient(AuthInputPacketsTest.input(41, 8, 4, 8, Vec3.ZERO));
        assertEquals(4, state.player().position().y);
    }
}
