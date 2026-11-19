package ac.navro.proxy.state;


public record WireVec2(float x, float y) {
    public static WireVec2 of(org.cloudburstmc.math.vector.Vector2f value) {
        return value == null ? null : new WireVec2(value.getX(), value.getY());
    }

    public boolean finite() {
        return Float.isFinite(x) && Float.isFinite(y);
    }
}
