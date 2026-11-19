package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;






public final class ProtocolGroundAirPrediction {
    private ProtocolGroundAirPrediction() {}

    public static Vec3 travel(Vec3 velocity, Vec3 input, float baseSpeed,
                               float slipperiness, boolean onGround,
                               boolean sprinting, boolean soulSand,
                               boolean soulSpeed, float yaw) {
        float speed = onGround
                ? ProtocolGroundAirPhysics.movementSpeed(baseSpeed, slipperiness, soulSand, soulSpeed)
                : ProtocolGroundAirPhysics.airSpeed(sprinting);
        return velocity.add(MathUtil.getInputVector(input, speed, yaw));
    }

    public static Vec3 jump(Vec3 velocity, boolean flying, boolean onGround,
                            boolean startJumping, int climbJumpDelay,
                            float blockJumpFactor, Integer jumpBoostAmplifier,
                            boolean sprinting, float yaw) {
        if (flying || !onGround || !startJumping || climbJumpDelay != 0) return velocity;
        float jumpBoost = jumpBoostAmplifier == null
                ? 0.0F : 0.1F * (jumpBoostAmplifier + 1.0F);
        float jumpPower = 0.42F * blockJumpFactor + jumpBoost;
        if (jumpPower <= 1.0E-5F) return velocity;
        Vec3 result = new Vec3(velocity.x, Math.max(jumpPower, velocity.y), velocity.z);
        return sprinting ? applySprintJump(result, yaw) : result;
    }

    public static Vec3 applySprintJump(Vec3 velocity, float yaw) {
        float radians = yaw * MathUtil.DEGREE_TO_RAD;
        return velocity.add(-BdsTrigMath.sin(radians) * 0.2F, 0,
                BdsTrigMath.cos(radians) * 0.2F);
    }

    public static Vec3 finalizeMovement(Vec3 velocity, Integer levitationAmplifier,
                                        float gravityDelta, float slipperiness) {
        Vec3 next = velocity;
        if (levitationAmplifier != null) {
            float target = 0.05F * (levitationAmplifier + 1);
            next = new Vec3(next.x, next.y + (target - next.y) * 0.2F, next.z);
        } else {
            next = new Vec3(next.x, next.y + gravityDelta, next.z);
        }
        next = new Vec3(next.x, next.y * 0.98F, next.z);
        float horizontal = slipperiness * 0.91F;
        return next.multiply(horizontal, 1.0F, horizontal);
    }
}
