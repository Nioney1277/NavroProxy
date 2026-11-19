package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.collision.Collider;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import ac.navro.proxy.data.input.TickData;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import ac.navro.proxy.prediction.PredictionRunner;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.teleport.data.RewindData;


public final class PredictionRewind {
    private PredictionRewind() { }

    public static void process(SessionState session, RewindData correction) {
        var wire = session.player();
        if (session.teleports().isTeleporting() || wire.isMovementExempted() || wire.vehicleData != null) return;
        long liveTick = wire.tick();
        if (correction.tick() >= liveTick) return;
        var history = session.teleports().getAuthInputHistory();
        TickData source = history.get(correction.tick());
        long distance = liveTick - correction.tick();
        if (source == null || distance > session.rewindHistorySize()
                || history.subMap(correction.tick(), false, liveTick, false).size() != distance - 1) {
            trace(session, "prediction_rewind_missing_history", correction, 0);
            session.teleports().teleport(correction.getPosition());
            return;
        }

        var prediction = session.authInputPackets();
        var context = prediction.context();
        var liveInput = wire.snapshot(session.fluidState());
        var liveItemUse = prediction.itemUse().getItemUseTracker().snapshot();
        var liveRiptide = RiptidePhysics.snapshot(context);

        prediction.velocityPackets().consumeBdsCorrectedExternalMotion(
                correction.externalMotionSequence(), correction.tick());
        restoreBaseline(session, correction, source);
        long precedingResize = wire.aabbResizeRevision();
        int replayed = 0;
        try {
            for (long tick = correction.tick() + 1; tick < liveTick; tick++) {
                var input = history.get(tick);
                wire.restoreReplayInput(input.state(), precedingResize);
                prediction.itemUse().getItemUseTracker().restore(input.itemUseSnapshot());
                RiptidePhysics.restore(context, input.riptideStartState());
                context.begin();


                new PredictionRunner(context).run(tick, input.state().collisionState());
                context.syncWire();
                acceptHistoricalPrediction(session, tick);
                wire.completePredictionInput();
                precedingResize = input.endAabb() == null
                        ? wire.aabbResizeRevision() : input.endAabb().resizeRevision();
                replayed++;
            }
        } finally {


            wire.restoreReplayInput(liveInput, precedingResize);
            prediction.itemUse().getItemUseTracker().restore(liveItemUse);
            RiptidePhysics.restore(context, liveRiptide);
        }

        trace(session, "prediction_rewind_applied", correction, replayed);
    }

    private static void restoreBaseline(SessionState session, RewindData correction, TickData source) {
        var wire = session.player();
        var context = session.authInputPackets().context();
        wire.collisionPhysics.restore(source.state().collisionState());
        wire.setCollisionPenetratingFrames(source.state().collisionPenetratingFrames());
        session.fluidState().restore(source.state().fluidState());
        context.fallDistance = source.fallDistance();
        context.fluidPrediction.swimSpeedMultiplier = source.swimSpeedMultiplier();
        context.fluidPrediction.waterDrag = source.waterDrag();
        context.bounceGravityCorrection = null;
        RiptidePhysics.restore(context, source.riptideEndState());
        float sourceOffset = source.state().observedPosition() == null
                ? session.playerYOffset() : source.state().observedPosition().yOffset();
        correction.restoreAabb(wire, source.endAabb(), sourceOffset);
        wire.setVelocity(correction.tickEnd());
        wire.setCollisionResult(false, false, correction.isOnGround());
        wire.resetCorrectionObservation();
        session.cacheRewindBaseline(correction.tick(), correction.yOffset(sourceOffset));
    }

    private static void acceptHistoricalPrediction(SessionState session, long tick) {
        var wire = session.player();
        var context = session.authInputPackets().context();
        var tolerance = session.sampleEntityTolerance().tolerance();
        if (tolerance.collided() && !context.collisionPhysics.pushedByBlock()) {
            Vec3 target = ProtocolEntityTolerance.clampAccepted(
                    context.position, context.unvalidatedPosition, tolerance);
            Vec3 motion = ProtocolEntityTolerance.clampAccepted(
                    context.velocity, context.unvalidatedTickEnd, tolerance);
            wire.rebasePosition(target, false);
            wire.setVelocity(motion);
            wire.collisionPhysics.acceptClientPosition();
            session.cacheRewindBaseline(tick, session.playerYOffset());
        } else if (!tolerance.collided()) {
            if (context.velocity.distanceTo(context.unvalidatedTickEnd) < context.maxOffset)
                wire.setVelocity(context.unvalidatedTickEnd);
            if (wire.inputPositionDistance(wire.position()) < context.maxOffset)
                Collider.acceptInputPosition(wire, session.collisionWorld(), wire.inputPositionTarget());
        }
        if (wire.inputPositionDistance(wire.position()) > context.maxOffset) wire.resetCorrectionObservation();
    }

    private static void trace(SessionState session, String event, RewindData correction, int replayed) {
        var diagnostics = session.player().worldDiagnostics();
        if (diagnostics != null) diagnostics.event(event, WorldCollisionDiagnostics.fields(
                "sourceTick", correction.tick(), "replayedInputs", replayed,
                "backendType", session.backendType().name(),
                "includedMotionSequence", correction.externalMotionSequence(),
                "position", session.player().position().toVector3f(),
                "velocity", session.player().velocity().toVector3f()));
    }
}
