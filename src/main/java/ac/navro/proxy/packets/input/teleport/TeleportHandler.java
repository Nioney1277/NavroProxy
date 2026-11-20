package ac.navro.proxy.packets.input.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.teleport.PredictionRewind;
import ac.navro.proxy.teleport.ProtocolTeleportMotion;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import ac.navro.proxy.teleport.data.RewindData;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;

public final class TeleportHandler {
    private final SessionState session;

    public TeleportHandler(SessionState session) {
        this.session = session;
    }

    public boolean processTeleport(PlayerAuthInputPacket input) {
        var teleports = session.teleports();
        var log = session.player().worldDiagnostics();
        if (log != null && teleports.isTeleporting()) {
            var head = teleports.getQueuedTeleports().peek();
            log.event("pendingTeleportInput", ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                    "inputTick", input.getTick(), "handleTeleport", input.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT),
                    "wirePosition", String.valueOf(input.getPosition()), "pendingTeleports", teleports.getQueuedTeleports().size(),
                    "headType", head == null ? null : head.getClass().getSimpleName(),
                    "headAccepted", head != null && head.isAccepted(), "headTarget", head == null ? null : head.getPosition().toVector3f()));
        }
        boolean[] skipTravel = {false};
        teleports.drainReadyTeleports(input, session.player().vehicleData == null, data -> {
            if (data instanceof RewindData rewind) PredictionRewind.process(session, rewind);
            else if (data instanceof CorrectionResetData reset) {
                applyHardCorrection(reset, input, true);
                skipTravel[0] = true;
            } else if (applyTeleport(data)) skipTravel[0] = true;
        });
        var player = session.player();
        if (player.vehicleData == null) {
            float offset = session.playerYOffset();
            Vec3 step = teleports.stepInterpolation(player.position().up(offset));
            if (step != null) {
                player.rebasePosition(step.down(offset), true);
                player.setVelocity(Vec3.ZERO.clone());
                teleports.setLastKnowValid(step);
            }
        }
        return skipTravel[0];
    }

    public boolean processRejectedInput(PlayerAuthInputPacket input) {
        if (!session.spawned() || session.awaitingBackendSpawnPosition() || session.player().awaitingRespawnHealth()) return false;
        var pending = session.teleports().getQueuedTeleports();
        if (pending.peek() instanceof CorrectionResetData reset && reset.confirmedBy(input) && pending.remove(reset)) {
            applyHardCorrection(reset, input, false);
            return true;
        }
        return false;
    }

    private boolean applyTeleport(TeleportData data) {
        var player = session.player();
        var teleports = session.teleports();
        float offset = session.playerYOffset();
        Vec3 target = data.getPosition();
        boolean normal = data.getMode() == MovePlayerPacket.Mode.NORMAL.ordinal();
        boolean interpolate = normal && session.world().isChunkLoaded((int) Math.floor(target.x), (int) Math.floor(target.z));
        Vec3 origin = player.position().clone();
        if (interpolate) teleports.startInterpolation(target);
        else teleports.clearInterpolation();
        var prediction = session.detectionEnabled() ? session.authInputPackets() : null;
        var external = prediction == null ? null : prediction.context().certainVelocity;
        player.acceptTeleport(interpolate ? origin : target.down(offset), data.isOnGround());
        if (normal && !interpolate) player.setVelocity(target.subtract(origin.up(offset)));
        if (prediction != null) {
            prediction.reset();
            prediction.context().certainVelocity = external;
        }
        session.resetMovementHistory();
        teleports.setLastKnowValid(player.position().up(offset));
        return data.getMode() == MovePlayerPacket.Mode.TELEPORT.ordinal();
    }

    private void applyHardCorrection(CorrectionResetData reset, PlayerAuthInputPacket input, boolean acceptedInput) {
        var player = session.player();
        var teleports = session.teleports();
        float offset = session.playerYOffset();
        var prediction = session.authInputPackets();
        var external = prediction.context().certainVelocity;
        if (external == reset.includedExternalImpulse()) external = null;
        player.acceptTeleport(reset.nativeTargetFoot(), reset.isOnGround());
        if (reset.deliveredVelocity() != null) player.setVelocity(reset.deliveredVelocity());
        player.acceptInputPosition(reset.confirmedFoot(input), java.util.List.of());
        player.resetCorrectionObservation();
        prediction.reset();
        prediction.context().certainVelocity = external;
        prediction.velocityPackets().consumeSetbackExternalMotion(reset);
        session.resetMovementHistory();
        teleports.setLastKnowValid(player.position().up(offset));
        if (acceptedInput && !teleports.isTeleporting() && reset.deliveredVelocity() == null) {
            Vec3 expected = reset.isOnGround() && input.getInputData().contains(PlayerAuthInputData.START_JUMPING)
                    && player.predictionSprinting() && !player.flying()
                    ? ProtocolTeleportMotion.sprintJumpImpulse(input.getRotation().getY()) : null;
            var seed = ProtocolTeleportMotion.fromConfirmation(input.getTick(), new Vec3(input.getDelta()), expected);
            if (seed != null) session.rememberTeleportMotion(seed);
        }
    }
}
