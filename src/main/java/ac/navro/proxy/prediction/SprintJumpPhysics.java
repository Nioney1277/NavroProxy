package ac.navro.proxy.prediction;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public final class SprintJumpPhysics {
    private SprintJumpPhysics() { }

    public static Vec3 apply(final Vec3 velocity, final float yaw) {
        final float radians = yaw * MathUtil.DEGREE_TO_RAD;
        return velocity.add(-BdsTrigMath.sin(radians) * 0.2F, 0,
                BdsTrigMath.cos(radians) * 0.2F);
    }
}
