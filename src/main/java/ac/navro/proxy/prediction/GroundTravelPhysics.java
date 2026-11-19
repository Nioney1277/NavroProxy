package ac.navro.proxy.prediction;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;


public final class GroundTravelPhysics {
    private static final float SLOW_BLOCK_MULTIPLIER = 1.225F;
    private static final float FRICTION_SCALE = 0.91F;
    private static final float DEFAULT_DAMPING = 0.54600006F;

    private GroundTravelPhysics() { }

    public static org.cloudburstmc.math.vector.Vector3i frictionPosition(Vec3 position) {
        return org.cloudburstmc.math.vector.Vector3i.from(floor(position.x), floor(position.y - 0.1F), floor(position.z));
    }

    public static float movementSpeed(float speed, float friction, boolean soulSand, boolean soulSpeed) {
        float adjustedFriction = friction;
        if (soulSand && !soulSpeed) adjustedFriction *= SLOW_BLOCK_MULTIPLIER;
        float damping = adjustedFriction * FRICTION_SCALE;
        if (damping == 0.0F) damping = DEFAULT_DAMPING;
        final float ratio = DEFAULT_DAMPING / damping;
        final float ratioSquared = ratio * ratio;
        final float ratioCubed = ratioSquared * ratio;
        return speed * ratioCubed;
    }

    private static int floor(float value) {
        final int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
