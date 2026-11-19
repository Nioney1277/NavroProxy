package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.math.Vec3;


public final class ProtocolRiptidePrediction {
    private static final float DEGREES_TO_RADIANS = 0.017453292F;
    private static final float NORMALIZATION_EPSILON = 0.0001F;
    private ProtocolRiptidePrediction() {}

    public static Vec3 impulse(Vec3 velocity, float pitch, float yaw, int level,
                               boolean onGround, boolean wasInWater, boolean headInWater) {
        if (level <= 0) return velocity;
        float pitchRadians=pitch*DEGREES_TO_RADIANS,yawRadians=yaw*DEGREES_TO_RADIANS;
        float negativeSinYaw=-BdsTrigMath.sin(yawRadians),cosPitch=BdsTrigMath.cos(pitchRadians);
        float x=negativeSinYaw*cosPitch,sinPitch=BdsTrigMath.sin(pitchRadians),z=cosPitch*BdsTrigMath.cos(yawRadians);
        float length=(float)Math.sqrt(z*z+sinPitch*sinPitch+x*x);
        float y;
        if(length<NORMALIZATION_EPSILON){x=y=z=0.0F;}else{x/=length;y=-sinPitch/length;z/=length;}
        float magnitude=(level+1.0F)*0.25F*3.0F;
        x*=magnitude;y*=magnitude;z*=magnitude;
        if(onGround){if(!(wasInWater&&!headInWater)){y+=0.08F;}else{y/=0.8F;y*=0.98F;}}
        return new Vec3(x+velocity.x,y+velocity.y,z+velocity.z);
    }
}
