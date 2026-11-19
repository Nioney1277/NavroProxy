package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;





public final class AcceptedPositionPhysics {
    private AcceptedPositionPhysics() { }

    public static Box synchronize(final Box solved, final Vec3 accepted, final List<Box> collisions) {
        final Vec3 correction = accepted.subtract(solved.feetCenter());
        Box current = solved;
        for (final Vec3 segment : new Vec3[]{new Vec3(0, correction.y, 0),
                new Vec3(correction.x, 0, 0), new Vec3(0, 0, correction.z)}) {
            if (segment.x == 0 && segment.y == 0 && segment.z == 0) continue;
            final Box candidate = SweptMovement.computeMoveWithDepenetration(
                    new Vec3[]{segment}, current, collisions, Vec3.ZERO).aabb;


            if (!increasesPenetration(current, candidate, collisions)) current = candidate;
        }
        return current;
    }

    private static boolean increasesPenetration(final Box before, final Box after,
                                                final List<Box> collisions) {
        for (final Box shape : collisions) {
            if (penetration(after, shape) > penetration(before, shape)) return true;
        }
        return false;
    }

    private static float penetration(final Box actor, final Box shape) {
        if (!shape.isValid()) return 0;
        final float x = Math.min(actor.maxX - shape.minX, shape.maxX - actor.minX);
        final float y = Math.min(actor.maxY - shape.minY, shape.maxY - actor.minY);
        final float z = Math.min(actor.maxZ - shape.minZ, shape.maxZ - actor.minZ);
        return x > 0 && y > 0 && z > 0 ? Math.min(x, Math.min(y, z)) : 0;
    }
}
