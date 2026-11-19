package ac.navro.anticheat.util;

import ac.navro.anticheat.util.math.Vec3;


public final class MathUtil {
    public static final float DEGREE_TO_RAD = 0.017453292F;
    private MathUtil() {}
    public static boolean isValid(org.cloudburstmc.math.vector.Vector3f value) {
        return Float.isFinite(value.getX()) && Float.isFinite(value.getY()) && Float.isFinite(value.getZ());
    }
    public static boolean isValid(org.cloudburstmc.math.vector.Vector2f value) {
        return Float.isFinite(value.getX()) && Float.isFinite(value.getY());
    }
    public static boolean sameDirection(Vec3 a, Vec3 b) { return sign(a.x)==sign(b.x)&&sign(a.y)==sign(b.y)&&sign(a.z)==sign(b.z); }
    public static boolean sameDirectionHorizontal(Vec3 a, Vec3 b) { return sign(a.x)==sign(b.x)&&sign(a.z)==sign(b.z); }
    public static Vec3 signAll(Vec3 v) { return new Vec3(sign(v.x),sign(v.y),sign(v.z)); }
    public static int sign(double value) { if(!Double.isFinite(value)) return 0; return value==0?0:value>0?1:-1; }
    public static float wrapDegrees(float f) { float g=f%360F; if(g>=180F)g-=360F; if(g<-180F)g+=360F; return g; }
    public static boolean equal(double a,double b) { return Math.abs(b-a)<1.0E-7D; }
    public static float lerp(float d,float start,float end){return start+d*(end-start);} public static double lerp(double d,double start,double end){return start+d*(end-start);}
    public static float square(float v){return v*v;}
    public static float clamp(float value,float min,float max){if(Float.isNaN(value)||Float.isInfinite(value))return 0;return value<min?min:Math.min(value,max);}
    public static float sqrt(float value){return (float)Math.sqrt(value);}
    public static Vec3 getRotationVector(float pitch,float yaw){
        final float negativePitch=-pitch*DEGREE_TO_RAD; final float shiftedYaw=-yaw*DEGREE_TO_RAD-3.1415927F;
        final float horizontal=-BdsTrigMath.cos(negativePitch);
        return new Vec3(BdsTrigMath.sin(shiftedYaw)*horizontal,BdsTrigMath.sin(negativePitch),BdsTrigMath.cos(shiftedYaw)*horizontal);
    }
    public static Vec3 getInputVector(Vec3 movementInput,float speed,float yaw){
        final float squared=movementInput.x*movementInput.x+movementInput.y*movementInput.y+movementInput.z*movementInput.z;
        if(squared<0.01F*0.01F)return Vec3.ZERO;
        final float length=(float)Math.sqrt(squared),scale=speed/Math.max(length,1F);
        final float localX=movementInput.x*scale,localY=movementInput.y*scale,localZ=movementInput.z*scale;
        final float radians=yaw*DEGREE_TO_RAD,sin=ClientFloatMath.sinf(radians),cos=ClientFloatMath.cosf(radians);
        return new Vec3(localX*cos-localZ*sin,localY,localZ*cos+localX*sin);
    }
    public static int blockPosition(int x,int y,int z){return (x<<8)|(z<<4)|y;}
    public static long chunkPositionToLong(int x,int z){return (x&0xFFFFFFFFL)|((z&0xFFFFFFFFL)<<32L);}
}
