package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.data.block.NavroBlockState;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.util.List;


public final class StickyBlockPhysics {
    private static final float EPSILON = 1.1920929E-7F;
    private static final float MIN_DOWNWARD_IMPACT = 0.08000011742115021F;

    private StickyBlockPhysics() { }

    public record GravityCorrection(float requestedY, float resolvedY) { }
    public record Restitution(Vec3 velocity, GravityCorrection gravityCorrection) { }

    public static Restitution restitute(Box actorBox, List<CollisionShapeEntry> entries,
                                       Vec3 velocity, Vec3 requested, Vec3 resolved,
                                       boolean collisionX, boolean collisionY, boolean collisionZ,
                                       boolean sneaking) {
        final Vec3 output = new Vec3(collisionX ? 0 : velocity.x,
                collisionY ? 0 : velocity.y, collisionZ ? 0 : velocity.z);
        float bounceY = 0;

        if (collisionY && !sneaking && velocity.y < 0 && Math.abs(velocity.y) >= MIN_DOWNWARD_IMPACT) {
            final CollisionShapeEntry support = selectRestitutionShape(actorBox, entries);
            final float coefficient = support == null || support.block() == null
                    ? 0 : new NavroBlockState(support.block()).getBlockBounciness();
            if (Float.isFinite(coefficient) && Math.abs(coefficient) > EPSILON
                    && coefficient != -1.0F) {
                bounceY = Math.max(0, -velocity.y * coefficient);
                output.y += bounceY;
            }
        }
        final GravityCorrection correction = Math.abs(bounceY) > EPSILON
                && Math.abs(resolved.y) > EPSILON
                ? new GravityCorrection(requested.y, resolved.y) : null;
        return new Restitution(output, correction);
    }

    public static CollisionShapeEntry selectRestitutionShape(Box actorBox,
                                                            List<CollisionShapeEntry> entries) {
        if (actorBox == null || !actorBox.isValid() || entries == null) return null;
        final float targetX = midpoint(actorBox.minX, actorBox.maxX);
        final float targetY = actorBox.minY - 0.20000000298023224F;
        final float targetZ = midpoint(actorBox.minZ, actorBox.maxZ);
        CollisionShapeEntry selected = null;
        float verticalDistance = Float.MAX_VALUE, distanceSquared = Float.MAX_VALUE;
        for (CollisionShapeEntry entry : entries) {
            if (entry == null || !entry.shape().isValid()) continue;
            final Box candidate = entry.shape();
            final float candidateVerticalDistance = targetY - midpoint(candidate.minY, candidate.maxY);
            if (candidateVerticalDistance < 0) continue;
            final float candidateDistanceSquared = restitutionDistanceSquared(targetX, targetY, targetZ, candidate);
            if (selected == null || candidateVerticalDistance < verticalDistance
                    || candidateVerticalDistance == verticalDistance && candidateDistanceSquared < distanceSquared) {
                selected = entry;
                verticalDistance = candidateVerticalDistance;
                distanceSquared = candidateDistanceSquared;
            }
        }
        return selected;
    }


    public static float resolveGravityDelta(PredictionContext player, float ordinaryGravityDelta) {
        final GravityCorrection correction = player.bounceGravityCorrection;
        player.bounceGravityCorrection = null;
        return resolveGravityDelta(correction, ordinaryGravityDelta);
    }

    public static float resolveGravityDelta(GravityCorrection correction, float ordinaryGravityDelta) {
        if (correction == null || Math.abs(correction.resolvedY()) <= EPSILON
                || Math.abs(ordinaryGravityDelta) <= EPSILON) return ordinaryGravityDelta;
        final float gravityMagnitude = Math.abs(ordinaryGravityDelta);
        final float requestedSquared = correction.requestedY() * correction.requestedY();
        final float radicand = (gravityMagnitude + gravityMagnitude)
                * Math.abs(correction.resolvedY()) + requestedSquared;
        final float impactSpeed = sqrtf(radicand);
        final float impactFraction = (Math.abs(correction.requestedY()) - impactSpeed) / ordinaryGravityDelta;
        final float remainingTick = 1.0F - Math.abs(impactFraction);
        final float sign = correction.requestedY() > 0 ? 1 : correction.requestedY() < 0 ? -1 : 0;
        return gravityMagnitude * remainingTick * sign;
    }

