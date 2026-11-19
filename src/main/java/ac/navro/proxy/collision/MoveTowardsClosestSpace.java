package ac.navro.proxy.collision;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.ArrayList;
import java.util.List;


public final class MoveTowardsClosestSpace {
    private static final float QUERY_EXPANSION = 1.0F;
    private static final float PUSH_MAGNITUDE = 0.1F;
    private static final float DIRECTION_EPSILON = 1.1920929E-7F;
    private static final float MIN_LENGTH = 1.0E-4F;
    private MoveTowardsClosestSpace() { }


    public static void prepare(final WirePlayerState player, final ProtocolCollisionWorld world,
                               final boolean trackedVehicle) {
        prepare(player, trackedVehicle, () -> {
            final Box actor = player.boundingBox();
            final Box query = new Box(actor.minX - QUERY_EXPANSION, actor.minY,
                    actor.minZ - QUERY_EXPANSION, actor.maxX + QUERY_EXPANSION,
                    actor.maxY, actor.maxZ + QUERY_EXPANSION);
            return world.collectColliders(List.of(), query);
        });
    }

    static void prepare(final WirePlayerState player, final boolean trackedVehicle,
                        final java.util.function.Supplier<List<Box>> fetch) {
        if (!player.collisionPhysics.consumeClosestSpaceRequest()
                || !player.hasCollision()
                || trackedVehicle) return;
        player.setVelocity(apply(player.boundingBox(), fetch.get(), player.velocity()));
    }

    public static Vec3 apply(final Box actor, final List<Box> queried, final Vec3 velocity) {
        final List<Box> collisions = new ArrayList<>();
        for (final Box collision : queried) {
            if (collision.intersects(actor)) {
                collisions.add(collision);
            }
        }
        if (collisions.isEmpty()) {
            return velocity.clone();
        }

        float averageX = 0.0F;
        float averageZ = 0.0F;
        for (final Box collision : collisions) {
            averageX += (collision.minX + collision.maxX) * 0.5F;
            averageZ += (collision.minZ + collision.maxZ) * 0.5F;
        }
        averageX /= collisions.size();
        averageZ /= collisions.size();

        final float halfWidth = (actor.maxX - actor.minX) * 0.5F;
        final float halfDepth = (actor.maxZ - actor.minZ) * 0.5F;
        final float actorCenterX = (actor.minX + actor.maxX) * 0.5F;
        final float actorCenterZ = (actor.minZ + actor.maxZ) * 0.5F;

        float directionX = actorCenterX - averageX;
        float directionZ = actorCenterZ - averageZ;
        if (Math.abs(directionX) < DIRECTION_EPSILON
                && Math.abs(directionZ) < DIRECTION_EPSILON) {
            directionX = 1.0F;
            directionZ = 1.0F;
        }

        directionX = selectOpenDirectionX(
                directionX,
                averageX,
                averageZ,
                halfWidth,
                halfDepth,
                actor.minY,
                actor.maxY,
                collisions);
        directionZ = selectOpenDirectionZ(
                directionZ,
                averageX,
                averageZ,
                halfWidth,
                halfDepth,
                actor.minY,
                actor.maxY,
                collisions);

        if (Math.abs(directionX) < DIRECTION_EPSILON
                && Math.abs(directionZ) < DIRECTION_EPSILON) {
            return velocity.clone();
        }

        final float length = (float) Math.sqrt(
                directionX * directionX + directionZ * directionZ);
        final float pushX = directionX / length * PUSH_MAGNITUDE;
        final float pushZ = directionZ / length * PUSH_MAGNITUDE;

        final Vec3 delta = velocity.clone();
        delta.x = applyHorizontalPush(delta.x, pushX, length);
        delta.z = applyHorizontalPush(delta.z, pushZ, length);
        return delta;
    }

    private static float selectOpenDirectionX(
            final float direction,
            final float centerX,
            final float centerZ,
            final float halfWidth,
            final float halfDepth,
            final float minY,
            final float maxY,
            final List<Box> collisions) {
        final float sign = Math.signum(direction);
        if (sign == 0.0F) {
            return 0.0F;
        }

        final float baseMinX = centerX - halfWidth;
        final float baseMaxX = centerX + halfWidth;
        final float baseMinZ = centerZ - halfDepth;
        final float baseMaxZ = centerZ + halfDepth;
        final Box forward = new Box(
                baseMinX + sign,
                minY,
                baseMinZ,
                baseMaxX + sign,
                maxY,
                baseMaxZ);
        if (!intersectsAny(forward, collisions)) {
            return direction;
        }

        final Box reverse = new Box(
                baseMinX - sign,
                minY,
                baseMinZ,
                baseMaxX - sign,
                maxY,
                baseMaxZ);
        return intersectsAny(reverse, collisions) ? 0.0F : -direction;
    }

    private static float selectOpenDirectionZ(
            final float direction,
            final float centerX,
            final float centerZ,
            final float halfWidth,
            final float halfDepth,
            final float minY,
            final float maxY,
            final List<Box> collisions) {
        final float sign = Math.signum(direction);
        if (sign == 0.0F) {
            return 0.0F;
        }

        final float baseMinX = centerX - halfWidth;
        final float baseMaxX = centerX + halfWidth;
        final float baseMinZ = centerZ - halfDepth;
        final float baseMaxZ = centerZ + halfDepth;
        final Box forward = new Box(
                baseMinX,
                minY,
                baseMinZ + sign,
                baseMaxX,
                maxY,
                baseMaxZ + sign);
        if (!intersectsAny(forward, collisions)) {
            return direction;
        }

        final Box reverse = new Box(
                baseMinX,
                minY,
                baseMinZ - sign,
                baseMaxX,
                maxY,
                baseMaxZ - sign);
        return intersectsAny(reverse, collisions) ? 0.0F : -direction;
    }

    private static boolean intersectsAny(final Box test, final List<Box> collisions) {
        for (final Box collision : collisions) {
            if (collision.intersects(test)) {
                return true;
            }
        }
        return false;
    }

    private static float applyHorizontalPush(
            final float current,
            final float push,
            final float directionLength) {
        final float absolutePush = directionLength < MIN_LENGTH
                ? 0.0F
                : Math.abs(push);
        if (absolutePush <= Math.abs(current)) {
            return current;
        }
        return Math.max(-absolutePush, Math.min(absolutePush, current + push));
    }
}
