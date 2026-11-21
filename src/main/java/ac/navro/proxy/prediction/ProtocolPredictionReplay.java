package ac.navro.proxy.prediction;

import ac.navro.anticheat.collision.ProtocolCollisionSolver;
import ac.navro.anticheat.collision.SneakEdgePhysics;
import ac.navro.anticheat.collision.SweptMovement;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.collision.ProtocolCollisionWorld;
import ac.navro.proxy.prediction.fluid.ProtocolFluidPrediction;
import ac.navro.proxy.state.WireTickSnapshot;
import ac.navro.proxy.teleport.ProtocolTeleportMotion;

import java.util.List;






public final class ProtocolPredictionReplay {
    private final ProtocolCollisionWorld collisionWorld;
    private WireTickSnapshot replayInput;
    private ProtocolCollisionWorld replayWorld;

    private ProtocolCollisionWorld collisionContext(WireTickSnapshot snapshot) {
        if (snapshot.collisionState() == null || snapshot.collisionState().inputTick() != snapshot.tick()) return collisionWorld;
        if (replayInput != snapshot) {
            replayInput = snapshot;
            replayWorld = collisionWorld.replay(snapshot.tick(), snapshot.collisionState());
        }
        return replayWorld;
    }

    public ProtocolPredictionReplay(ProtocolCollisionWorld collisionWorld) {
        this.collisionWorld = collisionWorld;
    }

    public CandidateResult solveCandidate(WireTickSnapshot snapshot,
                                          Vec3 requestedMovement,
                                          Vec3 maxDepenetration,
                                          boolean yxz) {
        if (snapshot == null || snapshot.boundingBox() == null
                || !snapshot.boundingBox().isValid() || requestedMovement == null) {
            return CandidateResult.unavailable(snapshot == null ? -1 : snapshot.tick());
        }
        Box actor = snapshot.boundingBox();
        requestedMovement = ac.navro.anticheat.collision.CollisionQuery.clamp(requestedMovement);
        float step = snapshot.wasdControlled() ? 1.0625F : 0.5625F;
        ProtocolCollisionWorld context = collisionContext(snapshot);
        if (SneakEdgePhysics.active(snapshot.inputData().contains(
                org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_DOWN), snapshot.sneaking(),
                snapshot.onGround(), snapshot.flag6())) {
            Box supportQuery = ac.navro.anticheat.collision.CollisionQuery.buildCollisionQuery(actor, requestedMovement, step);
            List<Box> support = context.collectColliders(context.entityCollisions(supportQuery, actor), supportQuery);
            requestedMovement = SneakEdgePhysics.resolve(actor, support, requestedMovement, step).movement();
        }
        Box query = ac.navro.anticheat.collision.CollisionQuery.buildCollisionQuery(actor, requestedMovement, step);
        List<Box> collisions = context.collectMovement(query, actor);
        boolean oneWay = snapshot.oneWayCollision() || snapshot.collisionPenetratingFrames() >= 2
                || context.collisionPhysics().hasOneWayCollision(actor, false);
        Vec3 depenetration = oneWay
                ? context.nearbyEntityShapes(actor).isEmpty() ? Vec3.ZERO : new Vec3(0.1F, 0.1F, 0.1F)
                : maxDepenetration;
        SweptMovement.MoveResult result = ProtocolCollisionSolver.solveResolved(
                requestedMovement, actor, collisions, depenetration, step,
                snapshot.onGround() || snapshot.flying());
        Vec3 postCollisionVelocity = applyInsideEffects(snapshot, result);
        return new CandidateResult(snapshot.tick(), result, collisions.size(), postCollisionVelocity);
    }

    private Vec3 applyInsideEffects(WireTickSnapshot snapshot, SweptMovement.MoveResult result) {
        Vec3 velocity = snapshot.velocity() == null ? result.movement.clone() : snapshot.velocity();
        List<ProtocolBlockEffects.BubbleContact> bubbles = ProtocolBlockEffects.bubbleContacts(
                collisionWorld.world(), result.aabb, collisionWorld.dimension());
        velocity = ProtocolBlockEffects.applyBubble(velocity, bubbles);
        ProtocolBlockEffects.HoneyResult honey = ProtocolBlockEffects.applyHoney(
                velocity, result.aabb.feetCenter(), snapshot.dimensions().width(),
                collisionWorld.world(), result.aabb, collisionWorld.dimension());
        return honey.velocity();
    }





