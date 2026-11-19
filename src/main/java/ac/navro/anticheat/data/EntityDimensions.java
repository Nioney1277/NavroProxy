package ac.navro.anticheat.data;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public record EntityDimensions(float width, float height, float eyeHeight, boolean fixed) {
    private EntityDimensions(float width, float height, boolean fixed) {
        this(width, height, height * 0.85F, fixed);
    }

    public Box getBoxAt(Vec3 position) {
        return getBoxAt(position.x, position.y, position.z);
    }

    public Box getBoxAt(float x, float y, float z) {
        float halfWidth = width / 2.0F;
        return new Box(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
    }

    public EntityDimensions hardScaled(float ratio) {
        return new EntityDimensions(width * ratio, height * ratio, eyeHeight, false);
    }

    public EntityDimensions scaled(float ratio) {
        return scaled(ratio, ratio);
    }

    public EntityDimensions scaled(float widthRatio, float heightRatio) {
        return !fixed && (widthRatio != 1.0F || heightRatio != 1.0F)
                ? new EntityDimensions(width * widthRatio, height * heightRatio, eyeHeight * heightRatio, false)
                : this;
    }

    public static EntityDimensions changing(float width, float height) {
        return new EntityDimensions(width, height, false);
    }

    public static EntityDimensions fixed(float width, float height) {
        return new EntityDimensions(width, height, true);
    }

    public EntityDimensions withEyeHeight(float value) {
        return new EntityDimensions(width, height, value, fixed);
    }
}
