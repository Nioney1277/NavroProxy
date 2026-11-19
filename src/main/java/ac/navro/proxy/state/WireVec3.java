package ac.navro.proxy.state;


public record WireVec3(float x, float y, float z) {
    public static WireVec3 of(org.cloudburstmc.math.vector.Vector3f value) {
        return value == null ? null : new WireVec3(value.getX(), value.getY(), value.getZ());
    }

    public boolean finite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }
}
