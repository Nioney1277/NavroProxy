package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.cache.entity.EntityCache;

import java.util.Map;


public final class ProtocolEntityTolerance {
    private ProtocolEntityTolerance() {}

    public static Sample sample(Map<Long, EntityCache> entities, Vec3 verifiedPosition,
                                Box movementBox, long ridingId, boolean riding,
                                boolean enforceBoatStep, float actualY, float baseMotionY) {
        return sample(EntityCollisionPhysics.snapshotActors(entities), verifiedPosition, movementBox,
                ridingId, riding, enforceBoatStep, actualY, baseMotionY);
    }

    public static Sample sample(java.util.List<EntityCollisionPhysics.Actor> actors, Vec3 verifiedPosition,
                                Box movementBox, long ridingId, boolean riding,
                                boolean enforceBoatStep, float actualY, float baseMotionY) {
        var sample = EntityCollisionPhysics.sample(actors, verifiedPosition, movementBox, ridingId,
                riding, enforceBoatStep, actualY, baseMotionY);
        var t = sample.tolerance();
        return new Sample(new Tolerance(t.boat(), t.happyGhast(), t.x(), t.y(), t.z()), sample.enforceBoatStep());
    }

    public static float adjustedMotionOffset(Vec3 predicted, Vec3 actual, Tolerance tolerance) {
        float x=Math.max(0,Math.abs(actual.x-predicted.x)-tolerance.x());
        float y=Math.max(0,Math.abs(actual.y-predicted.y)-tolerance.y());
        float z=Math.max(0,Math.abs(actual.z-predicted.z)-tolerance.z());
        return Math.max(x,Math.max(y,z));
    }

    public static Vec3 clampAccepted(Vec3 predicted, Vec3 client, Tolerance tolerance) {
        if (tolerance == null || !tolerance.collided()) return predicted.clone();
        return new Vec3(predicted.x+clamp(client.x-predicted.x,tolerance.x()),
                predicted.y+clamp(client.y-predicted.y,tolerance.y()),
                predicted.z+clamp(client.z-predicted.z,tolerance.z()));
    }

    private static float clamp(float difference,float allowance) {
        if (!Float.isFinite(difference)) return 0;
        float limit=Math.max(0,allowance);
        return Math.max(-limit,Math.min(limit,difference));
    }

    public record Tolerance(boolean boat,boolean happyGhast,float x,float y,float z) {
        public boolean collided(){return boat||happyGhast;}
    }
    public record Sample(Tolerance tolerance,boolean enforceBoatStep) {}
}