    public CandidateResult solveGroundAir(WireTickSnapshot snapshot,
                                          float baseSpeed, float slipperiness,
                                          boolean soulSand, boolean soulSpeed,
                                          Integer jumpBoostAmplifier,
                                          Integer levitationAmplifier,
                                          float blockJumpFactor,
                                          float gravityDelta,
                                          int climbJumpDelay) {
        if (snapshot == null || snapshot.boundingBox() == null
                || !snapshot.boundingBox().isValid()) {
            return CandidateResult.unavailable(snapshot == null ? -1 : snapshot.tick());
        }
        Vec3 velocity = ProtocolGroundAirPrediction.jump(snapshot.velocity(), snapshot.flying(),
                snapshot.onGround(), snapshot.inputData().contains(
                        org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_JUMPING),
                climbJumpDelay, blockJumpFactor, jumpBoostAmplifier,
                snapshot.predictionSprinting(), snapshot.inputRotation() == null
                        ? 0.0F : snapshot.inputRotation().y());
        velocity = ProtocolGroundAirPrediction.travel(velocity, snapshot.input(), baseSpeed,
                slipperiness, snapshot.onGround(), snapshot.predictionSprinting(), soulSand,
                soulSpeed, snapshot.inputRotation() == null ? 0.0F : snapshot.inputRotation().y());
        velocity = ProtocolGroundAirPrediction.finalizeMovement(velocity,
                levitationAmplifier, gravityDelta, slipperiness);
        return solveCandidate(snapshot, velocity, new Vec3(1, 1, 1), true);
    }

    public CandidateResult solveWater(WireTickSnapshot snapshot,
                                      int depthLevel,
                                      float movementSpeed, float underwaterSpeed,
                                      float swimMultiplier, float waterDrag,
                                      boolean jumping, boolean breathingLiquid,
                                      boolean breathingAir, boolean wantDownSlow,
                                      float yaw) {
        if (snapshot == null) return CandidateResult.unavailable(-1);
        ProtocolFluidPrediction.WaterParameters parameters = ProtocolFluidPrediction.waterParameters(
                depthLevel, snapshot.onGround(), movementSpeed,
                underwaterSpeed, swimMultiplier, snapshot.predictionSprinting(), waterDrag);
        Vec3 velocity = ProtocolFluidPrediction.moveRelative(snapshot.velocity(), snapshot.input(),
                parameters.strength(), yaw);
        Vec3 view = snapshot.inputRotation() == null ? null
                : ac.navro.anticheat.util.MathUtil.getRotationVector(
                        snapshot.inputRotation().x(), snapshot.inputRotation().y());
        velocity = ProtocolFluidPrediction.swimControl(velocity,
                view == null ? 0 : view.y,
                snapshot.predictionSwimming(), jumping, snapshot.flying(), breathingLiquid,
                breathingAir, wantDownSlow);
        velocity = new Vec3(velocity.x * parameters.horizontalDrag(), velocity.y * 0.8F,
                velocity.z * parameters.horizontalDrag());
        return solveCandidate(snapshot, velocity, new Vec3(1, 1, 1), true);
    }

    public CandidateResult solveLava(WireTickSnapshot snapshot, float lavaSpeed,
                                     boolean climbing, boolean sneaking,
                                     boolean climbHaltAtFeet, float yaw) {
        if (snapshot == null) return CandidateResult.unavailable(-1);
        Vec3 velocity = ProtocolFluidPrediction.moveRelative(snapshot.velocity(), snapshot.input(), lavaSpeed, yaw);
        velocity = new Vec3(velocity.x,
                ProtocolFluidPrediction.lavaClimbVelocity(velocity.y, climbing, sneaking, climbHaltAtFeet),
                velocity.z);
        velocity = new Vec3(velocity.x * 0.5F, velocity.y, velocity.z * 0.5F);
        return solveCandidate(snapshot, velocity, new Vec3(1, 1, 1), true);
    }

    public CandidateResult solveGliding(WireTickSnapshot snapshot, WireTickSnapshot previous,
                                        float gravity) {
        if (snapshot == null || previous == null || !snapshot.gliding()) {
            return CandidateResult.unavailable(snapshot == null ? -1 : snapshot.tick());
        }
        float pitch = snapshot.inputRotation() == null ? 0 : snapshot.inputRotation().x();
        float yaw = snapshot.inputRotation() == null ? 0 : snapshot.inputRotation().y();
        float previousPitch = previous.inputRotation() == null ? pitch : previous.inputRotation().x();
        float previousYaw = previous.inputRotation() == null ? yaw : previous.inputRotation().y();
        Vec3 velocity = ProtocolGlidingPrediction.travel(snapshot.velocity(), previousPitch, pitch,
                previousYaw, yaw, gravity, snapshot.glideBoostTicks());
        return solveCandidate(snapshot, velocity, new Vec3(1, 1, 1), true);
    }

    public CandidateResult solveRiptide(WireTickSnapshot snapshot, int riptideLevel,
                                        boolean wasInWater, boolean headInWater) {
        if (snapshot == null || riptideLevel <= 0) return CandidateResult.unavailable(snapshot == null ? -1 : snapshot.tick());
        float pitch = snapshot.inputRotation() == null ? 0 : snapshot.inputRotation().x();
        float yaw = snapshot.inputRotation() == null ? 0 : snapshot.inputRotation().y();
        Vec3 velocity = ProtocolRiptidePrediction.impulse(snapshot.velocity(), pitch, yaw,
                riptideLevel, snapshot.onGround(), wasInWater, headInWater);
        return solveCandidate(snapshot, velocity, new Vec3(1, 1, 1), true);
    }


