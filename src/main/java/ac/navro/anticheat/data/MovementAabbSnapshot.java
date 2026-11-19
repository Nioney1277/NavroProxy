package ac.navro.anticheat.data;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public record MovementAabbSnapshot(EntityDimensions dimensions, Vec3 position, Box box, long resizeRevision) {
    public MovementAabbSnapshot {
        position = position.clone();
        box = box.clone();
    }

    @Override public Vec3 position() { return position.clone(); }
    @Override public Box box() { return box.clone(); }
    public Box at(Vec3 target) { return box.offset(target.subtract(position)); }
}
