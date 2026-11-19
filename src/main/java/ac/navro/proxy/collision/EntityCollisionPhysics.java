package ac.navro.proxy.collision;

import ac.navro.proxy.compensated.cache.entity.EntityCache;
import java.util.Map;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.ArrayList;
import java.util.List;


public final class EntityCollisionPhysics {
    public static final int STACKABLE = 37;
    public static final int COLLIDABLE = 121;
    public static final Vec3 SOLID_DEPENETRATION = new Vec3(0.1F, 0.1F, 0.1F);
    private EntityCollisionPhysics() { }

    public record Actor(long runtimeId, int dimension, boolean collidable, boolean stackable,
                        boolean fallingBlock, boolean boat, boolean happyGhast,
                        Vec3 position, Box current, Box past) {
        public Actor { position = position == null ? null : position.clone(); }
        @Override public Vec3 position() { return position == null ? null : position.clone(); }
    }

    public static List<Actor> snapshotActors(final Map<Long, EntityCache> entities) {
        final List<Actor> actors = new ArrayList<>();
        for (final EntityCache cache : entities.values()) {
            if (cache == null || cache.getCurrent() == null) continue;
            actors.add(new Actor(cache.runtimeId(), cache.dimension(),
                    Boolean.TRUE.equals(cache.collidableFlag()), cache.stackable(),
                    cache.fallingBlock(), cache.boat(), cache.happyGhast(),
                    cache.getCurrent().getPos().clone(), cache.getCurrent().getBoundingBox(),
                    null));
        }
        return List.copyOf(actors);
    }

    public static List<Box> nearbyShapes(final List<Actor> actors, final Box target,
                                        final long targetId, final int dimension,
                                        final boolean targetStackable) {
        final Box query = target.expand(2.0F);
        final List<Box> shapes = new ArrayList<>();
        for (final Actor actor : actors) {
            if (actor.runtimeId() == targetId || actor.dimension() != dimension
                    || actor.current() == null || !actor.current().isValid()
                    || !query.intersects(actor.current())) continue;
            if (!(actor.collidable() || actor.stackable() && targetStackable)) continue;

            if (actor.fallingBlock() && actor.current().maxY > target.minY) continue;
            shapes.add(actor.current());
        }
        return List.copyOf(shapes);
    }

    public record Tolerance(boolean boat, boolean happyGhast, float x, float y, float z) {
        public static final Tolerance NONE = new Tolerance(false, false, 0, 0, 0);
        public boolean collided() { return boat || happyGhast; }
    }

    public record ToleranceSample(Tolerance tolerance, boolean enforceBoatStep) { }

    public static ToleranceSample sample(final List<Actor> actors, final Vec3 verifiedPosition,
                                         final Box movementBox, final long ridingId,
                                         final boolean riding, final boolean enforceBoatStep,
                                         final float actualY, final float baseMotionY) {
        boolean boat = false, happyGhast = false;
        final Box expanded = movementBox.expand(1.0F);
        for (final Actor actor : actors) {
            if (actor.runtimeId() == ridingId) continue;
            if (actor.boat() && actor.position() != null
                    && verifiedPosition.distanceTo(actor.position()) < 2.0F) boat = true;
            if (actor.happyGhast() && (actor.current() != null && expanded.intersects(actor.current())
                    || actor.past() != null && expanded.intersects(actor.past()))) happyGhast = true;
        }
        boolean enforce = enforceBoatStep;
        float boatY = 0;
        if (boat && !riding) {
            if (actualY < 0.605F) {
                if (enforce) {
                    if (actualY < 0.1F) boatY = 1.0F;
                    enforce = false;
                } else if (baseMotionY <= 0.0F) {
                    boatY = 1.0F;
                    if (actualY > 0.42F) enforce = true;
                }
            }
        } else if (!boat) {
            enforce = false;
        }
        return new ToleranceSample(new Tolerance(boat, happyGhast,
                boat || happyGhast ? 0.4F : 0,
                Math.max(boatY, happyGhast ? 0.4F : 0),
                boat || happyGhast ? 0.4F : 0), enforce);
    }


    public static float adjustedMotionOffset(final Vec3 predicted, final Vec3 actual,
                                             final Tolerance tolerance) {
        final float x = Math.max(0, Math.abs(actual.x - predicted.x) - tolerance.x());
        final float y = Math.max(0, Math.abs(actual.y - predicted.y) - tolerance.y());
        final float z = Math.max(0, Math.abs(actual.z - predicted.z) - tolerance.z());
        return Math.max(x, Math.max(y, z));
    }

    public static Vec3 clampAccepted(final Vec3 predicted, final Vec3 client,
                                     final Tolerance tolerance) {
        if (tolerance == null || !tolerance.collided()) return predicted.clone();
        return new Vec3(predicted.x + clamp(client.x - predicted.x, tolerance.x()),
                predicted.y + clamp(client.y - predicted.y, tolerance.y()),
                predicted.z + clamp(client.z - predicted.z, tolerance.z()));
    }

    private static float clamp(final float difference, final float allowance) {
        if (!Float.isFinite(difference)) return 0;
        final float limit = Math.max(0, allowance);
        return Math.max(-limit, Math.min(limit, difference));
    }

}
