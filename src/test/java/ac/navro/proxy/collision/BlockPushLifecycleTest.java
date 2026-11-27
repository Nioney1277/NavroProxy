package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BlockPushLifecycleTest {
    private static SessionState session() {
        var s = new SessionState(); s.setProtocolVersion(2193);
        var start = new StartGamePacket(); start.setRuntimeEntityId(7); start.setUniqueEntityId(70);
        start.setPlayerPosition(Vector3f.from(0.5F, 64, 0.5F)); s.observeBackend(start);
        s.world().put(0, 0, CompensatedWorld.CachedChunk.network(-64, 320, 24, s.world().airNetworkId()));
        return s;
    }
    private static int block(String name) {
        return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(state -> state.is(name)).findFirst().orElseThrow().networkId();
    }
    private static void flag(SessionState s, EntityFlag flag, boolean enabled) {
        var data = new SetEntityDataPacket(); data.setRuntimeEntityId(7); data.getMetadata().setFlag(flag, enabled);
        s.player().apply(data);
        s.player().acknowledgePoseMetadata(s.player().latestPoseMetadataRevision());
    }
    private static void actor(SessionState s, Box box) {
        s.player().setResolvedPosition(box.feetCenter(), box, false);
    }
    private static void shulker(SessionState s, int x, int y, int z, int facing) {
        s.world().updateBlock(x, y, z, 0, block("minecraft:undyed_shulker_box"));
        s.world().updateBlockEntityTag(x, y, z, NbtMap.builder().putByte("facing", (byte) facing).build());
        s.world().handleCollisionBlockEvent(x, y, z, 1, 1);
    }
    private static void next(SessionState s, long tick) {
        s.world().tickCollisionBlockActors();
        s.player().commitInputTick(tick);
        s.player().collisionPhysics.begin(s.world(), s.entities(), tick, null);
    }
    private static SessionState eastPush() {
        var s = session(); shulker(s, 0, 64, 0, 5); next(s, 1); next(s, 2);
        actor(s, new Box(1.04F, 64.1F, 0.2F, 1.64F, 65.9F, 0.8F));
        s.player().setCollisionResult(false, false, true);
        return s;
    }
    private static void vectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x); assertEquals(expected.y, actual.y); assertEquals(expected.z, actual.z);
    }

    @Test void closestRequestIsConsumedBeforeCollisionFlagAndMountGatesWithoutFetching() {
        var s = session(); var p = s.player(); var calls = new AtomicInteger();
        var fetch = (java.util.function.Supplier<List<Box>>) () -> { calls.incrementAndGet(); return List.of(p.boundingBox()); };
        MoveTowardsClosestSpace.prepare(p, false, fetch);
        assertEquals(0, calls.get());
        p.collisionPhysics.requestClosestSpace(true, true);
        MoveTowardsClosestSpace.prepare(p, false, fetch);
        assertEquals(0, calls.get()); assertFalse(p.collisionPhysics.consumeClosestSpaceRequest());
        flag(s, EntityFlag.HAS_COLLISION, true);
        p.collisionPhysics.requestClosestSpace(true, true);
        MoveTowardsClosestSpace.prepare(p, true, fetch);
        assertEquals(0, calls.get()); assertFalse(p.collisionPhysics.consumeClosestSpaceRequest());
        p.collisionPhysics.requestClosestSpace(true, true);
        MoveTowardsClosestSpace.prepare(p, false, fetch);
        assertEquals(1, calls.get()); assertTrue(p.velocity().x > 0); assertTrue(p.velocity().z > 0);
        MoveTowardsClosestSpace.prepare(p, false, fetch);
        assertEquals(1, calls.get());
    }

    @Test void closestSpaceUsesBlockOnlyExpandedQueryAndReplaysStartRequestAndOwnedCache() {
        var s = session(); var p = s.player(); flag(s, EntityFlag.HAS_COLLISION, true);
        s.world().updateBlock(0, 64, 0, 0, block("minecraft:stone"));
        actor(s, new Box(0.2F, 64.2F, 0.2F, 0.8F, 66, 0.8F));
        p.setVelocity(new Vec3(0, -0.08F, 0));
        p.collisionPhysics.requestClosestSpace(true, true); next(s, 1);
        var start = p.snapshot(null);
        MoveTowardsClosestSpace.prepare(p, s.collisionWorld(), false);
        Vec3 pushed = p.velocity().clone(); assertEquals(-0.08F, pushed.y);
        assertTrue(pushed.x > 0); assertTrue(pushed.z > 0);
        var completed = p.snapshot(null);
        assertEquals(1, completed.collisionState().blockQueries().size());
        var query = completed.collisionState().blockQueries().keySet().iterator().next();
        assertEquals(-0.8F, query.minX()); assertEquals(1.8F, query.maxX());
        assertEquals(64.2F, query.minY()); assertEquals(66, query.maxY());
        s.world().updateBlock(0, 64, 0, 0, s.world().airNetworkId());
        p.restore(start, null);
        p.collisionPhysics.begin(s.world(), s.entities(), 1, completed.collisionState());
        MoveTowardsClosestSpace.prepare(p, s.collisionWorld(), false);
        vectorEquals(pushed, p.velocity());
        assertFalse(p.collisionPhysics.consumeClosestSpaceRequest());
        next(s, 2); p.setVelocity(new Vec3(0, -0.08F, 0));
        MoveTowardsClosestSpace.prepare(p, s.collisionWorld(), false);
        assertEquals(0, p.velocity().x); assertEquals(0, p.velocity().z);
    }

    @Test void shulkerPushCommitsNativeBoundsDisplacementAndFlagsWithoutChangingVelocityOrMovementFetch() throws Exception {
        var s = eastPush(); var p = s.player();
        p.setVelocity(new Vec3(0.03F, -0.04F, 0.05F));
        p.setAfterCollision(new Vec3(0.2F, 0.1F, 0));
        Collider.collideAndUpdateMove(p, s.collisionWorld(), Vec3.ZERO);
        var committed = p.collisionPhysics.snapshot(); var entries = p.movementCollisionEntries();
        var before = p.position().clone(); long resize = p.aabbResizeRevision();
        p.collisionPhysics.acceptClientPosition();
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        Vec3 applied = p.position().subtract(before);
        assertTrue(applied.x > 0); assertEquals(0, applied.y); assertEquals(0, applied.z);
        var reference = BlockPushReferenceTest.suffixCases().get(5);
        assertTrue(reference.resultBox().equals(p.boundingBox()));
        vectorEquals(new Vec3(0.2F, 0.1F, 0).add(applied), p.afterCollision());
        vectorEquals(applied, p.collisionPhysics.snapshot().blockPush());
        vectorEquals(new Vec3(0.03F, -0.04F, 0.05F), p.velocity());
        assertTrue(p.collisionPhysics.pushedByBlock());
        assertEquals(reference.onGround(), p.onGround());
        assertEquals(reference.horizontal(), p.horizontalCollision());
        assertEquals(reference.vertical(), p.verticalCollision());
        assertSame(entries, p.movementCollisionEntries());
        assertSame(committed.movementQuery(), p.collisionPhysics.snapshot().movementQuery());
        assertEquals(resize, p.aabbResizeRevision());
        assertFalse(p.collisionPhysics.snapshot().resolveAcceptedOverlaps());
    }

    @Test void firstCachedShapeAndClosingZeroGateAreKeptForTheSuffix() {
        var s = session(); shulker(s, 0, 64, 0, 5); next(s, 1);
        actor(s, new Box(1.04F, 64.1F, 0.2F, 1.64F, 65.9F, 0.8F)); var p = s.player();
        Vec3 before = p.position().clone();
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        vectorEquals(before, p.position());
        next(s, 2);
        var expanded = p.collisionPhysics.shulkers().get(0).shape(); assertTrue(expanded.maxX > 1.04F);
        s.world().handleCollisionBlockEvent(0, 64, 0, 1, 0); next(s, 3); next(s, 4);
        assertEquals(0, p.collisionPhysics.shulkers().get(0).openness());
        assertTrue(p.collisionPhysics.shulkers().get(0).shape().intersects(p.boundingBox()));
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        vectorEquals(before, p.position()); assertFalse(p.collisionPhysics.pushedByBlock());
    }

    @Test void shulkerMountAndEveryMovementExemptionLeaveSuffixStateUntouched() {
        for (int gate = 0; gate < 5; gate++) {
            var s = eastPush(); var p = s.player();
            switch (gate) {
                case 0 -> p.setExempted(true);
                case 1 -> p.acknowledgeAbilities(Set.of(Ability.MAY_FLY));
                case 2 -> p.setFlying(true);
                case 3 -> { p.setFlying(true); p.setFlying(false); }
                default -> { }
            }
            var before = p.snapshot(null);
            ShulkerPushPhysics.finish(p, s.collisionWorld(), gate == 4);
            vectorEquals(before.position(), p.position()); vectorEquals(before.afterCollision(), p.afterCollision());
            assertTrue(p.collisionPhysics.snapshot().blockQueries().isEmpty());
            assertFalse(p.collisionPhysics.pushedByBlock());
        }
    }

    @Test void shulkerSuffixCanReplayOwnedFramesAfterLiveActorRemoval() {
        var s = eastPush(); var p = s.player(); p.setAfterCollision(new Vec3(0.2F, 0, 0));
        var start = p.snapshot(null);
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        var completed = p.snapshot(null);
        s.world().updateBlock(0, 64, 0, 0, s.world().airNetworkId());
        next(s, 3); assertTrue(s.world().snapshotShulkerFrames().isEmpty());
        p.restore(start, null); p.collisionPhysics.begin(s.world(), s.entities(), 2, completed.collisionState());
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        vectorEquals(completed.position(), p.position()); vectorEquals(completed.afterCollision(), p.afterCollision());
        vectorEquals(completed.collisionState().blockPush(), p.collisionPhysics.snapshot().blockPush());
        assertTrue(completed.boundingBox().equals(p.boundingBox()));
        assertTrue(s.world().snapshotShulkerFrames().isEmpty());
    }

    @Test void afterCollisionSnapshotsAndTeleportResetOwnTheirVectors() {
        var s = eastPush(); var p = s.player(); var supplied = new Vec3(1, 2, 3);
        p.setAfterCollision(supplied); supplied.x = 99;
        var saved = p.snapshot(null); saved.afterCollision().y = 99;
        p.setAfterCollision(Vec3.ZERO); p.restore(saved, null);
        vectorEquals(new Vec3(1, 2, 3), p.afterCollision());
        p.afterCollision().z = 99; vectorEquals(new Vec3(1, 2, 3), p.afterCollision());
        p.acceptTeleport(new Vec3(4, 64, 4), false);
        vectorEquals(Vec3.ZERO, p.afterCollision());
    }

    @Test void allSixSuffixDirectionsMatchOriginalSolverBoundsFlagsAndAppliedDisplacement() throws Exception {
        for (var reference : BlockPushReferenceTest.suffixCases()) {
            var s = session(); shulker(s, 0, 64, 0, reference.facing()); next(s, 1); next(s, 2);
            var p = s.player(); actor(s, reference.actor()); p.setCollisionResult(false, false, true);
            var previous = p.previousPosition().clone();
            ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
            assertTrue(reference.resultBox().equals(p.boundingBox()), "facing=" + reference.facing());
            vectorEquals(reference.resultBox().feetCenter(), p.position());
            Vec3 applied = reference.resultBox().feetCenter().subtract(reference.actor().feetCenter());
            vectorEquals(applied, p.afterCollision()); vectorEquals(applied, p.collisionPhysics.snapshot().blockPush());
            assertEquals(reference.horizontal(), p.horizontalCollision());
            assertEquals(reference.vertical(), p.verticalCollision()); assertEquals(reference.onGround(), p.onGround());
            vectorEquals(previous, p.previousPosition());
            assertEquals(reference.ordinaryOverlap() >= Box.COLLISION_EPSILON ? 1 : 0, p.collisionPenetratingFrames());
        }
    }

    @Test void upwardObstructionStopsPushWithoutConsumingBudgetOrUpdatingSuffix() {
        var s = session(); shulker(s, 0, 64, 0, 1); next(s, 1); next(s, 2);
        actor(s, new Box(0.2F, 65.04F, 0.2F, 0.8F, 66.84F, 0.8F));
        s.world().updateBlock(0, 66, 0, 0, block("minecraft:stone"));
        var p = s.player(); p.collisionPhysics.acceptClientPosition();
        var before = p.snapshot(null);
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        vectorEquals(before.position(), p.position()); vectorEquals(before.afterCollision(), p.afterCollision());
        assertEquals(before.collisionState().resolveAcceptedOverlaps(), p.collisionPhysics.snapshot().resolveAcceptedOverlaps());
        assertFalse(p.collisionPhysics.pushedByBlock());
    }

    @Test void compensatedMountGateAlsoOwnsPositionCommitWhenWireDeclarationDisagrees() {
        var s = eastPush(); var p = s.player();
        var input = new org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket();
        input.setTick(2); input.setPosition(Vector3f.from(1.34F, 65.72001F, 0.5F));
        input.setRotation(Vector3f.ZERO); input.setDelta(Vector3f.ZERO); input.setPredictedVehicle(99);
        p.apply(input, null, null);
        var before = p.boundingBox().clone();
        ShulkerPushPhysics.finish(p, s.collisionWorld(), false);
        assertTrue(p.boundingBox().minX > before.minX);
        vectorEquals(p.boundingBox().feetCenter(), p.position());
        assertEquals(99, p.predictedVehicle());
    }
}
