package ac.navro.proxy.collision;

import ac.navro.proxy.collision.block.ShulkerCollisionState;
import ac.navro.anticheat.collision.SweptMovement;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.util.math.Direction;
import java.util.ArrayList;
import java.util.List;


public final class ShulkerPushPhysics {
    private ShulkerPushPhysics() { }


    public static void finish(final WirePlayerState player, final ProtocolCollisionWorld world,
                              final boolean trackedVehicle) {
        if (player.isMovementExempted() || trackedVehicle) return;
        for (final ShulkerCollisionState.Frame frame : player.collisionPhysics.shulkers()) {
            final Box actor = player.boundingBox();
            final Box shulker = frame.shape();
            if (frame.openness() == 0 || !shulker.contract(1.0E-6F).intersects(actor)) continue;
            final Direction facing = face(frame.facing());
            final Vec3 outward = axisInside(shulker, actor, facing);
            final Box moved = actor.offset(outward);
            final List<Box> collisions = new ArrayList<>();
            for (final CollisionShapeEntry entry : world.blockEntries(moved)) {
                if (!entry.shape().equals(shulker)) collisions.add(entry.shape());
            }
            final Vec3 request = calculateMovement(actor, shulker, facing, collisions);
            if (request.lengthSquared() == 0) continue;
            final SweptMovement.MoveResult result = Collider.collideBlockPush(player, world,
                    Collider.maybeBackOffFromEdge(player, world, request));
            final Vec3 resolvedPosition = result.aabb.feetCenter();
            final Vec3 applied = resolvedPosition.subtract(player.position());
            player.setResolvedPosition(resolvedPosition, result.aabb, false, trackedVehicle);
            player.setAfterCollision(player.afterCollision().add(applied));
            player.collisionPhysics.addBlockPush(applied);
            final float epsilon = 1.1920929E-7F;
            final boolean horizontalCollision = Math.abs(request.x - result.movement.x) > epsilon
                    || Math.abs(request.z - result.movement.z) > epsilon;
            final boolean verticalCollision = Math.abs(request.y - result.movement.y) > epsilon;
            final boolean onGround = verticalCollision ? request.y < 0
                    : request.y == 0 && player.onGround();
            player.setCollisionResult(horizontalCollision, verticalCollision, onGround);
        }
    }

    public static Vec3 calculateMovement(final Box actor, final Box shulker,
                                         final Direction facing, final List<Box> collisions) {
        if (actor == null || !actor.isValid() || shulker == null || !shulker.isValid()) {
            return Vec3.ZERO.clone();
        }
        final Vec3 outward = axisInside(shulker, actor, facing);
        if (collisions.isEmpty()) return outward;
        if (facing == Direction.UP) return Vec3.ZERO.clone();
        final Box moved = actor.offset(outward);
        Vec3 correction = Vec3.ZERO.clone();
        for (final Box collision : collisions) {
            final Vec3 candidate = axisInside(collision, moved, facing.getOpposite());
            if (candidate.subtract(correction).lengthSquared() > 0) correction = candidate;
        }
        return outward.add(correction);
    }

    static Vec3 axisInside(final Box collider, final Box actor, final Direction axis) {
        return switch (axis) {
            case EAST -> new Vec3(collider.maxX - actor.minX, 0, 0);
            case WEST -> new Vec3(collider.minX - actor.maxX, 0, 0);
            case UP -> new Vec3(0, collider.maxY - actor.minY, 0);
            case DOWN -> new Vec3(0, collider.minY - actor.maxY, 0);
            case SOUTH -> new Vec3(0, 0, collider.maxZ - actor.minZ);
            case NORTH -> new Vec3(0, 0, collider.minZ - actor.maxZ);
        };
    }

    private static Direction face(final int value) {
        return switch (value) {
            case 1 -> Direction.UP;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> Direction.DOWN;
        };
    }
}
