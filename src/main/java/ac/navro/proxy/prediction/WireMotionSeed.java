package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Vec3;


public record WireMotionSeed(String source, long tick, Vec3 velocity, boolean authoritative) {
    public WireMotionSeed {
        velocity = velocity == null ? null : velocity.clone();
    }

    @Override public Vec3 velocity() { return velocity == null ? null : velocity.clone(); }
}
