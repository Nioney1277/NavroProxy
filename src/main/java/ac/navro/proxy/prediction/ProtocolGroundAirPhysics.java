package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Vec3;


public final class ProtocolGroundAirPhysics {
    private static final float SLOW_BLOCK_MULTIPLIER = 1.225F;
    private static final float FRICTION_SCALE = 0.91F;
    private static final float DEFAULT_DAMPING = 0.54600006F;
    private ProtocolGroundAirPhysics() {}

    public static float movementSpeed(float speed, float friction,
                                      boolean soulSand, boolean soulSpeed) {
        float adjusted = friction;
        if (soulSand && !soulSpeed) adjusted *= SLOW_BLOCK_MULTIPLIER;
        float damping = adjusted * FRICTION_SCALE;
        if (damping == 0.0F) damping = DEFAULT_DAMPING;
        float ratio = DEFAULT_DAMPING / damping;
        float squared = ratio * ratio;
        float cubed = squared * ratio;
        return speed * cubed;
    }

    public static float airSpeed(boolean sprinting) {
        return sprinting ? 0.026F : 0.02F;
    }

    public static Vec3 applyGravity(Vec3 velocity, float gravity) {
        return new Vec3(velocity.x, velocity.y + gravity, velocity.z);
    }

    public static Vec3 applyDrag(Vec3 velocity, float slipperiness) {
        float horizontal = slipperiness * 0.91F;
        return velocity.multiply(horizontal, 0.98F, horizontal);
    }
}
