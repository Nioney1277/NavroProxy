package ac.navro.proxy.check.impl.timer;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimerPredictionInteractionTest {
    private static org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket groundInput(long tick,float x,float y,float z,Vec3 delta) {
        var packet=AuthInputPacketsTest.input(tick,x,y,z,delta);
        packet.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);return packet;
    }
    @Test void timerRejectionCorrectsImmediatelyWithoutChargingTheSkippedMotionToPrediction() {
        var s=AuthInputPacketsTest.session();s.player().sinceLoadingScreen=200;
        var now=new AtomicLong(1_000_000_000L);var alerts=new ArrayList<String>();
        s.player().setAlertSink(alerts::add);s.player().getCheckHolder().put(Timer.class,new Timer(s.player(),now::get,null));
        s.authInputPackets().context().alertThreshold=1e-4F;
        var moves=AuthInputPacketsTest.transport(s);s.player().setVelocity(new Vec3(.4F,-.0784F,0));
        float x=8,speed=.4F;
        for(int i=0;i<3;i++) {
            x+=speed;speed*=.54600006F;
            assertTrue(s.receiveClient(groundInput(100+i,x,64,8,new Vec3(speed,-.0784F,0)),8500));
            assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
        }
        assertTrue(alerts.isEmpty());var prediction=s.authInputPackets().lastResult();
        for(int i=3;i<5;i++) {
            if(i==4)now.addAndGet(50_000_000L);
            x+=speed;speed*=.54600006F;
            assertFalse(s.receiveClient(groundInput(100+i,x,64,8,new Vec3(speed,-.0784F,0)),8500));
            assertSame(prediction,s.authInputPackets().lastResult());
            assertEquals(1,moves.size(),"repeat Timer failures share the pending correction");
            assertTrue(s.teleports().isHardTeleporting());
        }
        assertTrue(alerts.stream().allMatch(value->value.contains("Timer")));
        now.addAndGet(50_000_000L);x+=speed;speed*=.54600006F;
        assertFalse(s.receiveClient(groundInput(105,x,64,8,new Vec3(speed,-.0784F,0)),8500));
        assertSame(prediction,s.authInputPackets().lastResult(),"unconfirmed movement is not simulated or accepted");
        assertTrue(alerts.stream().allMatch(value->value.contains("Timer")),alerts.toString());
        assertEquals(1,moves.size());assertTrue(s.teleports().isHardTeleporting());
        int flags=alerts.size();now.addAndGet(50_000_000L);
        var confirmation=groundInput(106,8,64,8,Vec3.ZERO);
        confirmation.setPosition(moves.getFirst().getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        assertTrue(s.receiveClient(confirmation,8500));assertFalse(s.teleports().isTeleporting());
        now.addAndGet(50_000_000L);var feet=s.player().position();
        assertTrue(s.receiveClient(groundInput(107,feet.x,feet.y,feet.z,new Vec3(0,-.0784F,0)),8500));
        assertEquals(flags,alerts.size(),"confirmed correction must not produce a second prediction flag on normal input");
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
        now.addAndGet(50_000_000L);
        assertFalse(s.receiveClient(groundInput(108,feet.x+5,feet.y,feet.z,Vec3.ZERO),8500));
        assertTrue(alerts.stream().anyMatch(value->!value.contains("Timer")),"movement checks resume immediately after correction");
        assertEquals(2,moves.size());
    }
    @Test void unavailableCorrectionTransportDoesNotCreateAPhantomTeleportAndTheNextFailureRetries() {
        var s=AuthInputPacketsTest.session();s.player().sinceLoadingScreen=200;
        var now=new AtomicLong(1_000_000_000L);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.player().getCheckHolder().put(Timer.class,new Timer(s.player(),now::get,null));
        var moves=AuthInputPacketsTest.transport(s);var available=new java.util.concurrent.atomic.AtomicBoolean();
        s.setTeleportTransport(packet->{if(!available.get())return false;moves.add(packet);s.onProxyTeleport(packet);return true;});
        for(int i=0;i<3;i++)assertTrue(s.receiveClient(groundInput(100+i,8,64,8,new Vec3(0,-.0784F,0)),8500));
        assertFalse(s.receiveClient(groundInput(103,9,64,8,Vec3.ZERO),8500));
        assertFalse(s.teleports().isTeleporting());assertTrue(moves.isEmpty());
        available.set(true);assertFalse(s.receiveClient(groundInput(104,10,64,8,Vec3.ZERO),8500));
        assertTrue(s.teleports().isHardTeleporting());assertEquals(1,moves.size());
        assertTrue(alerts.stream().allMatch(value->value.contains("Timer")));
    }
    @Test void smoothTimerCorrectionKeepsPredictionEnabledDuringAckAndCooldown() {
        var s=AuthInputPacketsTest.session();s.player().sinceLoadingScreen=200;s.setSmoothSetback(true);
        var now=new AtomicLong(1_000_000_000L);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.player().getCheckHolder().put(Timer.class,new Timer(s.player(),now::get,null));
        var corrections=new ArrayList<org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket>();
        s.setSoftCorrectionTransport(packet->{
            if(corrections.isEmpty())s.player().getLatencyUtil().sentQueue().add(new ac.navro.proxy.util.LatencyUtil.Latency(800,now.get()/1_000_000,now.get(),true,new ArrayList<>()));
            s.onProxyPredictionCorrection(packet);corrections.add(packet);return true;
        });
        for(int i=0;i<3;i++)assertTrue(s.receiveClient(groundInput(100+i,8,64,8,new Vec3(0,-.0784F,0)),8500));
        assertFalse(s.receiveClient(groundInput(103,20,64,8,Vec3.ZERO),8500));
        assertEquals(1,corrections.size());assertEquals(102,corrections.getFirst().getTick());
        assertTrue(s.teleports().hasPendingCorrection());assertFalse(s.teleports().isHardTeleporting());
        now.addAndGet(50_000_000L);assertFalse(s.receiveClient(groundInput(104,20,64,8,Vec3.ZERO),8500));
        now.addAndGet(50_000_000L);assertTrue(s.receiveClient(groundInput(105,20,64,8,Vec3.ZERO),8500));
        assertEquals(8,s.player().position().x);assertEquals(1,corrections.size());
        assertTrue(alerts.stream().anyMatch(value->!value.contains("Timer")),"Prediction checks remain active while the correction acknowledgment is pending");
        int pendingFlags=alerts.size();
        assertTrue(s.player().getLatencyUtil().onResponse(800));assertTrue(s.teleports().isCorrectionCooldown());
        now.addAndGet(50_000_000L);assertTrue(s.receiveClient(groundInput(106,20,64,8,Vec3.ZERO),8500));
        assertFalse(s.teleports().isCorrectionCooldown());assertEquals(8,s.player().position().x);
        assertTrue(alerts.size()>pendingFlags,"Prediction checks remain active during correction cooldown");
        assertEquals(2,corrections.size());
        now.addAndGet(50_000_000L);assertTrue(s.receiveClient(groundInput(107,8,64,8,new Vec3(0,-.0784F,0)),8500));
        assertTrue(alerts.stream().anyMatch(value->!value.contains("Timer")),"Prediction checks remain active while a smooth correction is pending");
        int flags=alerts.size();
        now.addAndGet(50_000_000L);s.receiveClient(groundInput(108,20,64,8,Vec3.ZERO),8500);
        assertTrue(alerts.size()>flags,"later movement errors are also checked");
        assertTrue(alerts.stream().anyMatch(value->!value.contains("Timer")),"no timer-wide movement exemption");
        assertEquals(3,corrections.size());
    }
}
