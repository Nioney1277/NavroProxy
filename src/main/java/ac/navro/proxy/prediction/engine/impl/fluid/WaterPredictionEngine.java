package ac.navro.proxy.prediction.engine.impl.fluid;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.prediction.StickyBlockPhysics;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;
import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;
import java.util.Map;

public class WaterPredictionEngine extends PredictionEngine {
    private float depthRatio;
    public WaterPredictionEngine(PredictionContext player) { super(player); }

    @Override public Vec3 travel(Vec3 velocity) {
        final org.cloudburstmc.protocol.bedrock.data.inventory.ItemData boots = player.compensatedInventory.armorContainer.get(3).getData();
        final Map<Integer, Integer> enchantments = ac.navro.proxy.compensated.CompensatedInventory.getEnchantments(player.nativeItem(boots));
        final Integer depthType = NativeIds.Enchantment.getEnchantment(NativeIds.Enchantment.ID_WATER_WALKER);
        final int level = depthType == null ? 0 : Math.max(0, enchantments.getOrDefault(depthType, 0));
        final var parameters = FluidMovement.waterParameters(level, player.onGround,
                player.getSpeed(), player.getUnderwaterSpeed(), player.fluidPrediction.swimSpeedMultiplier,
                player.isPredictionSprinting(), player.fluidPrediction.waterDrag);
        depthRatio = parameters.depthRatio();
        player.hasDepthStrider = depthRatio > 0;
        return FluidMovement.moveRelative(velocity, player.input, parameters.strength(), player.yaw);
    }

    @Override public void finalizeMovement() {
        final float base = player.isPredictionSprinting() ? 0.9F
                : Float.isFinite(player.fluidPrediction.waterDrag) ? player.fluidPrediction.waterDrag : 0.8F;
        final float drag = player.fluidPrediction.swimSpeedMultiplier > 1 ? base
                : base + (0.54600006F - base) * depthRatio;
        player.velocity = player.velocity.multiply(drag, 0.8F, drag);
        if (player.hasEffect(NativeIds.Effect.LEVITATION)) {
            final float target = (player.getEffect(NativeIds.Effect.LEVITATION).amplifier() + 1) * 0.05F;
            player.velocity.y += (target - player.velocity.y) * 0.2F;
        } else {
            final float gravity = player.getEffectiveGravity();
            if (gravity != 0 && !player.isPredictionSwimming()) {
                player.velocity.y += StickyBlockPhysics.resolveGravityDelta(player, -gravity / 16.0F);
            }
        }
        player.bounceGravityCorrection = null;
    }
}
