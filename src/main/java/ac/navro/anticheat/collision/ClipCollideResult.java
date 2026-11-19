package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Direction;
import ac.navro.anticheat.util.math.Vec3;


final class ClipCollideResult {
    final float overlapDepth;
    final Vec3 movement;
    final Vec3 depenetratedMovement;
    final Direction.Axis axis;

    ClipCollideResult(float overlapDepth, Vec3 movement, Vec3 depenetratedMovement,
                      Direction.Axis axis) {
        this.overlapDepth = overlapDepth;
        this.movement = movement;
        this.depenetratedMovement = depenetratedMovement;
        this.axis = axis;
    }
}
