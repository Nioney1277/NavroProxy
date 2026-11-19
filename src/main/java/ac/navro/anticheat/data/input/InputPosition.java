package ac.navro.anticheat.data.input;

import ac.navro.anticheat.util.math.Vec3;


public record InputPosition(float x, float y, float z, float yOffset) {
    private static final long FIRST_FINITE = 0x00800000L;
    private static final long LAST_FINITE = 0xff7fffffL;

    public static InputPosition capture(org.cloudburstmc.math.vector.Vector3f wire, float yOffset) {
        return new InputPosition(wire.getX(), wire.getY(), wire.getZ(), yOffset);
    }

    public Vec3 decoded() { return new Vec3(x, y - yOffset, z); }

    public Vec3 residual(Vec3 physical) {
        return new Vec3(x - physical.x, y - (physical.y + yOffset), z - physical.z);
    }

    public float squaredDistance(Vec3 physical) { return residual(physical).lengthSquared(); }
    public float distance(Vec3 physical) { return residual(physical).length(); }

    public Vec3 physicalTarget(Vec3 physical) {
        return new Vec3(x, physicalY(physical.y), z);
    }

    private float physicalY(float predicted) {
        if (predicted + yOffset == y) return predicted;
        if (!Float.isFinite(predicted) || !Float.isFinite(y) || !Float.isFinite(yOffset)) return y - yOffset;
        if (yOffset == 0) return y;
        long first = boundary(false), last = boundary(true) - 1;
        if (first > last) return y - yOffset;
        return value(Math.max(first, Math.min(last, key(predicted))));
    }

    private long boundary(boolean strict) {
        long low = FIRST_FINITE, high = LAST_FINITE + 1;
        while (low < high) {
            long middle = (low + high) >>> 1;
            float encoded = value(middle) + yOffset;
            if (encoded < y || strict && encoded == y) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static long key(float value) {
        long bits = Integer.toUnsignedLong(Float.floatToRawIntBits(value));
        return (bits & 0x80000000L) != 0 ? (~bits) & 0xffffffffL : bits ^ 0x80000000L;
    }

    private static float value(long key) {
        return Float.intBitsToFloat((int) (key < 0x80000000L ? ~key : key ^ 0x80000000L));
    }
}
