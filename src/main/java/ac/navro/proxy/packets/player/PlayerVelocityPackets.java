package ac.navro.proxy.packets.player;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.engine.data.Vector;
import ac.navro.anticheat.util.math.Vec3;


public final class PlayerVelocityPackets {
    private final SessionState session;
    private final PredictionContext player;
    public PlayerVelocityPackets(SessionState session, PredictionContext player) {
        this.session=session;
        this.player=player;
    }
    private Vec3 pendingExternalMotion;
    private long externalMotionSequence,consumedExternalMotionSequence=-1;
    private long latestMotionPacketTick;

    private static final class Motion {
        final long sequence, packetTick;
        final Vector candidate;
        boolean acknowledged, superseded;
        Motion(long sequence, long packetTick, Vector candidate) {
            this.sequence = sequence; this.packetTick = packetTick; this.candidate = candidate;
        }
    }
    private final java.util.List<Motion> bdsMotions = new java.util.ArrayList<>();

    public void clearExternalMotion() {
        pendingExternalMotion = null;
        consumedExternalMotionSequence = ++externalMotionSequence;
        bdsMotions.clear();
    }
    public long externalMotionSequence() { return externalMotionSequence; }


    public void consumeBdsCorrectedExternalMotion(long included, long correctionTick) {
        if (session.backendType() != ac.navro.proxy.protocol.BackendType.BDS || included < 0) return;
        if (externalMotionSequence <= included && latestMotionPacketTick <= correctionTick)
            pendingExternalMotion = null;
        bdsMotions.removeIf(motion -> {
            boolean includedInBaseline = motion.sequence <= included && motion.packetTick <= correctionTick;
            if (includedInBaseline) {
                motion.superseded = true;
                if (player.certainVelocity == motion.candidate) player.certainVelocity = null;
                if (player.uncertainVelocity == motion.candidate) player.uncertainVelocity = null;
            }
            return includedInBaseline || motion.acknowledged
                    && player.certainVelocity != motion.candidate && player.uncertainVelocity != motion.candidate;
        });
    }
    public Vec3 setbackVelocity(ac.navro.proxy.teleport.data.RewindHistory frame){
        if(pendingExternalMotion!=null)return pendingExternalMotion.clone();
        return player.certainVelocity!=null?player.certainVelocity.getVelocity().clone():frame.data().tickEnd();
    }
    public void consumeSetbackExternalMotion(ac.navro.proxy.teleport.data.CorrectionResetData correction){
        long included=correction.externalMotionSequence();consumedExternalMotionSequence=Math.max(consumedExternalMotionSequence,included);
        if(externalMotionSequence<=included)pendingExternalMotion=null;
        if(player.certainVelocity==correction.includedExternalImpulse())player.certainVelocity=null;
    }
    public void onPacketSend(org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket packet){
        long backendTick=packet.getTick();
        latestMotionPacketTick=backendTick;
        packet.setTick(0);
        final Vec3 motion=new Vec3(packet.getMotion());pendingExternalMotion=motion.clone();long sequence=++externalMotionSequence;
        if(session.player().worldDiagnostics()!=null)session.player().worldDiagnostics().beginMotionTrace();
        trace("external_motion_queued",backendTick,sequence,motion);
        if(motion.x==0 && motion.y==0 && motion.z==0){
            session.player().queueAcknowledgment(packet,()->{if(sequence==externalMotionSequence)pendingExternalMotion=null;});return;
        }
        var velocity=new Vector(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,motion);
        var origin = new Motion(sequence, backendTick, velocity);
        if (session.backendType() == ac.navro.proxy.protocol.BackendType.BDS) {
            bdsMotions.removeIf(m -> m.acknowledged && player.certainVelocity != m.candidate
                    && player.uncertainVelocity != m.candidate);
            bdsMotions.add(origin);
        }
        session.player().queueAcknowledgment(packet,()->{
            origin.acknowledged = true;
            if(sequence==externalMotionSequence)pendingExternalMotion=null;
            if(sequence>consumedExternalMotionSequence&&!origin.superseded){player.certainVelocity=velocity;trace("external_motion_acknowledged",backendTick,sequence,motion);}
        });
    }
    private void trace(String event,long packetTick,long sequence,Vec3 motion){
        var diagnostics=session.player().worldDiagnostics();
        if(diagnostics!=null)diagnostics.event(event,ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "packetTick",packetTick,"motionSequence",sequence,"motion",motion.toVector3f(),
                "ackId",session.player().getLatencyUtil().currentAcknowledgmentId()));
    }
}
