package ac.navro.proxy.prediction.engine.impl;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.prediction.engine.base.PredictionEngine;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.MathUtil;

public class GlidingPredictionEngine extends PredictionEngine {
    private static final float PI = (float) Math.PI;
    private static final float LOOK_LENGTH_SCALE = 0.4F;
    private static final float LIFT_FACTOR = 0.75F;
    private static final float FALL_CONVERSION = -0.1F;
    private static final float DIVE_CONVERSION = 0.04F;
    private static final float DIVE_VERTICAL_FACTOR = 3.2F;
    private static final float STEERING_FACTOR = 0.1F;
    private static final float BOOST_TARGET_SPEED = 1.5F;
    private static final float BOOST_BLEND = 0.5F;
    private static final float BOOST_FORWARD = 0.1F;
    private static final float HORIZONTAL_DRAG = 0.99F;
    private static final float VERTICAL_DRAG = 0.98F;

    public GlidingPredictionEngine(final PredictionContext player) {
        super(player);
    }

    @Override
    public Vec3 travel(final Vec3 movement) {

        final float pitch = unwrapDegrees(player.prevPitch, player.pitch);
        final float yaw = unwrapDegrees(player.prevYaw, player.yaw);
        final float pitchRadians = pitch * MathUtil.DEGREE_TO_RAD;
        final float negativePitchRadians = pitch * -MathUtil.DEGREE_TO_RAD;
        final float shiftedYawRadians = yaw * -MathUtil.DEGREE_TO_RAD - PI;
        final float negativePitchCos = -BdsTrigMath.cos(negativePitchRadians);
        final Vec3 look = new Vec3(
                negativePitchCos * BdsTrigMath.sin(shiftedYawRadians),
                BdsTrigMath.sin(negativePitchRadians),
                negativePitchCos * BdsTrigMath.cos(shiftedYawRadians)
        );


        final Vec3 velocity = movement.clone();
        final float horizontalLook = (float) Math.sqrt(
                look.x * look.x + look.z * look.z);
        final float horizontalSpeed = (float) Math.sqrt(
                velocity.x * velocity.x + velocity.z * velocity.z);
        final float lookLength = (float) Math.sqrt(
                look.x * look.x + look.y * look.y + look.z * look.z);
        final float pitchCos = BdsTrigMath.cos(pitchRadians);
        final float liftShape = pitchCos * pitchCos
                * Math.min(1.0F, lookLength / LOOK_LENGTH_SCALE);
        final float gravity = player.getEffectiveGravity(movement);
        velocity.y += gravity * (LIFT_FACTOR * liftShape - 1.0F);

        if (velocity.y < 0.0F && horizontalLook > 0.0F) {
            final float converted = velocity.y * FALL_CONVERSION * liftShape;
            velocity.x += look.x * converted / horizontalLook;
            velocity.y += converted;
            velocity.z += look.z * converted / horizontalLook;
        }

        if (pitchRadians < 0.0F && horizontalLook > 0.0F) {
            final float converted = horizontalSpeed
                    * -BdsTrigMath.sin(pitchRadians)
                    * DIVE_CONVERSION;
            velocity.x -= look.x * converted / horizontalLook;
            velocity.y += converted * DIVE_VERTICAL_FACTOR;
            velocity.z -= look.z * converted / horizontalLook;
        }

        if (horizontalLook > 0.0F) {
            velocity.x += (look.x / horizontalLook * horizontalSpeed
                    - velocity.x) * STEERING_FACTOR;
            velocity.z += (look.z / horizontalLook * horizontalSpeed
                    - velocity.z) * STEERING_FACTOR;
        }

        if (player.glideBoostTicks > 0) {
            velocity.x += (look.x * BOOST_TARGET_SPEED - velocity.x)
                    * BOOST_BLEND + look.x * BOOST_FORWARD;
            velocity.y += (look.y * BOOST_TARGET_SPEED - velocity.y)
                    * BOOST_BLEND + look.y * BOOST_FORWARD;
            velocity.z += (look.z * BOOST_TARGET_SPEED - velocity.z)
                    * BOOST_BLEND + look.z * BOOST_FORWARD;
        }

        velocity.x *= HORIZONTAL_DRAG;
        velocity.y *= VERTICAL_DRAG;
        velocity.z *= HORIZONTAL_DRAG;

        return velocity;
    }

    private static float unwrapDegrees(final float previous, final float current) {
        return previous + positiveModulo(current - previous + 180.0F, 360.0F)
                - 180.0F;
    }

    private static float positiveModulo(final float value, final float modulus) {
        final float remainder = value % modulus;
        return remainder < 0.0F ? remainder + modulus : remainder;
    }

    @Override
    public void finalizeMovement() {}
}
