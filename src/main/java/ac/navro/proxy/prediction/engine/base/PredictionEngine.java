package ac.navro.proxy.prediction.engine.base;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.prediction.ClimbableBlockPhysics;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public abstract class PredictionEngine {
    protected final PredictionContext player;
    protected PredictionEngine(PredictionContext player){this.player=player;}

    public abstract Vec3 travel(Vec3 vec3);
    public abstract void finalizeMovement();

    protected final Vec3 moveRelative(Vec3 delta, float f) {
        return delta.add(MathUtil.getInputVector(player.input, f, player.yaw));
    }

    protected final Vec3 applyClimbingSpeed(final Vec3 motion) {
        return ClimbableBlockPhysics.clampDownwardMovement(player, motion);
    }
}
