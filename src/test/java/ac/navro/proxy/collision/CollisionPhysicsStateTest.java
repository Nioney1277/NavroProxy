package ac.navro.proxy.collision;

import ac.navro.anticheat.collision.SweptMovement;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollisionPhysicsStateTest {
    private static final Box SCAFFOLD = new Box(0, 0, 0, 1, 1, 1);
    private static final Box INSIDE = new Box(0.2F, 0.2F, 0.2F, 0.8F, 2, 0.8F);
    private static final Box OUTSIDE = INSIDE.offset(2, 0, 0);

    @Test void oneWayContactsSurviveRewindAndTrialsOnlyPruneTheirOwnView() {
        final var state = new CollisionPhysicsState();
        state.addInsideOneWay(SCAFFOLD);
        state.addInsideOneWay(SCAFFOLD.clone());
        assertEquals(1, state.snapshot().oneWayBlocks().size());
        final var saved = state.snapshot();
        assertTrue(state.hasOneWayCollision(INSIDE, true));
        assertFalse(state.hasOneWayCollision(OUTSIDE, false));
        assertTrue(state.hasOneWayCollision(INSIDE, false));
        assertFalse(state.hasOneWayCollision(OUTSIDE, true));
        assertFalse(state.hasOneWayCollision(INSIDE, false));
        state.restore(saved);
        assertTrue(state.hasOneWayCollision(INSIDE, false));
        state.reset();
        assertFalse(state.hasOneWayCollision(INSIDE, false));
        assertEquals(1, saved.oneWayBlocks().size());
    }

    @Test void pushTowardsSpaceGateIsSavedAndClearedWithDepenetrationState() {
        final var state = new CollisionPhysicsState();
        state.updatePushTowardsClosestSpace(true);
        final var saved = state.snapshot();
        assertTrue(state.hasOneWayCollision(OUTSIDE, false));
        state.updatePushTowardsClosestSpace(false);
        assertFalse(state.hasOneWayCollision(OUTSIDE, false));
        state.restore(saved);
        assertTrue(state.hasOneWayCollision(OUTSIDE, false));
        state.reset();
        assertFalse(state.hasOneWayCollision(OUTSIDE, false));
    }

    @Test void genericBlockQueryDoesNotInheritExtraMovementShapes() {
        final var state = new CollisionPhysicsState();
        state.begin(null, null, 10, state.snapshot());
        final Box fetched = new Box(-2, -2, -2, 3, 4, 3);
        final var floor = new CollisionShapeEntry(new Box(-2, -1, -2, 3, 0, 3), null);
        state.commitMovement(fetched, List.of(floor));
        final Box upwardQuery = new Box(0.2F, 1, 0.2F, 0.8F, 2, 0.8F);
        assertEquals(List.of(floor), state.movementBlocks(upwardQuery, () -> {
            fail("Contained MoveCollision query should reuse the fetch");
            return List.of();
        }));
        assertTrue(state.blocks(upwardQuery, List::of).isEmpty(),
                "Shulker obstruction query has no floor contact");
        assertTrue(state.blocks(upwardQuery, () -> {
            fail("Exact query cache should preserve the original answer");
            return List.of(floor);
        }).isEmpty());
        state.commitMovement(upwardQuery, List.of(floor));
        final Box withinOriginalFetch = new Box(1, 0, 1, 2, 3, 2);
        assertEquals(List.of(floor), state.movementBlocks(withinOriginalFetch, () -> {
            fail("Reused fetch must retain its original bounds");
            return List.of();
        }));
        final Box beyondFetch = upwardQuery.offset(20, 0, 0);
        assertTrue(state.movementBlocks(beyondFetch, List::of).isEmpty());
        final var saved = state.snapshot();
        state.restore(saved);
        state.begin(null, null, 10, saved);
        assertEquals(List.of(floor), state.movementBlocks(upwardQuery, List::of));
        assertTrue(state.blocks(upwardQuery, List::of).isEmpty());
    }

    @Test void currentRewindInputUsesNewSelectedYAndHistoricalSampleIsStable() {
        final var state = new CollisionPhysicsState();
        state.selectedStartY(0.2F);
        final var liveFrame = state.snapshot();
        state.begin(null, null, 10, liveFrame);
        state.selectedStartY(-0.1F);
        assertEquals(-0.1F, state.selectedStartY());
        final var sample = new EntityCollisionPhysics.ToleranceSample(
                new EntityCollisionPhysics.Tolerance(true, false, 0.4F, 1, 0.4F), true);
        state.setTolerance(sample);
        final var historicalFrame = state.snapshot();
        state.begin(null, null, 10, historicalFrame);
        state.selectedStartY(0.5F);
        assertEquals(-0.1F, state.selectedStartY());
        assertEquals(sample.tolerance(), state.tolerance());
        assertTrue(state.enforceBoatStep());
        state.begin(null, null, 11, historicalFrame);
        assertTrue(state.collidedPreviousTick());
    }

    @Test void replayUsesInputStartResolutionButRewindBaseRestoresEndRequest() {
        final var state = new CollisionPhysicsState();
        state.beginOverlapResolutionInput(null);
        state.finishOverlapResolution();
        state.acceptClientPosition();
        final var firstInput = state.snapshot();
        assertFalse(firstInput.inputStartResolveAcceptedOverlaps());
        assertTrue(firstInput.resolveAcceptedOverlaps());
        state.begin(null, null, 10, firstInput);
        assertFalse(state.resolveAcceptedOverlaps());
        state.restore(firstInput);
        assertTrue(state.resolveAcceptedOverlaps());
        state.beginOverlapResolutionInput(null);
        state.finishOverlapResolution();
        final var rejectedInput = state.snapshot();
        assertTrue(rejectedInput.inputStartResolveAcceptedOverlaps());
        assertFalse(rejectedInput.resolveAcceptedOverlaps());
        state.begin(null, null, 11, rejectedInput);
        assertTrue(state.resolveAcceptedOverlaps());
        state.finishOverlapResolution();
        state.restore(rejectedInput);
        assertFalse(state.resolveAcceptedOverlaps());
        assertTrue(rejectedInput.inputStartResolveAcceptedOverlaps());
    }

    @Test void repeatedAcceptanceKeepsOnePendingResolutionAndSnapshotsOwnItsLifetime() {
        final var state = new CollisionPhysicsState();
        for (int i = 0; i < 3; i++) state.acceptClientPosition();
        final var accepted = state.snapshot();
        state.endInput();
        state.beginOverlapResolutionInput(null);
        assertTrue(state.resolveAcceptedOverlaps());
        state.finishOverlapResolution();
        assertFalse(state.resolveAcceptedOverlaps());
        assertTrue(accepted.resolveAcceptedOverlaps());
        state.reset();
        assertFalse(state.snapshot().inputStartResolveAcceptedOverlaps());
        assertFalse(state.snapshot().resolveAcceptedOverlaps());
        state.restore(accepted);
        assertTrue(state.resolveAcceptedOverlaps());
    }

    @Test void closestSpaceReplayRestartsAtInputBoundaryRatherThanAtConsumedEndState() {
        final var state = new CollisionPhysicsState();
        state.requestClosestSpace(true, true);
        state.beginClosestSpaceInput(null);
        assertTrue(state.consumeClosestSpaceRequest());
        final var consumed = state.snapshot();
        state.restore(consumed);
        assertFalse(state.consumeClosestSpaceRequest());
        state.begin(null, null, 20, consumed);
        assertTrue(state.consumeClosestSpaceRequest());
        state.requestClosestSpace(false, true);
        state.requestClosestSpace(true, false);
        assertFalse(state.consumeClosestSpaceRequest());
    }

}
