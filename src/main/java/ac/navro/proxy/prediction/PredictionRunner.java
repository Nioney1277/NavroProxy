package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.collision.Collider;
import ac.navro.proxy.collision.ScaffoldingCollision;
import ac.navro.proxy.prediction.engine.base.PredictionEngine;
import ac.navro.proxy.prediction.engine.data.Vector;
import ac.navro.proxy.prediction.engine.impl.GlidingPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.GroundAndAirPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.fluid.LavaPredictionEngine;
import ac.navro.proxy.prediction.engine.impl.fluid.WaterPredictionEngine;
import ac.navro.proxy.prediction.fluid.FluidMovement;
import ac.navro.proxy.prediction.ticker.impl.PlayerTicker;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.proxy.data.input.PredictionData;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PredictionRunner {
    private final PredictionContext player;

    public PredictionRunner(final PredictionContext player) {
        this.player = player;
    }

    public void run() {
        run(player.tick, null);
    }

    public void run(final long inputTick,
                    final ac.navro.proxy.collision.CollisionPhysicsState.Snapshot replayFrame) {

        player.movementSampler.decision("predictionStart", "predictionInputTick", inputTick,
                "historicalFrame", replayFrame != null);
        player.trapdoorSampler.decision("predictionStart", "predictionInputTick", inputTick,
                "historicalFrame", replayFrame != null);
        player.predictionStartBoundingBox = player.boundingBox.clone();
        player.collisionPhysics.begin(player.session.world(), player.session.entities(), inputTick, replayFrame);
        player.swimDebug.stage("PREDICTION_START","predictionInputTick",inputTick,"historicalFrame",replayFrame!=null);
        player.itemUseDebug.stage("PREDICTION_START","predictionInputTick",inputTick,"historicalFrame",replayFrame!=null);
        player.bounceGravityCorrection = null;
        player.movementCollisionEntries = List.of();
        player.prepareClosestSpace();
        player.riptideTickStartState = RiptidePhysics.snapshot(player);
        player.riptideWasInWater = player.fluidPrediction.wasInWater;
        FluidMovement.prepare(player);
        player.lavaSampler.stage("liquidSensing");
        player.ceilingSampler.stage("liquidSensing");
        player.collisionSampler.stage("liquidSensing");
        player.trapdoorSampler.stage("liquidSensing");
        player.movementSampler.stage("liquidSensing");

        ClimbableBlockPhysics.refreshFlags(player);
        ScaffoldingCollision.prepareInput(player);
        if (player.climbJumpDelay > 0) --player.climbJumpDelay;
        GlidingState.filterInvalidState(player);
        RiptidePhysics.prepare(player);
        if (player.thisTickSpinAttack) {
            FluidMovement.sampleTravelLiquids(player,
                    null);
        }
        if (!this.findBestTickStartVelocity(inputTick)) {
            FluidMovement.finishInput(player);
            return;
        }

        new PlayerTicker(player).tick();
        collectInsideOneWayBlocks();
        player.finishShulkerPush();
        player.predictionResult = new PredictionData(player.beforeCollision.clone(), player.afterCollision.clone(), player.velocity.clone());
        player.lastTickFinalVelocity = player.velocity.clone();
        FluidMovement.finishInput(player);
        player.lavaSampler.stage("predictionComplete");
        player.ceilingSampler.stage("predictionComplete");
        player.collisionSampler.stage("predictionComplete");
        player.trapdoorSampler.stage("predictionComplete");
        player.movementSampler.stage("predictionComplete");
        player.swimDebug.stage("TRAVEL_RESULT","predictionInputTick",inputTick);
        player.itemUseDebug.stage("TRAVEL_RESULT","predictionInputTick",inputTick);

    }

    private void collectInsideOneWayBlocks() {

        final var box = player.boundingBox;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(Math.nextDown(box.maxX)); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(Math.nextDown(box.maxY)); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(Math.nextDown(box.maxZ)); z++) {
                    final var state = player.compensatedWorld.getBlockState(x, y, z, 0);
                    if (state.nativeId() == NativeIds.BlockID.SCAFFOLDING
                            || state.getNetworkState().is("minecraft:scaffolding")) {
                        player.collisionPhysics.addInsideOneWay(new ac.navro.anticheat.util.math.Box(
                                x, y, z, x + 1, y + 1, z + 1));
                    }
                }
            }
        }
    }

    private boolean findBestTickStartVelocity(final long inputTick) {
        final PredictionEngine engine;

        if (player.isInLava()) {
            engine = new LavaPredictionEngine(player);
        } else if (player.fluidPrediction.waterTravel) {
            engine = new WaterPredictionEngine(player);
        } else if (player.getFlagTracker().has("GLIDING")) {
            engine = new GlidingPredictionEngine(player);
        } else {
            engine = new GroundAndAirPredictionEngine(player);
        }

        final List<Vector> possibleVelocities = new ArrayList<>();
        player.swimDebug.stage("BDS_PHYSICS_ENGINE","engine",engine.getClass().getSimpleName());
        player.itemUseDebug.stage("PHYSICS_ENGINE","engine",engine.getClass().getSimpleName());


        if (player.uncertainVelocity != null) {
            possibleVelocities.add(player.uncertainVelocity);
            if (player.uncertainVelocity.isTwice()) {

                player.uncertainVelocity = null;
            }
        }
        final Vector canonicalVelocity = Objects.requireNonNullElseGet(player.certainVelocity,
                () -> new Vector(VectorType.NORMAL, player.velocity.clone()));
        possibleVelocities.add(canonicalVelocity);

        final List<Vec3> teleportVelocities = player.getTeleportUtil().getTeleportStartVelocities(inputTick);
        if (canonicalVelocity.getType() == VectorType.NORMAL) {
            for (final Vec3 teleportVelocity : teleportVelocities) {
                if (possibleVelocities.stream().noneMatch(candidate ->
                        teleportVelocity.squaredDistanceTo(candidate.getVelocity()) <= 1.0E-12F)) {


                    possibleVelocities.add(new Vector(VectorType.NORMAL, teleportVelocity));
                }
            }
        }
        player.movementSampler.decision("velocityCandidates", "predictionInputTick", inputTick,
                "engine", engine.getClass().getSimpleName(),
                "teleportSeeds", teleportVelocities.stream().map(Vec3::toVector3f).toList(),
                "candidates", possibleVelocities.stream().map(value -> java.util.Map.of(
                        "type", value.getType().name(), "velocity", value.getVelocity().toVector3f())).toList());
        player.trapdoorSampler.decision("velocityCandidates", "predictionInputTick", inputTick,
                "engine", engine.getClass().getSimpleName(),
                "teleportSeeds", teleportVelocities.stream().map(Vec3::toVector3f).toList(),
                "candidates", possibleVelocities.stream().map(value -> java.util.Map.of(
                        "type", value.getType().name(), "velocity", value.getVelocity().toVector3f())).toList());
        player.certainVelocity = null;

        float closetDistance = Float.MAX_VALUE;

        if (possibleVelocities.size() == 1) {

            player.bestPossibility = possibleVelocities.get(0);
        } else {
            final Vec3 oldInput = player.input.clone();



            for (Vector possibility : possibleVelocities) {
                player.input = PlayerTicker.scaleMovementInput(player, oldInput, false);
                Vec3 vec3 = RiptidePhysics.applyImpulse(player, possibility.getVelocity().clone());
                vec3 = FluidMovement.prepareVelocity(player, vec3);
                vec3 = ScaffoldingCollision.applyAcceptedDescend(player, vec3);
                vec3 = player.jump(vec3);
                vec3 = FluidMovement.swimControl(player, vec3);
                vec3 = engine.travel(vec3);

                if (player.stuckSpeedMultiplier.lengthSquared() > 1.0E-7) {
                    vec3 = vec3.multiply(player.stuckSpeedMultiplier);
                }

                final var solved = Collider.collideMove(player, Collider.maybeBackOffFromEdge(player, vec3));
                Vec3 vec32 = solved.movement;
                boolean horizontal = !MathUtil.equal(vec3.x, vec32.x) || !MathUtil.equal(vec3.z, vec32.z);
                boolean vertical = vec3.y != vec32.y;

                float distance = player.inputPositionResidual(solved.aabb.feetCenter()).lengthSquared();


                if (horizontal != player.hasInput(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HORIZONTAL_COLLISION)) {
                    distance += 1.0E-6f;
                }
                if (vertical != player.hasInput(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.VERTICAL_COLLISION)) {
                    distance += 1.0E-6f;
                }

                player.ceilingSampler.trial(possibility.getType().name(), possibility.getVelocity(), vec3, vec32, distance);
                player.collisionSampler.trial(possibility.getType().name(), possibility.getVelocity(), vec3, vec32, distance);
                player.trapdoorSampler.trial(possibility.getType().name(), possibility.getVelocity(), vec3, vec32, distance);
                player.movementSampler.trial(possibility.getType().name(), possibility.getVelocity(), vec3, vec32, distance);

                if (distance < closetDistance) {
                    closetDistance = distance;
                    player.bestPossibility = possibility;
                } else if (distance == closetDistance && possibility == player.uncertainVelocity) {
                    player.uncertainVelocity.setTwice(true);
                }
            }

            player.input = oldInput;
        }

        if (player.bestPossibility == null) {
            return false;
        }

        if (player.bestPossibility == player.uncertainVelocity && !player.uncertainVelocity.isTwice()) {

            player.uncertainVelocity = null;
        }



        player.velocity = player.bestPossibility.getVelocity().clone();
        if(player.bestPossibility.getType()==VectorType.VELOCITY && player.wire.worldDiagnostics()!=null)
            player.wire.worldDiagnostics().event("external_motion_prediction_consumed",
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields("predictionInputTick",inputTick,
                            "motion",player.velocity.toVector3f(),"wireInputTick",player.wire.tick()));

        player.collisionPhysics.selectedStartY(player.velocity.y);
        player.movementSampler.decision("selectedStartVelocity", "predictionInputTick", inputTick,
                "type", player.bestPossibility.getType().name(), "velocity", player.velocity.toVector3f());
        player.trapdoorSampler.decision("selectedStartVelocity", "predictionInputTick", inputTick,
                "type", player.bestPossibility.getType().name(), "velocity", player.velocity.toVector3f());

        return true;
    }

}
