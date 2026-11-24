package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.badpackets.BadPacketsC;
import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.teleport.data.RewindData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class BoarInputClockTest {
    static class TimerProbe extends ac.navro.proxy.check.impl.timer.Timer {
        TimerProbe(ac.navro.proxy.state.WirePlayerState player,java.util.concurrent.atomic.AtomicLong now) {
            super(player,now::get,ignored->{});
        }
    }
    @Test void clientFrameCannotAccelerateReverseOrResetTheInternalCounter() {
        var session=AuthInputPacketsTest.session();session.player().commitInputTick(-1);
        long[] claimed={9000,9000,3,Long.MAX_VALUE,0,100};
        for(int i=0;i<claimed.length;i++) {
            var input=AuthInputPacketsTest.input(claimed[i],8,64,8,Vec3.ZERO);
            assertTrue(PacketListener.onPacketReceived(session,input,8500));
            assertEquals(i,session.player().tick());assertEquals(claimed[i],session.player().simulationFrame());
            session.player().apply(input,session.world(),null);
            assertEquals(i,session.player().tick(),"observation cannot count the same arrival twice");
        }
    }
    @Test void impossibleNegativeFrameDisconnectsWithoutAdvancingOrFlaggingRespawnCheck() {
        var s=new SessionState();var kicks=new ArrayList<String>();var alerts=new ArrayList<String>();
        s.player().setDisconnectSink(kicks::add);s.player().setAlertSink(alerts::add);s.setDetectionEnabled(true);
        assertFalse(s.receiveClient(AuthInputPacketsTest.input(-1,8,64,8,Vec3.ZERO),8500));
        assertEquals(-1,s.player().tick());assertEquals(1,kicks.size());assertEquals("Impossible tick id=-1",kicks.getFirst());
        assertTrue(alerts.isEmpty());assertInstanceOf(BadPacketsC.class,s.player().getCheckHolder().get(BadPacketsC.class));
    }
    @Test void cancelledTimerInputStillConsumesExactlyOneInternalStep() {
        var s=new SessionState();s.setDetectionEnabled(true);s.player().inLoadingScreen=false;s.player().sinceLoadingScreen=200;
        var now=new java.util.concurrent.atomic.AtomicLong(1000000000L);
        s.player().getCheckHolder().put(ac.navro.proxy.check.impl.timer.Timer.class,new TimerProbe(s.player(),now));
        boolean rejected=false;
        for(int i=0;i<6;i++) {
            boolean accepted=PacketListener.onPacketReceived(s,AuthInputPacketsTest.input(1000,8,64,8,Vec3.ZERO),8500);
            assertEquals(i,s.player().tick());assertEquals(1000,s.player().simulationFrame());
            rejected|=!accepted;
        }
        assertTrue(rejected);assertEquals(0,s.player().pendingCorrectionRequests());
    }
    @Test void livePredictionAndSnapshotsKeepBothClocksAcrossRepeatedAndBackwardFrames() {
        var s=AuthInputPacketsTest.session();s.player().commitInputTick(-1);
        long[] frames={1000,1000,0,999999};
        for(int i=0;i<frames.length;i++) {
            assertTrue(s.receiveClient(AuthInputPacketsTest.input(frames[i],8,64,8,new Vec3(0,-.0784F,0)),8500));
            assertEquals(i,s.player().tick());assertEquals(frames[i],s.player().simulationFrame());
            assertEquals(frames[i],s.teleports().getAuthInputHistory().get((long)i).state().simulationFrame());
        }
        var snapshot=s.player().snapshot(s.fluidState());s.player().commitInputTick(900);
        s.player().restore(snapshot,s.fluidState());assertEquals(3,s.player().tick());assertEquals(999999,s.player().simulationFrame());
        s.player().restoreReplayInput(s.teleports().getAuthInputHistory().get(1L).state(),s.player().aabbResizeRevision());
        assertEquals(1,s.player().tick());assertEquals(1000,s.player().simulationFrame());
    }
    @Test void correctionPacketsUseClientFramesWhileHistoryUsesInternalTicks() {
        var s=AuthInputPacketsTest.session();s.setSmoothSetback(true);
        var corrections=new ArrayList<CorrectPlayerMovePredictionPacket>();
        s.setSoftCorrectionTransport(packet->{corrections.add(packet);s.onProxyPredictionCorrection(packet);return true;});
        s.observeClient(AuthInputPacketsTest.input(50000,80,64,8,Vec3.ZERO));
        assertEquals(10,s.player().tick());assertEquals(1,corrections.size());assertEquals(50000,corrections.getFirst().getTick());
        assertEquals(50000,s.teleports().getAuthInputHistory().get(10L).packet().getTick());
        assertEquals(50000,s.teleports().getAuthInputHistory().get(10L).state().simulationFrame());
    }
    @Test void hardCorrectionCanBeConfirmedAfterClientFrameMovesBackward() {
        var s=AuthInputPacketsTest.session();var corrections=AuthInputPacketsTest.transport(s);
        s.observeClient(AuthInputPacketsTest.input(9000,80,64,8,Vec3.ZERO));assertEquals(1,corrections.size());
        var confirmation=AuthInputPacketsTest.input(0,8,64,8,Vec3.ZERO);
        confirmation.setPosition(corrections.getFirst().getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        s.observeClient(confirmation);
        assertFalse(s.teleports().isHardTeleporting());assertEquals(11,s.player().tick());assertEquals(0,s.player().simulationFrame());
    }
    @Test void backendRewindLooksUpClaimedFrameThenReplaysInternalSequence() {
        var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.observeClient(AuthInputPacketsTest.input(5000,8,64,8,new Vec3(0,-.0784F,0)));
        var correction=new CorrectPlayerMovePredictionPacket();correction.setTick(5000);
        correction.setPosition(org.cloudburstmc.math.vector.Vector3f.from(10,65.62001F,8));
        correction.setDelta(org.cloudburstmc.math.vector.Vector3f.from(0,-.0784F,0));correction.setOnGround(true);
        correction.setPredictionType(PredictionType.PLAYER);
        s.player().getLatencyUtil().queue(919,true);s.observeBackend(correction);
        assertEquals(10,((RewindData)s.teleports().getQueuedTeleports().peek()).inputTick());
        s.observeClient(AuthInputPacketsTest.input(2,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(919);
        s.observeClient(AuthInputPacketsTest.input(5000,10,64,8,new Vec3(0,-.0784F,0)));
        assertFalse(s.teleports().isTeleporting());assertEquals(10,s.player().position().x);
        assertEquals(12,s.player().tick());assertEquals(5000,s.player().simulationFrame());assertTrue(alerts.isEmpty(),alerts.toString());
    }
    @Test void rewindWithBackwardClientFramesAppliesKnockbackOnceThenRunsTheLiveInput() {
        var s=AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(5000,8,64,8,new Vec3(0,-.0784F,0)));
        var correction=new CorrectPlayerMovePredictionPacket();correction.setTick(5000);
        correction.setPosition(org.cloudburstmc.math.vector.Vector3f.from(10,65.62001F,8));
        correction.setDelta(org.cloudburstmc.math.vector.Vector3f.from(0,-.0784F,0));
        correction.setOnGround(true);correction.setPredictionType(PredictionType.PLAYER);
        s.player().getLatencyUtil().queue(920,true);s.observeBackend(correction);
        s.observeClient(AuthInputPacketsTest.input(2,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(920);
        var motion=new org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket();
        motion.setRuntimeEntityId(7);motion.setMotion(org.cloudburstmc.math.vector.Vector3f.from(0,.4F,0));
        s.player().getLatencyUtil().queue(921,true);s.observeBackend(motion);
        s.player().getLatencyUtil().onResponse(921);
        s.observeClient(AuthInputPacketsTest.input(Long.MAX_VALUE,10,64.7136F,8,new Vec3(0,.22892801F,0)));
        assertFalse(s.teleports().isTeleporting());assertEquals(12,s.player().tick());
        assertEquals(Long.MAX_VALUE,s.player().simulationFrame());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
        assertEquals(64.7136F,s.player().position().y);assertEquals(.22892801F,s.player().velocity().y);
        assertNull(s.authInputPackets().context().certainVelocity);
    }
    @Test void repeatedClientFrameSelectsLatestHistoryAndCannotRebindQueuedCorrection() {
        var s=AuthInputPacketsTest.session();
        for(long frame:new long[]{5000,2,5000})
            s.observeClient(AuthInputPacketsTest.input(frame,8,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(12L,s.inputTickForSimulationFrame(5000));
        var correction=new CorrectPlayerMovePredictionPacket();correction.setTick(5000);
        correction.setPosition(org.cloudburstmc.math.vector.Vector3f.from(10,65.62001F,8));
        correction.setDelta(org.cloudburstmc.math.vector.Vector3f.from(0,-.0784F,0));
        correction.setOnGround(true);correction.setPredictionType(PredictionType.PLAYER);
        s.player().getLatencyUtil().queue(922,true);s.observeBackend(correction);
        var queued=(RewindData)s.teleports().getQueuedTeleports().peek();assertEquals(12,queued.inputTick());
        s.observeClient(AuthInputPacketsTest.input(5000,8,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(12,queued.inputTick());s.player().getLatencyUtil().onResponse(922);
        s.observeClient(AuthInputPacketsTest.input(0,10,64,8,new Vec3(0,-.0784F,0)));
        assertFalse(s.teleports().isTeleporting());assertEquals(14,s.player().tick());
        assertEquals(0,s.player().simulationFrame());assertEquals(0,s.authInputPackets().lastResult().rawOffset());
    }
}
