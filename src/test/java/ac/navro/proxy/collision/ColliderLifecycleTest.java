package ac.navro.proxy.collision;

import ac.navro.anticheat.collision.CollisionQuery;
import ac.navro.anticheat.collision.ProtocolCollisionSolver;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColliderLifecycleTest {
    private static SessionState session() { return session(2193); }
    private static SessionState session(int protocol) {
        SessionState state = new SessionState(); state.setProtocolVersion(protocol);
        var start = new StartGamePacket(); start.setRuntimeEntityId(7); start.setPlayerPosition(Vector3f.from(0.5F, 64, 0.5F));
        state.observeBackend(start);
        state.world().put(0, 0, CompensatedWorld.CachedChunk.network(-64, 320, 24, state.world().airNetworkId()));
        state.player().collisionPhysics.begin(state.world(), state.entities(), 1, null);
        return state;
    }
    private static int stone() {
        return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(state -> state.is("minecraft:stone")).findFirst().orElseThrow().networkId();
    }
    private static void metadata(SessionState s, EntityFlag flag, boolean value) {
        var packet = new SetEntityDataPacket(); packet.setRuntimeEntityId(7); packet.getMetadata().setFlag(flag, value);
        s.player().apply(packet);
        s.player().acknowledgePoseMetadata(s.player().latestPoseMetadataRevision());
    }
    private static void input(SessionState s, PlayerAuthInputData... actions) {
        var packet = new PlayerAuthInputPacket(); packet.setTick(1); packet.setPosition(Vector3f.from(0.5F, 65.62001F, 0.5F));
        packet.setRotation(Vector3f.ZERO); packet.setDelta(Vector3f.ZERO);
        packet.getInputData().addAll(List.of(actions)); s.player().apply(packet, s.world(), null, 1.62001F);
    }
    private static void ground(SessionState s) {
        s.player().setCollisionResult(false, false, true);
    }

    @Test void trialsPreserveOverlapResolutionAndSelectedMoveCommitsOnlyOnce() {
        SessionState s = session(); var p = s.player(); var world = s.collisionWorld();
        s.world().updateBlock(0, 63, 0, 0, stone());
        Box actor = new Box(0.2F, Math.nextDown(64F), 0.2F, 0.8F, 65.8F, 0.8F);
        p.setResolvedPosition(actor.feetCenter(), actor, false);
        p.collisionPhysics.acceptClientPosition();
        Vec3 fall = new Vec3(0, -0.0784F, 0);
        var before = p.collisionPhysics.snapshot();
        for (int i = 0; i < 4; i++) {
            assertTrue(Collider.collideMove(p, world, fall).movement.y > 0);
            assertEquals(0, p.collisionPenetratingFrames());
            assertNull(p.collisionPhysics.snapshot().movementQuery());
            assertEquals(before.resolveAcceptedOverlaps(), p.collisionPhysics.snapshot().resolveAcceptedOverlaps());
            assertTrue(p.movementCollisionEntries().isEmpty());
        }
        var move = Collider.collideAndUpdateMove(p, world, fall);
        assertTrue(move.movement.y > 0);
        assertEquals(1, p.collisionPenetratingFrames());
        assertFalse(p.collisionPhysics.snapshot().resolveAcceptedOverlaps());
        assertNotNull(p.collisionPhysics.snapshot().movementQuery());
        assertFalse(p.movementCollisionEntries().isEmpty());
        assertEquals("minecraft:stone", p.movementCollisionEntries().get(0).block().identifier());
        // Collider commits collision metadata; EntityTicker owns the later position update.
        assertTrue(actor.equals(p.boundingBox()));
        Collider.collideMove(p, world, fall);
        assertEquals(1, p.collisionPenetratingFrames());
        Collider.collideAndUpdateMove(p, world, fall);
        assertEquals(2, p.collisionPenetratingFrames());
        var saved = p.snapshot(null);
        p.setMovementCollisionEntries(List.of()); p.collisionPhysics.reset(); p.restore(saved, null);
        assertEquals(2, p.collisionPenetratingFrames());
        assertFalse(p.movementCollisionEntries().isEmpty());
    }

    @Test void autoStepTrialOverlapDoesNotTurnIntoOrdinaryPenetrationHistory() {
        SessionState s = session(); var p = s.player();
        s.world().updateBlock(0, 1, 0, 0, stone());
        Box actor = new Box(0.9F, 1, 0.2F, 1.5F, 2.8F, 0.8F);
        p.setResolvedPosition(actor.feetCenter(), actor, false); ground(s);
        Vec3 request = new Vec3(1, 0, 0);
        var reference = ProtocolCollisionSolver.solveResolvedDetailed(request, actor, List.of(new Box(0, 1, 0, 1, 2, 1)),
                new Vec3(1, 1, 1), 0.5625F, true);
        assertTrue(reference.result().overlapDepth > reference.ordinaryOverlapDepth());
        var result = Collider.collideAndUpdateMove(p, s.collisionWorld(), request);
        assertEquals(reference.result().overlapDepth, result.overlapDepth);
        assertEquals(reference.ordinaryOverlapDepth() >= Box.COLLISION_EPSILON ? 1 : 0, p.collisionPenetratingFrames());
        assertTrue(reference.result().aabb.equals(result.aabb));
    }

    @Test void acceptedPositionCannotOverrideRestrictedBudgetInTrialsReplayOrCommittedMovement() {
        SessionState s = session();
        var p = s.player();
        s.world().updateBlock(0, 63, 0, 0, stone());
        var actor = new Box(.25F, 63.875F, .25F, .75F, 65.675F, .75F);
        p.setResolvedPosition(actor.feetCenter(), actor, false);
        p.setCollisionPenetratingFrames(2);
        Collider.acceptInputPosition(p, s.collisionWorld(), actor.feetCenter());
        assertTrue(actor.equals(p.boundingBox()), "Position acceptance must not reconstruct this box");
        p.commitInputTick(2);
        p.collisionPhysics.begin(s.world(), s.entities(), 2, null);
        var saved = p.snapshot(null);
        var fall = new Vec3(0, -.0784F, 0);
        for (int trial = 0; trial < 3; trial++) {
            var move = Collider.collideMove(p, s.collisionWorld(), fall);
            assertEquals(fall.y, move.movement.y, "No forced separation beyond a zero native budget");
            assertEquals(actor.minY + fall.y, move.aabb.minY);
            assertEquals(2, p.collisionPenetratingFrames());
        }
        var replay = s.predictionReplay().solveCandidate(saved, fall, Vec3.ZERO, true);
        assertTrue(replay.available());
        assertEquals(fall.y, replay.result().movement.y);
        var committed = Collider.collideAndUpdateMove(p, s.collisionWorld(), fall);
        assertEquals(fall.y, committed.movement.y);
        assertEquals(actor.minY + fall.y, committed.aabb.minY);
        p.setResolvedMovement(committed.aabb, false);
        var escaping = Collider.collideMove(p, s.collisionWorld(), new Vec3(0, .5F, 0));
        assertEquals(.5F, escaping.movement.y, "Native budget restrictions still allow voluntary escape");
        assertTrue(escaping.aabb.minY > 64F);
    }

    @Test void restoredFloorStopsAlternatingPushAfterTwoPenetratingInputsIncludingReplay() {
        SessionState s = session(2225);
        var p = s.player();
        s.world().put(17, 16, CompensatedWorld.CachedChunk.network(-64, 320, 24, s.world().airNetworkId()));
        int grass = NetworkBlockStateRegistry.forProtocol(2225, false).states().values().stream()
                .filter(state -> state.is("minecraft:grass_block")).findFirst().orElseThrow().networkId();
        int dirt = NetworkBlockStateRegistry.forProtocol(2225, false).states().values().stream()
                .filter(state -> state.is("minecraft:dirt")).findFirst().orElseThrow().networkId();
        int log = NetworkBlockStateRegistry.forProtocol(2225, false).states().values().stream()
                .filter(state -> state.is("minecraft:oak_log")).findFirst().orElseThrow().networkId();
        s.world().updateBlock(276, 2, 265, 0, dirt);
        s.world().updateBlock(277, 2, 265, 0, dirt);
        s.world().updateBlock(277, 3, 265, 0, dirt);
        s.world().updateBlock(277, 4, 265, 0, log);
        s.world().updateBlock(277, 5, 265, 0, log);
        // Captured geometry: the client has fallen one block into the rejected dig.
        Box actor = new Box(276.4F, 3, 265.13882F, 277, 4.7999988F, 265.7388F);
        p.setResolvedPosition(actor.feetCenter(), actor, false);
        s.world().updateBlock(276, 3, 265, 0, grass);
        Vec3 fall = new Vec3(0, -.0784F, 0);
        for (int tick = 1; tick <= 2; tick++) {
            p.collisionPhysics.endInput();
            p.commitInputTick(tick);
            p.collisionPhysics.begin(s.world(), s.entities(), tick, null);
            var moved = Collider.collideAndUpdateMove(p, s.collisionWorld(), fall);
            assertEquals(tick == 1 ? .6F : -.6F, moved.movement.x, .0001F);
            p.setResolvedMovement(moved.aabb, false);
            Collider.acceptInputPosition(p, s.collisionWorld(), moved.aabb.feetCenter());
        }
        assertEquals(2, p.collisionPenetratingFrames());
        for (int tick = 3; tick <= 6; tick++) {
            p.collisionPhysics.endInput();
            p.commitInputTick(tick);
            p.collisionPhysics.begin(s.world(), s.entities(), tick, null);
            var saved = p.snapshot(null);
            for (int trial = 0; trial < 3; trial++) {
                var move = Collider.collideMove(p, s.collisionWorld(), fall);
                assertEquals(0F, move.movement.x, "Accepted positions must not bypass the native budget");
                assertEquals(0F, move.movement.y);
                var replay = s.predictionReplay().solveCandidate(saved, fall, Vec3.ZERO, true);
                assertTrue(replay.available());
                assertEquals(0F, replay.result().movement.x, "Replay uses the same native budget");
                assertEquals(2, p.collisionPenetratingFrames(), "Trials cannot advance penetration history");
            }
            var committed = Collider.collideAndUpdateMove(p, s.collisionWorld(), fall);
            assertEquals(0F, committed.movement.x);
            assertEquals(0F, committed.movement.y);
            assertEquals(2, p.collisionPenetratingFrames());
            p.setResolvedMovement(committed.aabb, false);
            Collider.acceptInputPosition(p, s.collisionWorld(), committed.aabb.feetCenter());
        }
    }

    @Test void blockPushConsumesBudgetWithoutReplacingCommittedMovementFetchOrContacts() {
        SessionState s = session(); var p = s.player(); var world = s.collisionWorld();
        s.world().updateBlock(1, 64, 0, 0, stone());
        Collider.collideAndUpdateMove(p, world, new Vec3(1, 0, 0));
        var committed = p.collisionPhysics.snapshot(); var entries = p.movementCollisionEntries();
        p.collisionPhysics.acceptClientPosition();
        Collider.collideBlockPush(p, world, new Vec3(0, 0.1F, 0));
        assertEquals(committed.movementQuery(), p.collisionPhysics.snapshot().movementQuery());
        assertSame(entries, p.movementCollisionEntries());
        assertFalse(p.collisionPhysics.snapshot().resolveAcceptedOverlaps());
    }

    @Test void oneWayContactsArePrunedOnlyByCommitAndClosestSpaceRequestNeedsActualPenetration() {
        SessionState s = session(); var p = s.player(); var world = s.collisionWorld();
        Box old = new Box(10, 64, 10, 11, 65, 11); p.collisionPhysics.addInsideOneWay(old);
        Collider.collideMove(p, world, Vec3.ZERO);
        assertEquals(1, p.collisionPhysics.snapshot().oneWayBlocks().size());
        Collider.collideAndUpdateMove(p, world, Vec3.ZERO);
        assertTrue(p.collisionPhysics.snapshot().oneWayBlocks().isEmpty());
        metadata(s, EntityFlag.PUSH_TOWARDS_CLOSEST_SPACE, true);
        s.world().updateBlock(0, 64, 0, 0, stone());
        p.setResolvedPosition(new Vec3(0.5F, 64.2F, 0.5F), new Box(0.2F, 64.2F, 0.2F, 0.8F, 66, 0.8F), false);
        p.collisionPhysics.endInput(); p.collisionPhysics.begin(s.world(), s.entities(), 2, null);
        Collider.collideMove(p, world, Vec3.ZERO);
        assertFalse(p.collisionPhysics.consumeClosestSpaceRequest());
        Collider.collideAndUpdateMove(p, world, Vec3.ZERO);
        assertTrue(p.collisionPhysics.consumeClosestSpaceRequest());
        assertTrue(p.collisionPhysics.snapshot().pushTowardsClosestSpace());
    }

    @Test void sneakControlUsesExplicitInputIncludingFalseAndAppliesVelocityClears() {
        SessionState s = session(); ground(s); metadata(s, EntityFlag.SNEAKING, true);
        Vec3 request = new Vec3(0.5F, -0.08F, 0.5F);
        input(s); // A present input with no SNEAK_DOWN overrides the metadata flag.
        assertEquals(0.5F, Collider.backOffFromEdge(s.player(), s.collisionWorld(), request).movement().x);
        input(s, PlayerAuthInputData.PERSIST_SNEAK);
        assertEquals(0.5F, Collider.backOffFromEdge(s.player(), s.collisionWorld(), request).movement().x);
        metadata(s, EntityFlag.SNEAKING, false);
        input(s, PlayerAuthInputData.SNEAK_DOWN);
        var edge = Collider.backOffFromEdge(s.player(), s.collisionWorld(), request);
        assertEquals(0, edge.movement().x); assertEquals(0, edge.movement().z); assertEquals(-0.08F, edge.movement().y);
        Vec3 velocity = edge.applyVelocity(new Vec3(2, 3, 4));
        assertEquals(0, velocity.x); assertEquals(3, velocity.y); assertEquals(0, velocity.z);
        assertTrue(Collider.canFallAtLeast(s.player(), s.collisionWorld(), 0, 0, 0.5F));
    }

    @Test void acceptedPositionUsesCachedCollidersToPreserveSupportAndRequestsNextInputResolution() {
        SessionState s = session(); var p = s.player();
        s.world().updateBlock(0, 63, 0, 0, stone());
        p.setResolvedPosition(new Vec3(0.5F, 64, 0.5F), new Box(0.2F, 64, 0.2F, 0.8F, 65.8F, 0.8F), false);
        Collider.acceptInputPosition(p, s.collisionWorld(), new Vec3(0.5F, Math.nextDown(64F), 0.5F));
        assertEquals(64, p.boundingBox().minY);
        assertTrue(p.collisionPhysics.resolveAcceptedOverlaps());
    }

    private static Vec3 vector(DataInputStream input) throws Exception { return new Vec3(input.readFloat(), input.readFloat(), input.readFloat()); }
    private static Box box(DataInputStream input) throws Exception {
        return new Box(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat());
    }
    @Test void detailedSolverMatchesEveryFloatBitOfOriginalNavroFixtures() throws Exception {
        try (var input = new DataInputStream(getClass().getResourceAsStream("/navro-collider-moves.bin"))) {
            assertEquals(0x4E434D31, input.readInt()); int count = input.readInt(); assertEquals(1200, count);
            for (int i = 0; i < count; i++) {
                Box actor = box(input); Vec3 movement = vector(input), budget = vector(input);
                float step = input.readFloat(); boolean canStep = input.readBoolean(); int shapesCount = input.readInt();
                List<Box> shapes = new ArrayList<>(); for (int j = 0; j < shapesCount; j++) shapes.add(box(input));
                var result = ProtocolCollisionSolver.solveResolvedDetailed(movement, actor, shapes, budget, step, canStep);
                var b = result.result().aabb; var m = result.result().movement;
                for (float value : new float[]{m.x, m.y, m.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                        result.result().overlapDepth, result.ordinaryOverlapDepth()})
                    assertEquals(input.readInt(), Float.floatToRawIntBits(value), "case=" + i);
            }
            assertEquals(-1, input.read());
        }
    }
}
