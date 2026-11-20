package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerMovementLifecycleTest {
    private static MovePlayerPacket move(long entity, MovePlayerPacket.Mode mode) {
        var packet = new MovePlayerPacket();
        packet.setRuntimeEntityId(entity);
        packet.setMode(mode);
        packet.setPosition(Vector3f.from(10, 71.62001F, 12));
        packet.setRotation(Vector3f.ZERO);
        packet.setOnGround(true);
        packet.setTick(500);
        return packet;
    }

    @Test void normalMoveUsesThreeInterpolationStepsInLoadedTerrain() {
        var state = AuthInputPacketsTest.session();
        state.setDetectionEnabled(false);
        try (var fixture = new TeleportSenderTest.Fixture(state)) {
            var packet = move(7, MovePlayerPacket.Mode.NORMAL);
            packet.setPosition(Vector3f.from(11, 65.62001F, 8));
            backend(fixture, packet);
            fixture.ack(fixture.flush());
            for (int tick = 1; tick <= 3; tick++) {
                fixture.input(tick, packet.getPosition(), false);
                assertEquals(8 + tick, state.player().position().x);
                assertEquals(tick < 3, state.teleports().isTeleporting());
            }
        }
    }

    private static void backend(TeleportSenderTest.Fixture f, BedrockPacket packet) {
        PacketListener.onPacketSent(f.state.player(), packet);
        f.state.observeBackend(packet);
        f.client.sendPacket(packet);
        f.state.onBackendPacketQueued(packet);
    }

    private static void vector(float x, float y, float z, Vec3 actual) {
        assertEquals(x, actual.x);
        assertEquals(y, actual.y);
        assertEquals(z, actual.z);
    }

    @Test void localMovesPreserveTheirModeAndUseClosingAck() {
        for (var mode : new MovePlayerPacket.Mode[]{MovePlayerPacket.Mode.NORMAL,
                MovePlayerPacket.Mode.RESPAWN, MovePlayerPacket.Mode.TELEPORT}) {
            try (var f = new TeleportSenderTest.Fixture()) {
                var player = f.state.player();
                player.setVelocity(new Vec3(.2F, .3F, .4F));
                var packet = move(7, mode);
                backend(f, packet);
                vector(8, 64, 8, player.position());
                vector(.2F, .3F, .4F, player.velocity());
                assertFalse(player.onGround(), "Outbound " + mode + " must not change prediction ground");
                var queued = f.state.teleports().getQueuedTeleports().peek();
                assertNotNull(queued);
                assertFalse(queued.isAccepted());
                var wire = f.flush();
                assertEquals(2, wire.size());
                var encoded = assertInstanceOf(MovePlayerPacket.class, wire.get(0));
                assertEquals(mode, encoded.getMode());
                assertEquals(packet.getPosition(), encoded.getPosition());
                assertTrue(encoded.isOnGround());


                f.input(1, packet.getPosition(), true);
                assertSame(queued, f.state.teleports().getQueuedTeleports().peek());
                vector(8, 64, 8, player.position());
                assertFalse(player.onGround());
                f.ack(wire);
                assertTrue(queued.isAccepted());
                vector(8, 64, 8, player.position());
                vector(.2F, .3F, .4F, player.velocity());
                assertFalse(player.onGround());

                f.input(2, packet.getPosition().add(.125F, 0, 0), false);
                assertFalse(f.state.teleports().isTeleporting());
                vector(10, 70, 12, player.position());
                if (mode == MovePlayerPacket.Mode.NORMAL) vector(2, 6, 4, player.velocity());
                else vector(0, 0, 0, player.velocity());
                assertTrue(player.onGround());
                vector(10, 71.62001F, 12, f.state.teleports().getLastKnowValid());
            }
        }
    }

    @Test void headRotationAndRemoteMovesCannotChangeLocalMovementOrTeleportState() {
        try (var f = new TeleportSenderTest.Fixture()) {
            var player = f.state.player();
            player.setVelocity(new Vec3(.2F, .3F, .4F));
            assertNull(player.serverPosition());
            for (var packet : new MovePlayerPacket[]{move(7, MovePlayerPacket.Mode.HEAD_ROTATION),
                    move(42, MovePlayerPacket.Mode.NORMAL), move(42, MovePlayerPacket.Mode.RESPAWN)}) {
                backend(f, packet);
                f.ack(f.flush());
                assertFalse(f.state.teleports().isTeleporting());
                vector(8, 64, 8, player.position());
                vector(.2F, .3F, .4F, player.velocity());
                assertNull(player.serverPosition());
                assertFalse(player.onGround());
                assertNull(f.state.serverPosition());
            }
        }
    }

    @Test void respawnStatesWaitForTheirAckAndPreserveThePendingResetUntilInputConfirmation() {
        try (var f = new TeleportSenderTest.Fixture()) {
            var player = f.state.player();
            player.setVelocity(new Vec3(.2F, .3F, .4F));
            var remote = new AddPlayerPacket();
            remote.setRuntimeEntityId(42);
            remote.setUniqueEntityId(420);
            remote.setPosition(Vector3f.ZERO);
            remote.setMotion(Vector3f.ZERO);
            f.state.observeBackend(remote);
            var entity = f.state.entity(42);
            var reset = move(7, MovePlayerPacket.Mode.RESPAWN);
            backend(f, reset);
            var queued = f.state.teleports().getQueuedTeleports().peek();
            assertNotNull(queued);

            var searching = new RespawnPacket();
            searching.setRuntimeEntityId(7);
            searching.setState(RespawnPacket.State.SERVER_SEARCHING);
            searching.setPosition(Vector3f.from(90, 100, 110));
            backend(f, searching);
            assertFalse(player.acknowledgedDead);
            assertSame(queued, f.state.teleports().getQueuedTeleports().peek());
            assertFalse(queued.isAccepted());
            vector(8, 64, 8, player.position());
            vector(.2F, .3F, .4F, player.velocity());
            f.ack(f.flush());
            assertTrue(player.acknowledgedDead);
            assertTrue(queued.isAccepted());

            var ready = new RespawnPacket();
            ready.setRuntimeEntityId(7);
            ready.setState(RespawnPacket.State.SERVER_READY);
            ready.setPosition(Vector3f.from(120, 130, 140));
            backend(f, ready);
            assertTrue(player.acknowledgedDead);
            f.ack(f.flush());
            assertFalse(player.acknowledgedDead);
            assertSame(queued, f.state.teleports().getQueuedTeleports().peek());
            assertSame(entity, f.state.entity(42));
            assertEquals(42L, f.state.entityRuntimeIdByUniqueId(420));
            vector(8, 64, 8, player.position());
            vector(.2F, .3F, .4F, player.velocity());

            f.input(1, reset.getPosition(), false);
            assertFalse(f.state.teleports().isTeleporting());
            vector(10, 70, 12, player.position());
            vector(0, 0, 0, player.velocity());
            assertTrue(player.onGround());
        }
    }
}
