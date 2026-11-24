package ac.navro.proxy.collision;

import ac.navro.anticheat.collision.SweptMovement;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityCollisionPhysicsTest {
    private static final Box PLAYER = new Box(-0.3F, 1, -0.3F, 0.3F, 2.8F, 0.3F);
    private static final float EPS = 1.0E-6F;

    private static EntityCollisionPhysics.Actor actor(long id, int dimension, boolean collidable,
                                                       boolean stackable, boolean falling,
                                                       boolean boat, boolean ghast, Vec3 position,
                                                       Box current, Box past) {
        return new EntityCollisionPhysics.Actor(id, dimension, collidable, stackable,
                falling, boat, ghast, position, current, past);
    }

    private static EntityCollisionPhysics.Actor boat(float distance) {
        return actor(2, 0, false, false, false, true, false,
                new Vec3(distance, 1, 0), PLAYER.offset(distance, 0, 0), null);
    }

    private static EntityCollisionPhysics.ToleranceSample boatSample(boolean enforce, float actualY, float baseY) {
        return EntityCollisionPhysics.sample(List.of(boat(1)), new Vec3(0, 1, 0),
                PLAYER, Long.MIN_VALUE, false, enforce, actualY, baseY);
    }

    @Test void solidQueryFiltersSelfDimensionAndNonSolidAndKeepsProviderOrder() {
        final var self = actor(1, 0, true, false, false, false, false, Vec3.ZERO, PLAYER, null);
        final var otherWorld = actor(2, 1, true, false, false, false, false, Vec3.ZERO, PLAYER, null);
        final var nonSolid = actor(3, 0, false, false, false, true, false, Vec3.ZERO, PLAYER, null);
        final Box right = PLAYER.offset(1, 0, 0), left = PLAYER.offset(-1, 0, 0);
        final var first = actor(4, 0, true, false, false, false, false, Vec3.ZERO, right, null);
        final var second = actor(5, 0, true, false, false, false, false, Vec3.ZERO, left, null);
        final var distant = actor(6, 0, true, false, false, false, false, Vec3.ZERO,
                PLAYER.offset(4, 0, 0), null);
        final var shapes = EntityCollisionPhysics.nearbyShapes(
                List.of(self, otherWorld, nonSolid, first, second, distant), PLAYER, 1, 0, false);
        assertEquals(2, shapes.size());
        assertSame(right, shapes.get(0));
        assertSame(left, shapes.get(1));
    }

    @Test void stackableSourceRequiresStackableTarget() {
        final var source = actor(2, 0, false, true, false, false, false, Vec3.ZERO, PLAYER, null);
        assertTrue(EntityCollisionPhysics.nearbyShapes(List.of(source), PLAYER, 1, 0, false).isEmpty());
        assertEquals(1, EntityCollisionPhysics.nearbyShapes(List.of(source), PLAYER, 1, 0, true).size());
    }

    @Test void fallingBlockSupportsFeetButCannotSqueezeFromAbove() {
        final var support = actor(2, 0, true, false, true, false, false, Vec3.ZERO,
                new Box(-0.5F, 0, -0.5F, 0.5F, 1, 0.5F), null);
        final var overhead = actor(3, 0, true, false, true, false, false, Vec3.ZERO,
                new Box(-0.5F, 0.01F, -0.5F, 0.5F, 1.01F, 0.5F), null);
        final var shapes = EntityCollisionPhysics.nearbyShapes(List.of(support, overhead), PLAYER, 1, 0, false);
        assertEquals(1, shapes.size());
        assertSame(support.current(), shapes.get(0));
    }

    @Test void nearbySolidCanDepenetrateEvenWhenBlockPhysicsIsOneWay() {
        final Box actor = new Box(0.95F, 0, 0.2F, 1.55F, 1.8F, 0.8F);
        final Box solid = new Box(0, 0, 0, 1, 1, 1);
        final var result = SweptMovement.computeYXZMoveWithDepenetration(Vec3.ZERO, actor,
                List.of(solid), EntityCollisionPhysics.SOLID_DEPENETRATION);
        assertEquals(0.05F, result.movement.x, EPS);
    }

    @Test void boatRangeIsStrictAndDoesNotRequireCollidableMetadata() {
        final var inside = EntityCollisionPhysics.sample(List.of(boat(1.999F)), new Vec3(0, 1, 0),
                PLAYER, Long.MIN_VALUE, false, false, 0, 0);
        final var boundary = EntityCollisionPhysics.sample(List.of(boat(2)), new Vec3(0, 1, 0),
                PLAYER, Long.MIN_VALUE, false, false, 0, 0);
        assertTrue(inside.tolerance().boat());
        assertEquals(0.4F, inside.tolerance().x());
        assertFalse(boundary.tolerance().collided());
    }

    @Test void riddenEntityNeverContributesTolerance() {
        final var sample = EntityCollisionPhysics.sample(List.of(boat(0)), new Vec3(0, 1, 0),
                PLAYER, 2, true, false, 0, 0);
        assertFalse(sample.tolerance().collided());
    }

    @Test void boatStepEnforcementAdvancesOnceAndUsesExactThresholds() {
        final var first = boatSample(false, 0.5F, 0);
        assertEquals(1, first.tolerance().y());
        assertTrue(first.enforceBoatStep());
        final var enforced = boatSample(first.enforceBoatStep(), 0.5F, 0);
        assertEquals(0, enforced.tolerance().y());
        assertFalse(enforced.enforceBoatStep());
        assertEquals(1, boatSample(true, 0.099F, 1).tolerance().y());
        assertEquals(0, boatSample(true, 0.1F, 0).tolerance().y());
        assertEquals(0, boatSample(false, 0.605F, 0).tolerance().y());
        assertEquals(0, boatSample(false, 0.2F, 0.01F).tolerance().y());
        assertFalse(boatSample(false, 0.42F, 0).enforceBoatStep());
    }

    @Test void happyGhastUsesPastAabbAndHasNoBoatVerticalAllowance() {
        final var ghast = actor(3, 0, false, false, false, false, true, Vec3.ZERO,
                PLAYER.offset(20, 0, 0), PLAYER.offset(0.5F, 0, 0));
        final var sample = EntityCollisionPhysics.sample(List.of(ghast), new Vec3(0, 1, 0),
                PLAYER, Long.MIN_VALUE, false, true, 0, 0);
        assertTrue(sample.tolerance().happyGhast());
        assertEquals(0.4F, sample.tolerance().y());
        assertFalse(sample.enforceBoatStep());
    }

    @Test void boatAndGhastTakeMaximumAllowanceInsteadOfAdding() {
        final var ghast = actor(3, 0, true, false, false, false, true, Vec3.ZERO, PLAYER, null);
        final var sample = EntityCollisionPhysics.sample(List.of(boat(1), ghast), new Vec3(0, 1, 0),
                PLAYER, Long.MIN_VALUE, false, false, 0, 0);
        assertEquals(0.4F, sample.tolerance().x());
        assertEquals(1, sample.tolerance().y());
        assertEquals(0.4F, sample.tolerance().z());
    }

    @Test void nonFiniteClientValuesCannotPoisonAcceptedBaseline() {
        final var allowance = new EntityCollisionPhysics.Tolerance(true, false, 0.4F, 1, 0.4F);
        final var accepted = EntityCollisionPhysics.clampAccepted(new Vec3(1, 2, 3),
                new Vec3(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), allowance);
        assertEquals(1, accepted.x);
        assertEquals(2, accepted.y);
        assertEquals(3, accepted.z);
    }

    @Test void checksUseLargestResidualAxisRatherThanSummedAllowance() {
        final var allowance = new EntityCollisionPhysics.Tolerance(false, true, 0.4F, 0.4F, 0.4F);
        assertEquals(0.2F, EntityCollisionPhysics.adjustedMotionOffset(Vec3.ZERO,
                new Vec3(0.5F, -0.6F, 0.3F), allowance), EPS);
    }

}
