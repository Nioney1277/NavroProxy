package ac.navro.anticheat.util.math;


public class Vec3 implements Cloneable {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public float x, y, z;

    public Vec3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    public Vec3(double x, double y, double z) { this((float) x, (float) y, (float) z); }
    public Vec3(org.cloudburstmc.math.vector.Vector3f value) { this(value.getX(), value.getY(), value.getZ()); }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float distToCenterSqr(Vec3 value) { return distToCenterSqr(value.x, value.y, value.z); }
    public float distToCenterSqr(float d, float e, float f) {
        float g = x + 0.5F - d, h = y + 0.5F - e, i = z + 0.5F - f;
        return g * g + h * h + i * i;
    }
    public org.cloudburstmc.math.vector.Vector3f toVector3f() { return org.cloudburstmc.math.vector.Vector3f.from(x, y, z); }
    public float squaredDistanceTo(Vec3 vec) { float d = vec.x - x, e = vec.y - y, f = vec.z - z; return d*d + e*e + f*f; }
    public float distanceTo(Vec3 vec) { return (float) Math.sqrt(squaredDistanceTo(vec)); }
    public float horizontalLength() { return (float) Math.sqrt(horizontalLengthSquared()); }
    public float horizontalLengthSquared() { return x*x + z*z; }
    public float lengthSquared() { return x*x + y*y + z*z; }
    public float length() { return (float) Math.sqrt(lengthSquared()); }
    public Vec3 add(float v) { return add(v, v, v); }
    public Vec3 add(Vec3 v) { return add(v.x, v.y, v.z); }
    public Vec3 add(float a, float b, float c) { return new Vec3(x+a, y+b, z+c); }
    public Vec3 add(double a, double b, double c) { return add((float)a, (float)b, (float)c); }
    public Vec3 subtract(Vec3 v) { return subtract(v.x, v.y, v.z); }
    public Vec3 subtract(float a, float b, float c) { return new Vec3(x-a, y-b, z-c); }
    public Vec3 subtract(double a, double b, double c) { return subtract((float)a, (float)b, (float)c); }
    public Vec3 multiply(float a) { return multiply(a, a, a); }
    public Vec3 multiply(float a, float b, float c) { return new Vec3(x*a, y*b, z*c); }
    public Vec3 multiply(double a, double b, double c) { return multiply((float)a, (float)b, (float)c); }
    public Vec3 multiply(Vec3 v) { return multiply(v.x, v.y, v.z); }
    public Vec3 divide(float a) { return divide(a, a, a); }
    public Vec3 divide(float a, float b, float c) { return new Vec3(x/a, y/b, z/c); }
    public Vec3 up(float a) { return new Vec3(x, y+a, z); }
    public Vec3 down(float a) { return new Vec3(x, y-a, z); }
    public Vec3 north(float a) { return new Vec3(x, y, z-a); }
    public Vec3 south(float a) { return new Vec3(x, y, z+a); }
    public Vec3 east(float a) { return new Vec3(x+a, y, z); }
    public Vec3 west(float a) { return new Vec3(x-a, y, z); }
    public Vec3 normalize() {
        float length = length();
        return Math.abs(length) < 1.1920929e-07F ? ZERO : new Vec3(x/length, y/length, z/length);
    }
    public String horizontalToString() { return "(" + x + ", " + z + ")"; }
    @Override public Vec3 clone() { return new Vec3(x, y, z); }
    @Override public String toString() { return "Vec3[" + x + ", " + y + ", " + z + "]"; }
}
