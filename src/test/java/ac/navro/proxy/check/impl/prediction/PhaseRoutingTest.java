package ac.navro.proxy.check.impl.prediction;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhaseRoutingTest {
    private static final float EPSILON = 1.0E-4F;
    private static final Box START = new Box(-0.3F, 0, -0.3F, 0.3F, 1.8F, 0.3F);
    private static final Box WALL = new Box(1, 0, -1, 2, 3, 1);

    @Test void clientInsideWallClaimsPhaseEvenThoughPredictionStoppedOutside() {
        final Box predicted = START.offset(0.7F, 0, 0);
        final Box client = START.offset(1.0F, 0, 0);
        assertFalse(Phase.newlyEntered(START, predicted, WALL, EPSILON));
        assertTrue(Phase.newlyEntered(START, client, WALL, EPSILON));
        final List<String> alerts = new ArrayList<>();
        Prediction.routeChecks(() -> {
            if (!Phase.newlyEntered(START, client, WALL, EPSILON)) return false;
            alerts.add("Phase");
            return true;
        }, () -> { fail("GroundSpoof must not run after Phase"); return false; },
                () -> { fail("NoSlow must not run after Phase"); return false; },
                () -> { fail("AntiKB must not run after Phase"); return false; },
                () -> alerts.addAll(List.of("Collisions", "Horizonal", "Vertical")));
        assertEquals(List.of("Phase"), alerts);
    }

    @Test void touchingWallAndSubThresholdPenetrationAreNotPhase() {
        assertFalse(Phase.newlyEntered(START, START.offset(0.7F, 0, 0), WALL, EPSILON));
        assertFalse(Phase.newlyEntered(START, START.offset(0.70005F, 0, 0), WALL, EPSILON));
    }

    @Test void existingBlockOverlapIsNotANewPhaseEntry() {
        final Box inside = START.offset(1.0F, 0, 0);
        assertFalse(Phase.newlyEntered(inside, inside.offset(0.1F, 0, 0), WALL, EPSILON));
    }

    @Test void partialBlocksUseTheirActualShapes() {
        final Box lowSlab = new Box(1, 0, -1, 2, 0.5F, 1);
        assertFalse(Phase.newlyEntered(START, START.offset(1, 0.5F, 0), lowSlab, EPSILON));
        assertTrue(Phase.newlyEntered(START, START.offset(1, 0.3F, 0), lowSlab, EPSILON));
    }

    @Test void crossingWholeWallCannotFallThroughToHorizontalError() {
        final Box predicted = START.offset(0.7F, 0, 0);
        final Box client = START.offset(3, 0, 0);
        assertFalse(client.intersects(WALL), "Endpoint is already beyond the wall");
        assertTrue(Phase.crossedBlockingShape(START, client, predicted, WALL,
                new Vec3(3, 0, 0), new Vec3(0.7F, 0, 0), EPSILON));
    }

    @Test void passingWholeCeilingClaimsPhaseRatherThanVertical() {
        final Box ceiling = new Box(-2, 2, -2, 2, 2.2F, 2);
        assertTrue(Phase.crossedBlockingShape(START, START.offset(0, 2.5F, 0),
                START.offset(0, 0.2F, 0), ceiling,
                new Vec3(0, 2.5F, 0), new Vec3(0, 0.2F, 0), EPSILON));
    }

    @Test void diagonalMovementAwayFromWallDoesNotCountAsCrossing() {
        assertFalse(Phase.crossedBlockingShape(START, START.offset(3, 0, 6),
                START.offset(0.7F, 0, 0), WALL,
                new Vec3(3, 0, 0), new Vec3(0.7F, 0, 0), EPSILON));
    }

    @Test void legitimateStepAboveBlockDoesNotCountAsCrossing() {
        final Box step = new Box(1, 0, -1, 2, 0.5F, 1);
        final Box above = START.offset(3, 0.5F, 0);
        assertFalse(Phase.crossedBlockingShape(START, above, above, step,
                new Vec3(3, 0, 0), new Vec3(3, 0.5F, 0), EPSILON));
    }

    @Test void disabledPhaseStillOwnsSampleWithoutRelabelingIt() {
        final List<String> alerts = new ArrayList<>();
        Prediction.routeChecks(() -> Phase.newlyEntered(START, START.offset(1, 0, 0), WALL, EPSILON),
                () -> false, () -> false, () -> false,
                () -> alerts.addAll(List.of("Collisions", "Horizonal", "Vertical")));
        assertTrue(alerts.isEmpty());
    }

    @Test void ordinaryErrorsStillReachGenericChecks() {
        final List<String> alerts = new ArrayList<>();
        Prediction.routeChecks(() -> false, () -> false, () -> false, () -> false,
                () -> alerts.addAll(List.of("Collisions", "Horizonal", "Vertical")));
        assertEquals(List.of("Collisions", "Horizonal", "Vertical"), alerts);
    }

    @Test void projectedActualHeightAvoidsFalseCeilingEntryAndStillDetectsRealPenetration() {
        final var player = new ac.navro.proxy.prediction.PredictionSample(null,null,null);
        player.position=new Vec3(0,62,0);
        final Box ceiling = new Box(-2, 64, -2, 2, 65, 2);
        final Box start = new Box(-0.3F, 62, -0.3F, 0.3F, 63.79987335205078F, 0.3F);
        final Vec3 contact = new Vec3(0, 62.20012664794922F, 0);
        player.boundingBox=start.offset(contact.subtract(player.position));
        player.position=contact;
        assertEquals(64, player.boundingBox.maxY);
        assertTrue(Phase.newlyEntered(start, player.dimensions.getBoxAt(contact), ceiling, EPSILON),
                "Nominal reconstruction reproduces the old false Phase entry");
        assertFalse(Phase.newlyEntered(start, player.boxAtProjectedPosition(contact), ceiling, EPSILON));
        assertTrue(Phase.newlyEntered(start, player.boxAtProjectedPosition(contact.up(0.001F)), ceiling, EPSILON));
    }
}