    public Selection selectCandidate(WireTickSnapshot snapshot, List<VelocityCandidate> candidates,
                                     Vec3 maxDepenetration) {
        if (snapshot == null || candidates == null || candidates.isEmpty()) {
            return new Selection(snapshot == null ? -1 : snapshot.tick(), null, Double.POSITIVE_INFINITY);
        }
        VelocityCandidate best = null;
        CandidateResult bestResult = null;
        float closest = Float.POSITIVE_INFINITY;
        for (VelocityCandidate candidate : candidates) {
            CandidateResult result = solveCandidate(snapshot, candidate.velocity(), maxDepenetration, true);
            if (!result.available()) continue;
            Vec3 feet = result.result().aabb.feetCenter();
            float distance = wireResidualSquared(snapshot, feet, candidate.yOffset());
            boolean horizontal = !ac.navro.anticheat.util.MathUtil.equal(result.result().movement.x, candidate.velocity().x)
                    || !ac.navro.anticheat.util.MathUtil.equal(result.result().movement.z, candidate.velocity().z);
            boolean vertical = result.result().movement.y != candidate.velocity().y;
            if (horizontal != snapshot.horizontalCollision()) distance += 1.0E-6F;
            if (vertical != snapshot.verticalCollision()) distance += 1.0E-6F;
            if (distance < closest) {
                closest = distance;
                best = candidate;
                bestResult = result;
            }
        }
        return new Selection(snapshot.tick(), bestResult == null ? null : new SelectedCandidate(best, bestResult), closest);
    }

    public Selection selectWithMotionSeeds(WireTickSnapshot snapshot,
                                           List<WireMotionSeed> seeds,
                                           float yOffset,
                                           Vec3 maxDepenetration) {
        return selectWithMotionSeeds(snapshot, seeds, List.of(), yOffset, maxDepenetration);
    }

    public Selection selectWithMotionSeeds(WireTickSnapshot snapshot,
                                           List<WireMotionSeed> seeds,
                                           List<ProtocolTeleportMotion> teleportSeeds,
                                           float yOffset,
                                           Vec3 maxDepenetration) {
        if (snapshot == null) return new Selection(-1, null, Double.POSITIVE_INFINITY);
        List<VelocityCandidate> candidates = new java.util.ArrayList<>();
        if (snapshot.velocity() != null) candidates.add(new VelocityCandidate("NORMAL", snapshot.velocity(), yOffset));
        if (seeds != null) for (WireMotionSeed seed : seeds) {
            if (seed != null && seed.velocity() != null) {
                addDistinct(candidates, new VelocityCandidate(seed.source(), seed.velocity(), yOffset));
            }
        }
        if (teleportSeeds != null) for (ProtocolTeleportMotion seed : teleportSeeds) {
            if (seed == null) continue;
            for (Vec3 velocity : seed.velocitiesForTick(snapshot.tick())) {
                addDistinct(candidates, new VelocityCandidate("TELEPORT", velocity, yOffset));
            }
        }
        return selectCandidate(snapshot, candidates, maxDepenetration);
    }

    private static void addDistinct(List<VelocityCandidate> candidates, VelocityCandidate candidate) {
        for (VelocityCandidate existing : candidates) {
            if (candidate.velocity().squaredDistanceTo(existing.velocity()) <= 1.0E-12F) return;
        }
        candidates.add(candidate);
    }

    private static float wireResidualSquared(WireTickSnapshot snapshot, Vec3 feet, float yOffset) {
        if (snapshot.inputPosition() == null || feet == null) return Float.POSITIVE_INFINITY;
        ac.navro.anticheat.data.input.InputPosition observation = snapshot.observedPosition();
        if (observation == null) observation = new ac.navro.anticheat.data.input.InputPosition(
                snapshot.inputPosition().x(), snapshot.inputPosition().y(), snapshot.inputPosition().z(), yOffset);
        return observation.residual(feet).lengthSquared();
    }

    public record CandidateResult(long tick, SweptMovement.MoveResult result,
                                  int collisionCount, Vec3 postCollisionVelocity) {
        public boolean available() { return result != null; }
        static CandidateResult unavailable(long tick) { return new CandidateResult(tick, null, 0, null); }
    }

    public record VelocityCandidate(String type, Vec3 velocity, float yOffset) {}
    public record SelectedCandidate(VelocityCandidate candidate, CandidateResult result) {}
    public record Selection(long tick, SelectedCandidate selected, double distanceSquared) {}
}
