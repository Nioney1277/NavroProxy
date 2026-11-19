package ac.navro.proxy.collision;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CollisionHistoryLifecycleTest {
    private static final Box QUERY = new Box(0.1F, 64.1F, 0.1F, 0.9F, 64.9F, 0.9F);
    private static SessionState session() {
        SessionState state = new SessionState(); state.setProtocolVersion(2193);
        StartGamePacket start = new StartGamePacket(); start.setRuntimeEntityId(7);
        start.setPlayerPosition(Vector3f.from(0.5F, 64, 0.5F)); state.observeBackend(start);
        var initialized = new SetLocalPlayerAsInitializedPacket(); initialized.setRuntimeEntityId(7);
        state.observeClient(initialized);
        state.world().put(0, 0, CompensatedWorld.CachedChunk.network(-64, 320, 24, state.world().airNetworkId()));
        return state;
    }
    private static void input(SessionState state, long tick) {
        PlayerAuthInputPacket input = new PlayerAuthInputPacket(); input.setTick(tick);
        input.setPosition(Vector3f.from(0.5F, 65.62001F, 0.5F));
        input.setRotation(Vector3f.ZERO); input.setDelta(Vector3f.ZERO); state.observeClient(input);
    }
    private static int blockId(String name) {
        return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(state -> state.is(name)).findFirst().orElseThrow().networkId();
    }
    private static AddEntityPacket boat() {
        var packet = new AddEntityPacket(); packet.setRuntimeEntityId(12); packet.setUniqueEntityId(120);
        packet.setIdentifier("minecraft:boat"); packet.setPosition(Vector3f.from(0.5F, 64.375F, 0.5F));
        packet.setMotion(Vector3f.ZERO); packet.setRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);
        packet.getMetadata().put(EntityDataTypes.SCALE, 1F);
        var flags = new java.util.EnumMap<EntityFlag, Boolean>(EntityFlag.class);
        flags.put(EntityFlag.COLLIDABLE, true);
        packet.getMetadata().putFlags(flags);
        return packet;
    }

    @Test void repeatedQueriesUseInputOwnedCacheAndNextInputReadsNewWorldState() {
        SessionState session = session(); var world = session.world();
        world.updateBlock(0, 64, 0, 0, blockId("minecraft:stone"));
        input(session, 1);
        assertSame(session.player().collisionPhysics, world.collisionPhysics());
        var collision = session.collisionWorld();
        List<CollisionShapeEntry> original = collision.blockEntries(QUERY);
        assertFalse(original.isEmpty());
        assertEquals("minecraft:stone", original.get(0).block().identifier());
        var snapshot = session.player().snapshot(null);
        world.updateBlock(0, 64, 0, 0, world.airNetworkId());
        assertSame(original, collision.blockEntries(QUERY));
        assertEquals("minecraft:stone", snapshot.collisionState().blockQueries().values().iterator().next().get(0).block().identifier());
        input(session, 2);
        assertTrue(session.collisionWorld().blockEntries(QUERY).isEmpty());
        var history = session.collisionWorld().replay(snapshot.tick(), snapshot.collisionState());
        assertFalse(history.blockEntries(QUERY).isEmpty());
        assertTrue(session.collisionWorld().blockEntries(QUERY).isEmpty());
        assertFalse(session.player().collisionPhysics.replaying());
        assertEquals(2, session.player().collisionPhysics.inputTick());
    }

    @Test void historyRetainsActorPositionsAndDoesNotBorrowSubsequentMovementOrRemoval() {
        SessionState session = session(); session.observeBackend(boat()); input(session, 1);
        var collision = session.collisionWorld(); var actor = session.player().boundingBox();
        assertEquals(1, collision.nearbyEntityShapes(actor).size());
        var saved = session.player().snapshot(null);

        session.entity(12).getCurrent().getPos().x = 100;
        assertEquals(1, collision.nearbyEntityShapes(actor).size());
        assertEquals(0.5F, saved.collisionState().actors().get(0).position().x);
        saved.collisionState().actors().get(0).position().x = 999;
        assertEquals(0.5F, saved.collisionState().actors().get(0).position().x);
        input(session, 2);
        assertTrue(session.collisionWorld().nearbyEntityShapes(actor).isEmpty());
        var replay = session.collisionWorld().replay(saved.tick(), saved.collisionState());
        assertEquals(1, replay.nearbyEntityShapes(actor).size());
        assertTrue(session.collisionWorld().nearbyEntityShapes(actor).isEmpty());
        assertEquals(0.4F, EntityCollisionPhysics.sample(replay.collisionPhysics().actors(), new Vec3(0.5F, 64, 0.5F),
                actor, Long.MIN_VALUE, false, false, 0, 0).tolerance().x());
    }

    @Test void historicalNewQueryUsesSavedShulkerFrameWithoutAdvancingOrReplacingLiveActor() {
        SessionState session = session(); var world = session.world();
        world.updateBlock(0, 64, 0, 0, blockId("minecraft:undyed_shulker_box"));
        world.updateBlockEntityTag(0, 64, 0, NbtMap.builder().putByte("facing", (byte) 5).build());
        world.handleCollisionBlockEvent(0, 64, 0, 1, 1);
        input(session, 1); input(session, 2);
        var saved = session.player().snapshot(null);
        assertEquals(0.1F, saved.collisionState().shulkers().get(0).openness());
        input(session, 3); input(session, 4);
        var live = world.snapshotShulkerFrames().get(0);
        var history = session.collisionWorld().replay(saved.tick(), saved.collisionState());
        var query = new Box(1.01F, 64.1F, 0.1F, 1.04F, 64.9F, 0.9F);
        for (int i = 0; i < 5; i++) {
            var shapes = history.blockEntries(query);
            assertEquals(1, shapes.size());
            assertTrue(saved.collisionState().shulkers().get(0).shape().equals(shapes.get(0).shape()));
        }
        assertSame(live.shape(), world.snapshotShulkerFrames().get(0).shape());
        assertEquals(live.openness(), world.snapshotShulkerFrames().get(0).openness());
        assertFalse(session.player().collisionPhysics.replaying());
    }

    @Test void wireHistoryRestoresOverlapResolutionAndTeleportClearsIt() {
        SessionState session = session(); var state = session.player().collisionPhysics;
        state.acceptClientPosition(); state.requestClosestSpace(true, true);
        state.addInsideOneWay(QUERY); input(session, 1);
        var saved = session.snapshotAt(1);
        state.finishOverlapResolution(); state.consumeClosestSpaceRequest(); state.updatePushTowardsClosestSpace(true);
        input(session, 2);
        assertTrue(session.restoreTick(1));
        assertTrue(state.resolveAcceptedOverlaps());
        assertFalse(state.active());
        assertTrue(state.consumeClosestSpaceRequest());
        assertTrue(state.hasOneWayCollision(QUERY, false));
        var replay = session.collisionWorld().replay(1, saved.collisionState());
        assertTrue(replay.collisionPhysics().resolveAcceptedOverlaps());
        assertTrue(replay.collisionPhysics().consumeClosestSpaceRequest());
        saved.collisionState().blockPush().y = 100;
        assertEquals(0, saved.collisionState().blockPush().y);
        session.player().acceptTeleport(new Vec3(20, 70, 20), false);
        assertFalse(state.resolveAcceptedOverlaps());
        assertFalse(state.hasOneWayCollision(QUERY, false));
        assertFalse(state.consumeClosestSpaceRequest());
    }

    @Test void candidateReplayUsesCapturedMovementQueryAndLeavesLiveFrameUntouched() {
        SessionState session = session(); var world = session.world();
        world.updateBlock(1, 64, 0, 0, blockId("minecraft:stone"));
        input(session, 1);
        var player = session.player();
        Vec3 movement = new Vec3(1, 0, 0);
        var query = ac.navro.anticheat.collision.CollisionQuery.buildCollisionQuery(player.boundingBox(), movement, 0.5625F);
        var entries = session.collisionWorld().movementEntries(query);
        assertFalse(entries.isEmpty());
        player.collisionPhysics.commitMovement(query, entries);
        var saved = player.snapshot(null);
        world.updateBlock(1, 64, 0, 0, world.airNetworkId());
        input(session, 2);
        var liveBefore = player.collisionPhysics.snapshot();
        var prediction = session.predictionReplay();
        for (int trial = 0; trial < 3; trial++) {
            var result = prediction.solveCandidate(saved, movement, new Vec3(1, 1, 1), true);
            assertTrue(result.available());
            assertTrue(result.result().movement.x < 1);
            assertEquals(liveBefore.inputTick(), player.collisionPhysics.inputTick());
            assertEquals(liveBefore.blockQueries(), player.collisionPhysics.snapshot().blockQueries());
            assertFalse(player.collisionPhysics.replaying());
        }
        var current = prediction.solveCandidate(player.snapshot(null), movement, new Vec3(1, 1, 1), true);
        assertEquals(1, current.result().movement.x);
        assertEquals(liveBefore.blockQueries(), player.collisionPhysics.snapshot().blockQueries());
    }

    @Test void acceptedPositionRequestsNextInputResolutionAndTrialReplayDoesNotConsumeIt() {
        SessionState session = session(); var player = session.player();
        input(session, 1);
        player.collisionPhysics.finishOverlapResolution();
        player.acceptInputPosition(player.position(), List.of());
        var end = player.snapshot(null);
        assertFalse(end.collisionState().inputStartResolveAcceptedOverlaps());
        assertTrue(end.collisionState().resolveAcceptedOverlaps());
        var sameInput = session.collisionWorld().replay(end.tick(), end.collisionState());
        assertFalse(sameInput.collisionPhysics().resolveAcceptedOverlaps());
        assertTrue(player.collisionPhysics.resolveAcceptedOverlaps());
        input(session, 2);
        var next = player.snapshot(null);
        assertTrue(next.collisionState().inputStartResolveAcceptedOverlaps());
        var prediction = session.predictionReplay();
        for (int trial = 0; trial < 3; trial++) prediction.solveCandidate(next, Vec3.ZERO, Vec3.ZERO, true);
        assertTrue(player.collisionPhysics.resolveAcceptedOverlaps());
        assertTrue(next.collisionState().resolveAcceptedOverlaps());
    }

    @Test void perInputEntityToleranceIsResolvedOnceAndRewindCannotAdvanceBoatBudget() {
        SessionState session = session(); session.observeBackend(boat());
        session.player().rebasePosition(new Vec3(0.5F, 64, 0.5F), true);
        input(session, 1);
        session.player().collisionPhysics.selectedStartY(-0.1F);
        var first = session.sampleEntityTolerance();
        assertTrue(first.tolerance().boat()); assertEquals(1F, first.tolerance().y());
        assertEquals(first, session.sampleEntityTolerance());
        var saved = session.player().snapshot(null);
        var historical = session.collisionWorld().replay(saved.tick(), saved.collisionState());
        historical.collisionPhysics().selectedStartY(5);
        assertEquals(-0.1F, historical.collisionPhysics().selectedStartY());
        assertEquals(first.tolerance().y(), historical.collisionPhysics().tolerance().y());
        assertEquals(first, session.sampleEntityTolerance());
    }
}
