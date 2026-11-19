package ac.navro.proxy.prediction.engine.impl.fluid;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.prediction.ClimbableBlockPhysics;
import ac.navro.proxy.prediction.StickyBlockPhysics;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;
import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;

public class LavaPredictionEngine extends PredictionEngine {
    public LavaPredictionEngine(PredictionContext player) { super(player); }

    @Override public Vec3 travel(Vec3 velocity) {
        final Vec3 moved = FluidMovement.moveRelative(velocity, player.input, player.getLavaSpeed(), player.yaw);
        final boolean halt = ClimbableBlockPhysics.hasClimbHaltAtFeet(player);
        return new Vec3(moved.x, FluidMovement.lavaClimbVelocity(moved.y, FluidMovement.isClimbing(player),
                player.getFlagTracker().has("SNEAKING"), halt), moved.z);
    }

    @Override public void finalizeMovement() {
        player.velocity = player.velocity.multiply(0.5F);
        final float gravity = player.getEffectiveGravity();
        if (gravity != 0) {
            player.velocity.y += StickyBlockPhysics.resolveGravityDelta(player, -gravity / 4.0F);
        }
        player.bounceGravityCorrection = null;
        player.fallDistance = 0;
    }
}
