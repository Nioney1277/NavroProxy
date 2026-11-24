package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FluidLifecycleTest {
    private static final EntityDimensions STANDING = EntityDimensions.fixed(0.58F, 1.8F).withEyeHeight(1.62F);

    private static void prepare(ProtocolFluidStateTracker state, MovementAabbSnapshot start,
                                Vec3 feet, boolean swimmingBefore, boolean swimmingNow,
                                boolean crawlingBefore, PlayerAuthInputData... actions) {
        state.beginInput(new CompensatedWorld(), start, STANDING.getBoxAt(feet), STANDING, feet,
                STANDING, false, false, false, swimmingBefore, swimmingNow, crawlingBefore,
                actions, new Vec3(0, 0, 1));
    }

    @Test void stopSwimmingKeepsTheInputStartFlagAndAdvancesBlendUsingThatFlag() {
        ProtocolFluidStateTracker state = new ProtocolFluidStateTracker();
        Vec3 feet = new Vec3(0, 64, 0);
        MovementAabbSnapshot start = new MovementAabbSnapshot(STANDING, feet, STANDING.getBoxAt(feet), 7);
        prepare(state, start, feet, true, false, false, PlayerAuthInputData.STOP_SWIMMING);
        assertTrue(state.wasSwimming());
        assertFalse(state.swimming());
        assertEquals(0.1F, state.swimAmount());
        prepare(state, start, feet, false, false, false);
        assertEquals(0.0F, state.swimAmount());
    }

    @Test void historicalSensingMovesActualBoundsAndPreservesResizeRevision() {
        ProtocolFluidStateTracker state = new ProtocolFluidStateTracker();
        Vec3 feet = new Vec3(256.31F, 75, 82.29F);
        Box actual = new Box(256.02002F, 75, 82, 256.60004F, 76.8F, 82.58F);
        MovementAabbSnapshot start = new MovementAabbSnapshot(STANDING, feet, actual, 41);
        Vec3 replayFeet = feet.add(0.25F, 0, -1);
        prepare(state, start, replayFeet, false, false, true);
        ProtocolFluidStateTracker.Frame saved = state.snapshot();
        assertTrue(actual.offset(replayFeet.subtract(feet)).equals(saved.inputBox()));
        assertEquals(41, saved.inputAabb().resizeRevision());
        assertTrue(saved.inputAabb().box().equals(actual));
        state.resetMovement();
        state.restore(saved);
        assertEquals(0.1F, state.swimAmount());
        assertTrue(state.wasCrawling());
        assertTrue(state.snapshot().inputBox().equals(saved.inputBox()));
        saved.inputAabb().position().x = -1;
        saved.liquids().selectedPush().x = -1;
        assertEquals(feet.x, state.snapshot().inputAabb().position().x);
        assertEquals(0, state.snapshot().liquids().selectedPush().x);
    }

    @Test void crawlingRequestRetainsStandingTravelBoxAndSurvivesTeleportMovementReset() {
        WirePlayerState player = new WirePlayerState();
        StartGamePacket start = new StartGamePacket();
        start.setRuntimeEntityId(1);
        start.setPlayerPosition(Vector3f.from(0, 64, 0));
        player.apply(start);
        ProtocolFluidStateTracker fluid = new ProtocolFluidStateTracker();
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        input.setPosition(Vector3f.from(0, 65.62001F, 0));
        input.setRotation(Vector3f.ZERO);
        input.setTick(0);
        input.getInputData().add(PlayerAuthInputData.START_CRAWLING);
        Box before = player.boundingBox();
        player.apply(input, new CompensatedWorld(), fluid);
        var frame = player.snapshot(fluid);
        assertTrue(before.equals(player.boundingBox()));
        assertEquals(1.8F, player.dimensions().height());
        assertEquals(0.6F, frame.pendingPoseDimensions().height());
        player.finishInput();
        player.acceptTeleport(new Vec3(1, 64, 0), false);
        assertEquals(0.6F, player.snapshot(fluid).pendingPoseDimensions().height());
        player.restoreInputPose(frame);
        assertNull(player.snapshot(fluid).pendingPoseDimensions());
        assertEquals(frame.inputHorizontalPoseApplied(), player.horizontalPoseApplied());
    }
}