    public static void standOn(PredictionContext player) {
        applyStandOn(player.velocity, player.getFlagTracker().has("SNEAKING"));
    }

    public static void applyStandOn(Vec3 velocity, boolean sneaking) {
        if (sneaking || velocity.y >= 0.1F) return;
        final float scale = 0.4F + Math.abs(velocity.y) * 0.2F;
        velocity.x *= scale;
        velocity.z *= scale;
    }


    public static boolean insideHoney(Vec3 velocity, Vec3 position, float width, org.cloudburstmc.math.vector.Vector3i block) {
        velocity.x *= 0.4F;

        velocity.y = -0.12F > velocity.y ? -0.12F : velocity.y;
        velocity.z *= 0.4F;
        if (position.y > block.getY() + 0.9375F || velocity.y >= 0) return false;
        final float sideLimit = width * 0.5F + 0.4312500059604645F;
        return Math.abs(block.getX() + 0.5F - position.x) > sideLimit
                || Math.abs(block.getZ() + 0.5F - position.z) > sideLimit;
    }


    public static Box selectStandingShape(Box actor, List<Box> candidates) {
        final float targetX = (actor.minX + actor.maxX) * 0.5F;
        final float targetY = actor.minY - 0.2F;
        final float targetZ = (actor.minZ + actor.maxZ) * 0.5F;
        Box selected = null;
        float distance = Float.MAX_VALUE;
        for (Box candidate : candidates) {
            if (candidate == null || !candidate.isValid()
                    || !(actor.minX < candidate.maxX && candidate.minX < actor.maxX
                    && targetY < candidate.maxY && candidate.minY < targetY
                    && actor.minZ < candidate.maxZ && candidate.minZ < actor.maxZ)) continue;
            final float candidateDistance = distanceSquared(targetX, targetY, targetZ, candidate);
            if (selected == null || candidate.maxY > selected.maxY
                    || candidate.maxY == selected.maxY && candidateDistance < distance) {
                selected = candidate;
                distance = candidateDistance;
            }
        }
        return selected;
    }

    static float sqrtf(float value) {
        if (Float.isNaN(value) || value < 0) return Float.NaN;
        if (value == 0 || value == Float.POSITIVE_INFINITY) return value;
        float candidate = (float) StrictMath.sqrt((double) value);
        final double exactInput = value;
        while (true) {
            final float lower = Math.nextDown(candidate);
            final double lowerMidpoint = ((double) lower + candidate) * 0.5D;
            final boolean odd = (Float.floatToRawIntBits(candidate) & 1) != 0;
            final double lowerBoundary = lowerMidpoint * lowerMidpoint;
            if (exactInput < lowerBoundary || exactInput == lowerBoundary && odd) {
                candidate = lower;
                continue;
            }
            final float upper = Math.nextUp(candidate);
            final double upperMidpoint = ((double) candidate + upper) * 0.5D;
            final double upperBoundary = upperMidpoint * upperMidpoint;
            if (exactInput > upperBoundary || exactInput == upperBoundary && odd) {
                candidate = upper;
                continue;
            }
            return candidate;
        }
    }

    private static float restitutionDistanceSquared(float x, float y, float z, Box box) {
        final float dx = midpoint(box.minX, box.maxX) - x;
        final float dy = midpoint(box.minY, box.maxY) - y;
        final float dz = midpoint(box.minZ, box.maxZ) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float midpoint(float min, float max) { return (max - min) * 0.5F + min; }

    private static float distanceSquared(float x, float y, float z, Box box) {
        final float dx = (box.minX + box.maxX) * 0.5F - x;
        final float dy = (box.minY + box.maxY) * 0.5F - y;
        final float dz = (box.minZ + box.maxZ) * 0.5F - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
