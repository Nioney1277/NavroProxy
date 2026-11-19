package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;

import java.util.List;


public final class ProtocolCollisionSolver {
    private ProtocolCollisionSolver() {}

    public static SweptMovement.MoveResult solve(Vec3 movement, Box actor,
                                                 List<Box> shapes, Vec3 maxDepenetration,
                                                 boolean yxz) {
        Vec3 submitted = CollisionQuery.clamp(movement);
        if (yxz) return SweptMovement.computeYXZMoveWithDepenetration(submitted, actor, shapes, maxDepenetration);
        return SweptMovement.computeMoveWithDepenetration(new Vec3[]{submitted}, actor, shapes, maxDepenetration);
    }


    public static SweptMovement.MoveResult solveResolved(Vec3 movement, Box actor,
                                                         List<Box> shapes, Vec3 maxDepenetration,
                                                         float step, boolean canStep) {
        return solveResolvedDetailed(movement, actor, shapes, maxDepenetration, step, canStep).result();
    }

    public static SweptMovement.MoveResult solveResolved(Vec3 movement, Box actor,
                                                         List<Box> shapes, Vec3 maxDepenetration,
                                                         float step, boolean canStep, boolean resolveOverlaps) {
        return solveResolvedDetailed(movement, actor, shapes, maxDepenetration, step, canStep, resolveOverlaps).result();
    }

    public record ResolvedMove(SweptMovement.MoveResult result, float ordinaryOverlapDepth) { }


    public static ResolvedMove solveResolvedDetailed(Vec3 movement, Box actor,
                                                     List<Box> shapes, Vec3 maxDepenetration,
                                                     float step, boolean canStep) {
        return solveResolvedDetailed(movement, actor, shapes, maxDepenetration, step, canStep, false);
    }

    public static ResolvedMove solveResolvedDetailed(Vec3 movement, Box actor,
                                                     List<Box> shapes, Vec3 maxDepenetration,
                                                     float step, boolean canStep, boolean resolveOverlaps) {
        Vec3 submitted = CollisionQuery.clamp(movement);
        SweptMovement.MoveResult ordinary = SweptMovement.computeYXZMoveWithDepenetration(
                submitted, actor, shapes, maxDepenetration, resolveOverlaps);
        boolean horizontal = submitted.x != ordinary.movement.x || submitted.z != ordinary.movement.z;
        boolean downward = submitted.y < 0 && submitted.y != ordinary.movement.y;
        if (!(step > 0 && horizontal && (canStep || downward))) return new ResolvedMove(ordinary, ordinary.overlapDepth);
        List<Box> stepShapes = new java.util.ArrayList<>(shapes.size());
        for (Box shape : shapes) if (shape.minY < actor.maxY) stepShapes.add(shape);
        SweptMovement.MoveResult up = SweptMovement.computeMoveWithDepenetration(
                new Vec3[]{new Vec3(0, step, 0), new Vec3(submitted.x, 0, 0),
                        new Vec3(0, 0, submitted.z)}, actor, stepShapes, maxDepenetration, resolveOverlaps);
        SweptMovement.MoveResult down = SweptMovement.computeMoveWithDepenetration(
                new Vec3[]{new Vec3(0, -up.movement.y, 0)}, up.aabb, stepShapes, maxDepenetration, resolveOverlaps);
        Vec3 candidate = up.movement.add(down.movement);
        float overlapDepth = ordinary.overlapDepth + up.overlapDepth + down.overlapDepth;
        if (hasCollision(down.aabb, shapes) || !reasonable(candidate) || !reasonable(down.aabb)
                || candidate.horizontalLengthSquared() <= ordinary.movement.horizontalLengthSquared()) {
            return new ResolvedMove(new SweptMovement.MoveResult(ordinary.movement, overlapDepth, ordinary.aabb), ordinary.overlapDepth);
        }
        return new ResolvedMove(new SweptMovement.MoveResult(candidate, overlapDepth, down.aabb), ordinary.overlapDepth);
    }

    private static boolean hasCollision(Box box, List<Box> shapes) {
        for (int i = shapes.size() - 1; i >= 0; i--) if (box.intersects(shapes.get(i))) return true;
        return false;
    }

    private static boolean reasonable(Vec3 value) {
        return reasonable(value.x) && reasonable(value.y) && reasonable(value.z);
    }

    private static boolean reasonable(Box value) {
        return value.isValid() && reasonable(value.minX) && reasonable(value.minY)
                && reasonable(value.minZ) && reasonable(value.maxX)
                && reasonable(value.maxY) && reasonable(value.maxZ);
    }

    private static boolean reasonable(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 33554432.0F;
    }
}
