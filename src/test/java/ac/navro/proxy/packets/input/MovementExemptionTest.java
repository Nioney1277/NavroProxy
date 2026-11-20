package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.permission.ProxyPermissions;
import ac.navro.proxy.prediction.engine.data.Vector;
import ac.navro.proxy.protocol.BackendType;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static ac.navro.proxy.packets.input.AuthInputPacketsTest.input;
import static ac.navro.proxy.packets.input.AuthInputPacketsTest.session;
import static org.junit.jupiter.api.Assertions.*;

class MovementExemptionTest {
    @Test void groundedMayFlyStillTravelsOnTheFirstInputAfterLoadingWithZeroDelta() {
        var state = session();
        var wire = state.player();
        wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        wire.sinceLoadingScreen = 0;
        wire.setVelocity(new Vec3(0, -0.2F, 0));
        long resize = wire.aabbResizeRevision();

        state.observeClient(input(10, 8, 64, 8, Vec3.ZERO));

        var context = state.authInputPackets().context();
        assertTrue(wire.onGround());
        assertEquals(-0.2F, context.predictionResult.before().y);
        assertEquals(0F, context.predictionResult.after().y);
        assertEquals(0F, context.predictionResult.tickEnd().y);
        assertSame(Vector.NONE, context.bestPossibility);
        assertEquals(resize, wire.aabbResizeRevision());
        assertEquals(wire.position().toVector3f(), wire.previousPosition().toVector3f());
        assertTrue(state.teleports().getAuthInputHistory().containsKey(10L));
        assertEquals(10, state.authInputPackets().lastResult().tick());
    }

