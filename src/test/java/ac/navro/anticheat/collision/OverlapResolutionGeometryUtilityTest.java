package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;





class OverlapResolutionGeometryUtilityTest {
    @Test void nearestSeparatingFaceHandlesAllSixDirectionsAndDifferentOverlapDepths() {
        var solid = new Box(0, 0, 0, 1, 1, 1);
        for (int axis = 0; axis < 3; axis++) for (int side : new int[]{-1, 1}) {
            for (float depth : new float[]{1F / 64, 1F / 16, 1F / 8}) {
                float[] min = {.25F, .25F, .25F}, max = {.75F, .75F, .75F};
                min[axis] = side < 0 ? depth - .5F : 1 - depth;
                max[axis] = min[axis] + .5F;
                var actor = new Box(min[0], min[1], min[2], max[0], max[1], max[2]);
                float[] request = {0, 0, 0};
                request[axis] = -side * .25F;
                int tangent = (axis + 1) % 3;
                request[tangent] = 1F / 32;
                var result = SweptMovement.computeYXZMoveWithDepenetration(
                        new Vec3(request[0], request[1], request[2]), actor, List.of(solid), Vec3.ZERO, true);
                float[] actual = {result.movement.x, result.movement.y, result.movement.z};
                assertEquals(side * depth, actual[axis], "Nearest face axis=" + axis + " side=" + side);
                assertEquals(1F / 32, actual[tangent], "Tangential travel is preserved");
                assertFalse(result.aabb.intersects(solid));
            }
        }
    }

    @Test void outwardTravelIsPreservedAndSeparatedBodiesKeepTheirRequestedMovement() {
        var solid = new Box(0, 0, 0, 1, 1, 1);
        var actor = new Box(.25F, .875F, .25F, .75F, 2.675F, .75F);
        var escaping = SweptMovement.computeYXZMoveWithDepenetration(new Vec3(0, .5F, 0), actor,
                List.of(solid), Vec3.ZERO, true);
        assertEquals(.5F, escaping.movement.y);
        assertFalse(escaping.aabb.intersects(solid));
        var clear = SweptMovement.computeYXZMoveWithDepenetration(new Vec3(.125F, .25F, -.125F),
                actor.offset(0, 3, 0), List.of(solid), Vec3.ZERO, true);
        assertEquals(.125F, clear.movement.x);
        assertEquals(.25F, clear.movement.y);
        assertEquals(-.125F, clear.movement.z);
    }

    @Test void sweepStillStopsAnIncomingBodyAtTheActualSurface() {
        var actor = new Box(.25F, 1.25F, .25F, .75F, 3.05F, .75F);
        var floor = new Box(0, 0, 0, 1, 1, 1);
        var result = SweptMovement.computeYXZMoveWithDepenetration(new Vec3(0, -.5F, 0), actor,
                List.of(floor), Vec3.ZERO, true);
        assertEquals(-.25F, result.movement.y);
        assertEquals(1F, result.aabb.minY);
        assertFalse(result.aabb.intersects(floor));
    }

    @Test void headOverlapResolvesItsActualDepthWithoutRebuildingTheActor() {
        var actor = new Box(-.29F, 64.2F, -.29F, .29F, Math.nextUp(66F), .29F);
        var ceiling = new Box(-1, 66, -1, 1, 67, 1);
        var result = ProtocolCollisionSolver.solveResolved(new Vec3(0, .42F, 0), actor,
                List.of(ceiling), Vec3.ZERO, 0, false, true);
        assertEquals(66F - actor.maxY, result.movement.y);
        assertEquals(66F, result.aabb.maxY);
        assertEquals(actor.minY + (66F - actor.maxY), result.aabb.minY);
        assertEquals(actor.minX, result.aabb.minX);
        assertEquals(actor.maxZ, result.aabb.maxZ);
    }
}
