package ac.navro.proxy.prediction.ticker.impl;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.collision.ScaffoldingCollision;
import ac.navro.proxy.prediction.ClimbableBlockPhysics;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;
import ac.navro.proxy.prediction.engine.impl.GlidingPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.GroundAndAirPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.fluid.LavaPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.fluid.WaterPredictionEngine;
import ac.navro.proxy.prediction.ticker.base.EntityTicker;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;

import java.util.List;

public class LivingTicker extends EntityTicker {
    public LivingTicker(PredictionContext player) {
        super(player);
    }

    @Override
    public void tick() {
        super.tick();

        player.velocity = RiptidePhysics.applyImpulse(player, player.velocity);

        this.aiStep();
    }

    public void aiStep() {


        player.velocity.x = Math.abs(player.velocity.x) < 1.0E-8 ? 0 : player.velocity.x;
        player.velocity.y = Math.abs(player.velocity.y) < 1.0E-8 ? 0 : player.velocity.y;
        player.velocity.z = Math.abs(player.velocity.z) < 1.0E-8 ? 0 : player.velocity.z;

        this.applyInput();

        player.velocity = ScaffoldingCollision.applyAcceptedDescend(player, player.velocity);
        ClimbableBlockPhysics.recordAcceptedJump(player);

        player.swimDebug.stage("BDS_JUMP_INPUT");
        player.velocity = player.jump(player.velocity);
        player.swimDebug.stage("BDS_JUMP_RESULT");
        player.lavaSampler.stage("afterJump");
        player.ceilingSampler.stage("afterJump");

        player.cachedOnPos = null;

        Box oldBox = player.boundingBox.clone();

        this.travelRidden();

        this.applyEffectsFromBlocks();

        if (player.autoSpinAttackTicks > 0) {
            --player.autoSpinAttackTicks;
            this.checkAutoSpinAttack(oldBox, player.boundingBox);
        }

        ClimbableBlockPhysics.refreshFlags(player);
    }

    protected final void checkAutoSpinAttack(Box aABB, Box aABB2) {
        Box aABB3 = aABB.union(aABB2);

        List<ac.navro.proxy.compensated.cache.entity.EntityCache> list = player.compensatedWorld.getEntities().values().stream().toList();
        if (!list.isEmpty()) {
            for (ac.navro.proxy.compensated.cache.entity.EntityCache entity : list) {
                if (entity.getCurrent() == null) {
                    continue;
                }

                if (!entity.getCurrent().getBoundingBox().intersects(aABB3)) {
                    continue;
                }

                player.dirtySpinStop = true;


                break;
            }
        } else if (player.horizontalCollision) {
            player.stopRiptide();
        }

        if (player.autoSpinAttackTicks <= 0) {
            player.stopRiptide();
        }
    }

    protected void travelRidden() {









        this.travel();
    }

    public void applyInput() {
        player.input = player.input.multiply(0.98F);
    }

    protected void travel() {

        if (player.isInLava() || player.fluidPrediction.waterTravel) {
            this.travelInFluid();
        } else if (player.getFlagTracker().has("GLIDING")) {






            player.velocity = new GlidingPredictionEngine(player).travel(player.velocity);
            this.doSelfMove(player.velocity.clone());
        } else {
            travelInAir();
        }
    }

    private void travelInAir() {
        final PredictionEngine engine = new GroundAndAirPredictionEngine(player);

        player.velocity = engine.travel(player.velocity);
        player.ceilingSampler.stage("afterAirTravel");

        this.doSelfMove(player.velocity.clone());
        engine.finalizeMovement();
        player.ceilingSampler.stage("afterAirDragAndGravity");

    }

    private void travelInFluid() {
        float d = player.position.y;
        final PredictionEngine engine;
        if (player.isInLava()) {
            engine = new LavaPredictionEngine(player);
        } else {
            engine = new WaterPredictionEngine(player);
        }

        player.velocity = engine.travel(player.velocity);
        player.lavaSampler.stage("afterFluidTravel");
        player.ceilingSampler.stage("afterFluidTravel");

        this.doSelfMove(player.velocity.clone());
        engine.finalizeMovement();
        player.lavaSampler.stage("afterFluidDragAndGravity");
        player.ceilingSampler.stage("afterFluidDragAndGravity");

        Vec3 vec33 = player.velocity;
        if (player.horizontalCollision && player.doesNotCollide(vec33.x, vec33.y + 0.6f - player.position.y + d, vec33.z)) {
            player.velocity.y = 0.3F;
        }
        player.lavaSampler.stage("afterFluidWallExit");
        player.ceilingSampler.stage("afterFluidWallExit");
    }
}