    @Test void flightStartAndStopBothResetTravelUntilTheOriginalAbilityTransitionClearsWasFlying() {
        var state = session();
        var wire = state.player();
        wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        wire.setVelocity(new Vec3(0, -0.2F, 0));
        wire.setCollisionResult(true, false, true);
        var start = input(10, 8, 66, 8, new Vec3(0, 0.3F, 0));
        start.getInputData().addAll(Set.of(PlayerAuthInputData.START_FLYING, PlayerAuthInputData.VERTICAL_COLLISION));
        var context = state.authInputPackets().context();
        context.fallDistance = 4;
        context.fluidPrediction.waterDrag = 0.9F;
        wire.setCollisionPenetratingFrames(3);

        state.observeClient(start);

        assertTrue(wire.flying());
        assertEquals(0F, context.fallDistance);
        assertEquals(0.8F, context.fluidPrediction.waterDrag);
        assertEquals(0, wire.snapshot(state.fluidState()).collisionPenetratingFrames());
        assertEquals(Vector3f.from(0, 1, 0), context.predictionResult.after().toVector3f());
        assertEquals(0.3F, wire.velocity().y);
        assertTrue(wire.horizontalCollision());
        assertFalse(wire.verticalCollision());
        assertTrue(start.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION));
        assertFalse(start.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION));

        long resize = wire.aabbResizeRevision();
        var stop = input(11, 8, 65, 8, Vec3.ZERO);
        stop.getInputData().add(PlayerAuthInputData.STOP_FLYING);
        state.observeClient(stop);
        assertFalse(wire.flying());
        assertTrue(wire.wasFlying());
        assertTrue(wire.aabbResizeRevision() > resize);
        wire.acknowledgeAbilities(Set.of());
        assertFalse(wire.isMovementExempted());
    }

    @Test void exemptWalkingTranslatesTheBoxUsingTheTrackedMountNotTheInputDeclaration() {
        var state = session();
        var wire = state.player();
        wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        long resize = wire.aabbResizeRevision();
        var packet = input(10, 9, 64, 8, Vec3.ZERO);
        packet.setPredictedVehicle(99);

        state.observeClient(packet);

        assertEquals(99, wire.predictedVehicle());
        assertNull(wire.vehicleData);
        assertEquals(9F, wire.position().x);
        assertEquals(9F, wire.boundingBox().feetCenter().x);
        assertEquals(9F, state.teleports().getLastKnowValid().x);
        assertEquals(resize, wire.aabbResizeRevision());
    }

    @Test void loadingNoClipAndSpectatorUseTheResetBranchWhenMayFlyIsGranted() {
        for (int condition = 0; condition < 3; condition++) {
            var state = session();
            var wire = state.player();
            wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
            switch (condition) {
                case 0 -> wire.inLoadingScreen = true;
                case 1 -> wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY, Ability.NO_CLIP));
                case 2 -> wire.acknowledgeGameType(GameType.SPECTATOR);
            }
            long resize = wire.aabbResizeRevision();
            var context = state.authInputPackets().context();
            context.fallDistance = 4;
            state.observeClient(input(10, 9, 66, 8, Vec3.ZERO));
            assertEquals(0F, context.fallDistance);
            assertTrue(wire.aabbResizeRevision() > resize);
            assertEquals(66F, wire.boundingBox().minY);
        }
    }

    @Test void acknowledgedBdsAbilitiesTargetTheLocalUniqueIdNotTheRuntimeId() {
        var state = session(BackendType.BDS);
        var wire = state.player();
        sendAbilities(state, 71, wire.runtimeId());
        wire.getLatencyUtil().onResponse(71);
        assertFalse(wire.isMovementExempted());

        sendAbilities(state, 72, wire.uniqueId());
        assertFalse(wire.isMovementExempted());
        wire.getLatencyUtil().onResponse(72);
        assertTrue(wire.isMovementExempted());
        assertFalse(wire.flying());
    }

    @Test void exemptTravelRequestsTheLastLoadedPositionBeforeCachingAnUnloadedInput() {
        var state = session();
        var moves = AuthInputPacketsTest.transport(state);
        state.player().acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        var anchor = state.teleports().getLastKnowValid().toVector3f();

        state.observeClient(input(10, 100, 64, 8, Vec3.ZERO));

        assertEquals(1, moves.size());
        assertEquals(anchor, moves.getFirst().getPosition());
        assertEquals(anchor, state.teleports().getLastKnowValid().toVector3f());
    }

    @Test void acknowledgedSoftCorrectionUsesTheExemptInputInsteadOfTheHistoricalTarget() {
        var state = session();
        var wire = state.player();
        wire.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
        var correction = new CorrectPlayerMovePredictionPacket();
        correction.setTick(9);
        correction.setPosition(Vector3f.from(10, 65.62001F, 8));
        correction.setDelta(Vector3f.ZERO);
        correction.setPredictionType(PredictionType.PLAYER);
        correction.setVehicleRotation(Vector2f.ZERO);
        wire.getLatencyUtil().queue(91, true);
        state.observeBackend(correction);
        wire.getLatencyUtil().onResponse(91);

        state.observeClient(input(10, 9, 64, 8, Vec3.ZERO));

        assertFalse(state.teleports().isTeleporting());
        assertEquals(9F, wire.position().x);
        assertEquals(9F, state.teleports().getLastKnowValid().x);
        assertEquals(10, state.authInputPackets().lastResult().tick());
    }

    @Test void confirmedHardTeleportDoesNotRunExemptTravelOnTheSameInput() {
        var state = session();
        var wire = state.player();
        wire.acknowledgeAbilities(Set.of(Ability.FLYING));
        var teleport = new MovePlayerPacket();
        teleport.setRuntimeEntityId(wire.runtimeId());
        teleport.setMode(MovePlayerPacket.Mode.TELEPORT);
        teleport.setPosition(Vector3f.from(9, 65.62001F, 8));
        teleport.setRotation(Vector3f.ZERO);
        wire.getLatencyUtil().queue(91, true);
        state.observeBackend(teleport);
        wire.getLatencyUtil().onResponse(91);
        var confirmation = input(10, 9, 64, 8, new Vec3(0, 0.3F, 0));
        confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);

        state.observeClient(confirmation);

        assertFalse(state.teleports().isTeleporting());
        assertEquals(9F, wire.position().x);
        assertEquals(0F, wire.velocity().y);
        assertEquals(0.3F, confirmation.getDelta().getY());
        assertEquals(10, state.authInputPackets().lastResult().tick());
    }

    @Test void explicitPermissionLeavesInputUntouchedButNotStandaloneInventory(@TempDir Path directory) throws Exception {
        var state = session();
        var wire = state.player();
        Path file = directory.resolve("permissions.json");
        var permissions = new ProxyPermissions(file, ignored -> fail(ignored));
        UUID identity = UUID.randomUUID();
        state.setExemptionPermission(() -> permissions.hasPermission(identity, "navro.exempt", true));
        assertFalse(permissions.hasPermission(identity, "navro.exempt", true));
        Files.writeString(file, "{\"players\":{\"" + identity + "\":{\"navro.exempt\":true}}}");
        var packet = input(10, 12, 68, 8, new Vec3(0, 0.3F, 0));
        packet.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
        var position = packet.getPosition();
        var delta = packet.getDelta();
        long tick = wire.tick();

        assertTrue(state.receiveClient(packet, 8500));
        assertTrue(wire.isExempted());
        assertEquals(tick, wire.tick());
        assertEquals(8F, wire.position().x);
        assertEquals(position, packet.getPosition());
        assertEquals(delta, packet.getDelta());
        assertEquals(Set.of(PlayerAuthInputData.VERTICAL_COLLISION), packet.getInputData());
        assertTrue(state.teleports().getAuthInputHistory().isEmpty());

        assertFalse(state.receiveClient(new InventoryTransactionPacket(), 8500));
        assertEquals("inventoryPredictionRejected", state.anomalies().getLast().kind());

        Files.writeString(file, "{\"players\":{\"" + identity + "\":{\"navro.exempt\":false}}}");
        state.observeClient(input(11, 8, 64, 8, new Vec3(0, -0.0784F, 0)));
        assertFalse(wire.isExempted());
        assertEquals(11, wire.tick());
    }

    private static void sendAbilities(SessionState state, long marker, long entityId) {
        var packet = new UpdateAbilitiesPacket();
        packet.setUniqueEntityId(entityId);
        var layer = new AbilityLayer();
        layer.setLayerType(AbilityLayer.Type.BASE);
        layer.getAbilityValues().add(Ability.MAY_FLY);
        packet.getAbilityLayers().add(layer);
        state.player().getLatencyUtil().queue(marker, true);
        state.observeBackend(packet);
    }
}
