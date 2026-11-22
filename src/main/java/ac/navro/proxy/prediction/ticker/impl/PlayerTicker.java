package ac.navro.proxy.prediction.ticker.impl;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.prediction.SneakInputPhysics;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;
import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;

import java.util.Map;

public class PlayerTicker extends LivingTicker {
    public PlayerTicker(PredictionContext player) {
        super(player);
    }

    @Override
    public void applyInput() {
        player.swimDebug.stage("MOVEMENT_INPUT_SCALE_BEFORE","movementInput",
                player.input.toVector3f(),
                "slowdownTicks",player.ticksSinceCanSlowdown);
        player.lavaSampler.stage("beforeInputScaling");
        player.ceilingSampler.stage("beforeInputScaling");
        player.itemUseDebug.stage("INPUT_SCALE_BEFORE");
        player.input = scaleMovementInput(player, player.input, true);
        player.itemUseDebug.stage("INPUT_SCALE_AFTER");
        player.lavaSampler.stage("afterInputScaling");
        player.ceilingSampler.stage("afterInputScaling");
        player.swimDebug.stage("MOVEMENT_INPUT_SCALE_AFTER","movementInput",
                player.input.toVector3f(),
                "slowdownTicks",player.ticksSinceCanSlowdown);
    }


    public static Vec3 scaleMovementInput(final PredictionContext player, final Vec3 rawInput, final boolean commit) {
        Vec3 input = rawInput;
        input = input.multiply(0.98F);
        boolean swiftSneak = false;
        if (SneakInputPhysics.shouldScale(player) && player.ticksSinceCanSlowdown >= 2) {
            final Map<Integer, Integer> enchantments = ac.navro.proxy.compensated.CompensatedInventory.getEnchantments(
                    player.nativeItem(player.compensatedInventory.armorContainer.get(2).getData()));
            final Integer enchantment = NativeIds.Enchantment.getEnchantment(NativeIds.Enchantment.ID_SWIFT_SNEAK);
            swiftSneak = enchantment != null && enchantments.containsKey(enchantment);
        }
        input = SneakInputPhysics.scale(player, input, swiftSneak, commit);
        if (player.getItemUseTracker().hasPredictionItemSlowdown()) {
            input = input.multiply(player.getItemUseTracker().getPredictionItemSlowdownFactor());
        }
        return input;
    }

    @Override
    public void aiStep() {

        player.lavaSampler.stage("beforeFluidPush");
        player.ceilingSampler.stage("beforeFluidPush");
        player.velocity = FluidMovement.prepareVelocity(player, player.velocity);
        player.lavaSampler.stage("afterFluidPush");
        player.ceilingSampler.stage("afterFluidPush");

        super.aiStep();
    }

    @Override
    protected void travel() {
        player.swimDebug.stage("SWIM_CONTROL_BEFORE");
        player.velocity = FluidMovement.swimControl(player, player.velocity);
        player.swimDebug.stage("SWIM_CONTROL_AFTER");

        super.travel();

    }
}
