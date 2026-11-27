package ac.navro.proxy.prediction.engine.impl;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.collision.ScaffoldingCollision;
import ac.navro.proxy.prediction.ClimbableBlockPhysics;
import ac.navro.proxy.prediction.StickyBlockPhysics;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.data.vanilla.StatusEffect;
import ac.navro.anticheat.util.math.Vec3;


public class GroundAndAirPredictionEngine extends PredictionEngine {
    private float prevSlipperiness = 0.6F;

    public GroundAndAirPredictionEngine(PredictionContext player) {
        super(player);
    }

    @Override
    public Vec3 travel(Vec3 vec3) {
        org.cloudburstmc.math.vector.Vector3i blockPos = player.getBlockPosBelowThatAffectsMyMovement();
        var surface = player.compensatedWorld.getBlockState(blockPos, 0);


        this.prevSlipperiness = player.onGround
                ? surface.isAir() && surface.getNetworkState().declaredSlipperiness() == null ? 0.6F : surface.getFriction()
                : 1.0F;

        final Vec3 result = this.halfRelativeMovementCalculate(vec3, this.prevSlipperiness);

        return result;
    }

    @Override
    public void finalizeMovement() {

        final StatusEffect effect = player.getEffect(NativeIds.Effect.LEVITATION);
        if (effect != null) {
            player.velocity.y += (0.05f * (effect.amplifier() + 1) - player.velocity.y) * 0.2f;
        } else if (!(player.getFlagTracker().has(ScaffoldingCollision.DESCEND_THROUGH_BLOCK_FLAG)
                && (player.getFlagTracker().has("IN_SCAFFOLDING")
                || player.getFlagTracker().has("OVER_SCAFFOLDING")))) {
            player.velocity.y += StickyBlockPhysics.resolveGravityDelta(player, -player.getEffectiveGravity());
        }

        player.bounceGravityCorrection = null;
        player.velocity.y *= 0.98F;

        ClimbableBlockPhysics.applyAutoClimb(player);

        float g = this.prevSlipperiness * 0.91F;
        player.velocity = player.velocity.multiply(g, 1, g);

    }

    private Vec3 halfRelativeMovementCalculate(Vec3 vec3, float f) {
        vec3 = this.moveRelative(vec3, player.getFrictionInfluencedSpeed(f));
        return this.applyClimbingSpeed(vec3);
    }
}
