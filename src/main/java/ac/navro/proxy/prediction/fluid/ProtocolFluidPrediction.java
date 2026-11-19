package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public final class ProtocolFluidPrediction {
    private ProtocolFluidPrediction() {}

    public static float nextSwimAmount(float amount, boolean horizontal) {
        return horizontal ? Math.min(1, amount + 0.1F) : Math.max(0, amount - 0.1F);
    }

    public record WaterParameters(float strength, float depthRatio, float horizontalDrag) {}

    public static WaterParameters waterParameters(int depthLevel, boolean onGround,
                                                   float movementSpeed, float underwaterSpeed,
                                                   float swimMultiplier, boolean sprinting,
                                                   float waterDrag) {
        float ratio = Math.min(Math.max(0, depthLevel), 3) / 3.0F;
        float strength;
        if (swimMultiplier > 1) {
            strength = underwaterSpeed * swimMultiplier * (0.7F + 0.3F * ratio);
        } else {
            if (!onGround) ratio *= 0.5F;
            strength = underwaterSpeed + (movementSpeed - underwaterSpeed) * ratio;
        }
        float baseDrag = sprinting ? 0.9F : Float.isFinite(waterDrag) ? waterDrag : 0.8F;
        float drag = swimMultiplier > 1 ? baseDrag : baseDrag + (0.54600006F - baseDrag) * ratio;
        return new WaterParameters(strength, ratio, drag);
    }

    public static Vec3 moveRelative(Vec3 velocity, Vec3 input, float strength, float yaw) {
        return input.lengthSquared() < 0.0001F ? velocity
                : velocity.add(MathUtil.getInputVector(input, strength, yaw));
    }

    public static Vec3 prepareVelocity(Vec3 velocity, Vec3 selectedPush,
                                       boolean flying, boolean wasInWater,
                                       boolean inLava, boolean wantDown) {
        Vec3 prepared = velocity.add(selectedPush);
        if (!flying && wasInWater && !inLava && wantDown) prepared = prepared.add(0, -0.04F, 0);
        return prepared;
    }

    public static Vec3 swimControl(Vec3 velocity, float targetY, boolean swimming,
                                   boolean jumping, boolean flying, boolean breathingLiquid,
                                   boolean breathingAir, boolean wantDownSlow) {
        if (!swimming || jumping) return velocity;
        if (targetY > 0 && !breathingLiquid && (!flying || breathingAir && !wantDownSlow)) {
            return new Vec3(velocity.x, 0, velocity.z);
        }
        float response = flying ? 1.3F : targetY < -0.2F ? 0.085F : 0.06F;
        return new Vec3(velocity.x, velocity.y + (targetY - velocity.y) * response, velocity.z);
    }

    public static Vec3 liquidJump(Vec3 velocity, boolean inWater, boolean touchingLava,
                                  boolean jumping, boolean flying) {
        if (flying || !jumping || !(inWater || touchingLava)) return velocity;
        return velocity.add(0, 0.04F, 0);
    }

    public static Vec3 swimmingJump(Vec3 velocity, boolean inWater, boolean swimming,
                                    boolean headInWater, float swimAmount) {
        if (!(swimming && !headInWater) && !(swimAmount > 0 && swimAmount < 1)) return null;
        return inWater ? new Vec3(velocity.x, 0, velocity.z) : velocity;
    }

    public static float lavaClimbVelocity(float vertical, boolean climbing,
                                          boolean sneaking, boolean climbHaltAtFeet) {
        if (!climbing) return vertical;
        vertical = Math.max(vertical, -0.2F);
        return vertical < 0 && sneaking && climbHaltAtFeet ? 0 : vertical;
    }
}
