package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;


public final class SneakEdgePhysics {
    private static final float EPSILON = 1.1920929E-7F;
    private SneakEdgePhysics() { }


    public static boolean active(final Boolean moveInputSneaking, final boolean actorSneaking,
                                 final boolean onGround, final boolean flag6) {
        return onGround && (moveInputSneaking == null ? actorSneaking : moveInputSneaking) && !flag6;
    }

    public record Result(Vec3 movement, boolean clearX, boolean clearZ) {
        public Vec3 applyVelocity(final Vec3 velocity) {
            return new Vec3(clearX ? 0 : velocity.x, velocity.y, clearZ ? 0 : velocity.z);
        }
    }

    public static Result unchanged(final Vec3 movement) {
        return new Result(movement, false, false);
    }


    public static Result resolve(final Box box, final List<Box> collisions,
                                 final Vec3 submitted, final float maxAutoStep) {
        float x = submitted.x, z = submitted.z;
        final float distance = -(maxAutoStep * 1.01F);
        final Box inset = inset(box);
        while (x != 0 && !supported(inset, collisions, x, 0, distance)) x = approachZero(x);
        while (z != 0 && !supported(inset, collisions, 0, z, distance)) z = approachZero(z);
        while ((x != 0 || z != 0) && !supported(inset, collisions, x, z, distance)) {
            x = approachZero(x);
            z = approachZero(z);
        }


        return new Result(new Vec3(x, submitted.y, z), Math.abs(x) <= EPSILON, Math.abs(z) <= EPSILON);
    }

    private static float approachZero(final float value) {
        if (value < 0) {
            final float added = value + 0.05F;
            return added > 0 ? 0 : added > value ? added : value;
        }
        final float subtracted = value - 0.05F;
        return subtracted > value ? value : subtracted > 0 ? subtracted : 0;
    }

    private static Box inset(final Box box) {
        float minX = box.minX + 0.025F, maxX = box.maxX - 0.025F;
        float minZ = box.minZ + 0.025F, maxZ = box.maxZ - 0.025F;
        if (minX > maxX) minX = maxX = (box.minX + box.maxX) / 2F;
        if (minZ > maxZ) minZ = maxZ = (box.minZ + box.maxZ) / 2F;
        return new Box(minX, box.minY, minZ, maxX, box.maxY, maxZ);
    }

    private static boolean supported(final Box inset, final List<Box> collisions,
                                     final float x, final float z, final float y) {
        final Box shifted = new Box(inset.minX + x, inset.minY + y, inset.minZ + z,
                inset.maxX + x, inset.maxY + y, inset.maxZ + z);
        for (final Box collision : collisions) if (collision.intersects(shifted)) return true;
        return false;
    }
}
