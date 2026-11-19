package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public final class ProtocolGlidingPrediction {
    private static final float PI = (float) Math.PI, LOOK_LENGTH_SCALE = 0.4F, LIFT_FACTOR = 0.75F;
    private static final float FALL_CONVERSION = -0.1F, DIVE_CONVERSION = 0.04F, DIVE_VERTICAL_FACTOR = 3.2F;
    private static final float STEERING_FACTOR = 0.1F, BOOST_TARGET_SPEED = 1.5F, BOOST_BLEND = 0.5F;
    private static final float BOOST_FORWARD = 0.1F, HORIZONTAL_DRAG = 0.99F, VERTICAL_DRAG = 0.98F;
    private ProtocolGlidingPrediction() {}

    public static Vec3 travel(Vec3 movement, float previousPitch, float pitch, float previousYaw,
                              float yaw, float gravity, int glideBoostTicks) {
        float unwrappedPitch = unwrap(previousPitch, pitch), unwrappedYaw = unwrap(previousYaw, yaw);
        float pitchRadians = unwrappedPitch * MathUtil.DEGREE_TO_RAD;
        float negativePitchRadians = unwrappedPitch * -MathUtil.DEGREE_TO_RAD;
        float shiftedYawRadians = unwrappedYaw * -MathUtil.DEGREE_TO_RAD - PI;
        float negativePitchCos = -BdsTrigMath.cos(negativePitchRadians);
        Vec3 look = new Vec3(negativePitchCos * BdsTrigMath.sin(shiftedYawRadians),
                BdsTrigMath.sin(negativePitchRadians), negativePitchCos * BdsTrigMath.cos(shiftedYawRadians));
        Vec3 velocity = movement.clone();
        float horizontalLook = (float)Math.sqrt(look.x*look.x + look.z*look.z);
        float horizontalSpeed = (float)Math.sqrt(velocity.x*velocity.x + velocity.z*velocity.z);
        float lookLength = (float)Math.sqrt(look.x*look.x + look.y*look.y + look.z*look.z);
        float pitchCos = BdsTrigMath.cos(pitchRadians);
        float liftShape = pitchCos*pitchCos*Math.min(1.0F, lookLength/LOOK_LENGTH_SCALE);
        velocity.y += gravity * (LIFT_FACTOR*liftShape - 1.0F);
        if (velocity.y < 0 && horizontalLook > 0) {
            float converted=velocity.y*FALL_CONVERSION*liftShape;
            velocity.x += look.x*converted/horizontalLook; velocity.y += converted; velocity.z += look.z*converted/horizontalLook;
        }
        if (pitchRadians < 0 && horizontalLook > 0) {
            float converted=horizontalSpeed * -BdsTrigMath.sin(pitchRadians) * DIVE_CONVERSION;
            velocity.x -= look.x*converted/horizontalLook; velocity.y += converted*DIVE_VERTICAL_FACTOR; velocity.z -= look.z*converted/horizontalLook;
        }
        if (horizontalLook > 0) {
            velocity.x += (look.x/horizontalLook*horizontalSpeed - velocity.x)*STEERING_FACTOR;
            velocity.z += (look.z/horizontalLook*horizontalSpeed - velocity.z)*STEERING_FACTOR;
        }
        if (glideBoostTicks > 0) {
            velocity.x += (look.x*BOOST_TARGET_SPEED-velocity.x)*BOOST_BLEND + look.x*BOOST_FORWARD;
            velocity.y += (look.y*BOOST_TARGET_SPEED-velocity.y)*BOOST_BLEND + look.y*BOOST_FORWARD;
            velocity.z += (look.z*BOOST_TARGET_SPEED-velocity.z)*BOOST_BLEND + look.z*BOOST_FORWARD;
        }
        return velocity.multiply(HORIZONTAL_DRAG, VERTICAL_DRAG, HORIZONTAL_DRAG);
    }

    private static float unwrap(float previous, float current) {
        float remainder=(current-previous+180.0F)%360.0F;
        if (remainder < 0) remainder += 360.0F;
        return previous + remainder - 180.0F;
    }
}
