package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.anticheat.util.math.Vec3;

import java.util.List;





public final class SweptMovement {
    private static final float CONTACT_EPSILON = Box.COLLISION_EPSILON;

    private SweptMovement() {
    }

    public static final class MoveResult {
        public final Vec3 movement;
        public final float overlapDepth;
        public final Box aabb;

        MoveResult(Vec3 movement, float overlapDepth, Box aabb) {
            this.movement = movement;
            this.overlapDepth = overlapDepth;
            this.aabb = aabb;
        }
    }

    public static MoveResult computeYXZMoveWithDepenetration(
            Vec3 movement, Box movingAABB, List<Box> collisionShapes,
            Vec3 maxDepenetration) {
        return computeYXZMoveWithDepenetration(movement, movingAABB, collisionShapes, maxDepenetration, false);
    }

    public static MoveResult computeYXZMoveWithDepenetration(
            Vec3 movement, Box movingAABB, List<Box> collisionShapes,
            Vec3 maxDepenetration, boolean resolveOverlaps) {
        return computeMoveWithDepenetration(new Vec3[]{
                new Vec3(0.0F, movement.y, 0.0F),
                new Vec3(movement.x, 0.0F, 0.0F),
                new Vec3(0.0F, 0.0F, movement.z)
        }, movingAABB, collisionShapes, maxDepenetration, resolveOverlaps);
    }

    public static MoveResult computeMoveWithDepenetration(
            Vec3[] movementSegments, Box movingAABB,
            List<Box> collisionShapes, Vec3 maxDepenetration) {
        return computeMoveWithDepenetration(movementSegments, movingAABB, collisionShapes, maxDepenetration, false);
    }





    public static MoveResult computeMoveWithDepenetration(
            Vec3[] movementSegments, Box movingAABB,
            List<Box> collisionShapes, Vec3 maxDepenetration, boolean resolveOverlaps) {
        Vec3 totalMovement = Vec3.ZERO.clone();
        float overlapDepth = 0.0F;
        for (Vec3 input : movementSegments) {
            if (input == null) continue;
            Vec3 segment = input.clone();
            for (int index = collisionShapes.size() - 1; index >= 0; index--) {
                ClipCollideResult clip = clipCollide(collisionShapes.get(index), movingAABB, segment);
                overlapDepth += clip.overlapDepth;
                segment = resolveOverlaps || component(maxDepenetration, clip.axis) >= clip.overlapDepth
                        ? clip.depenetratedMovement : clip.movement;
            }
            totalMovement.x += segment.x;
            totalMovement.y += segment.y;
            totalMovement.z += segment.z;
            movingAABB = movingAABB.offset(segment);
        }
        return new MoveResult(totalMovement, overlapDepth, movingAABB);
    }

    private static ClipCollideResult clipCollide(Box stationary, Box moving, Vec3 velocity) {
        Vec3 clipped = velocity.clone();
        Vec3 depenetrated = velocity.clone();
        if (!valid(stationary)) return new ClipCollideResult(0.0F, clipped, depenetrated, Direction.Axis.X);

        AxisDistance x = axisDistance(stationary.minX, stationary.maxX, moving.minX, moving.maxX);
        AxisDistance y = axisDistance(stationary.minY, stationary.maxY, moving.minY, moving.maxY);
        AxisDistance z = axisDistance(stationary.minZ, stationary.maxZ, moving.minZ, moving.maxZ);
        int separatedAxes = (x.overlapping ? 0 : 1) + (y.overlapping ? 0 : 1) + (z.overlapping ? 0 : 1);
        if (separatedAxes > 1) return new ClipCollideResult(0.0F, clipped, depenetrated, Direction.Axis.X);

        if (separatedAxes == 1) {
            Direction.Axis axis;
            AxisDistance distance;
            if (!x.overlapping) { axis = Direction.Axis.X; distance = x; }
            else if (!y.overlapping) { axis = Direction.Axis.Y; distance = y; }
            else { axis = Direction.Axis.Z; distance = z; }
            float requested = component(velocity, axis);
            if (!(distance.distance - requested * distance.direction > 0.0F)) {
                return new ClipCollideResult(0.0F, clipped, depenetrated, Direction.Axis.X);
            }
            float limited = distance.distance * distance.direction;
            setComponent(clipped, axis, limited);
            setComponent(depenetrated, axis, limited);
            return new ClipCollideResult(0.0F, clipped, depenetrated, axis);
        }

        Direction.Axis axis = Direction.Axis.X;
        AxisDistance selected = x;
        if (y.distance < selected.distance) { axis = Direction.Axis.Y; selected = y; }
        if (z.distance < selected.distance) { axis = Direction.Axis.Z; selected = z; }
        float resolution = selected.distance * selected.direction;
        float requested = component(depenetrated, axis);
        setComponent(depenetrated, axis, resolution > 0.0F
                ? Math.max(requested, resolution) : Math.min(requested, resolution));
        return new ClipCollideResult(selected.distance, clipped, depenetrated, axis);
    }

    private static AxisDistance axisDistance(float stationaryMin, float stationaryMax,
                                              float movingMin, float movingMax) {
        float negativeDistance = normalize(movingMax - stationaryMin);
        float negativeDepth = Math.max(negativeDistance, 0.0F);
        if (negativeDepth == 0.0F) return new AxisDistance(negativeDistance, -1.0F, false);
        float positiveDistance = normalize(stationaryMax - movingMin);
        float positiveDepth = Math.max(positiveDistance, 0.0F);
        if (positiveDepth == 0.0F) return new AxisDistance(positiveDistance, 1.0F, false);
        return negativeDepth < positiveDepth
                ? new AxisDistance(negativeDepth, -1.0F, true)
                : new AxisDistance(positiveDepth, 1.0F, true);
    }

    private static float normalize(float value) {
        return Math.abs(value) <= CONTACT_EPSILON ? 0.0F : value;
    }

    private static boolean valid(Box value) {
        return value != null && value.minX < value.maxX && value.minY < value.maxY && value.minZ < value.maxZ;
    }

    private static float component(Vec3 vector, Direction.Axis axis) {
        return switch (axis) { case X -> vector.x; case Y -> vector.y; case Z -> vector.z; };
    }

    private static void setComponent(Vec3 vector, Direction.Axis axis, float value) {
        switch (axis) { case X -> vector.x = value; case Y -> vector.y = value; case Z -> vector.z = value; }
    }

    private record AxisDistance(float distance, float direction, boolean overlapping) {}
}
