package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.*;
import ac.navro.proxy.check.api.impl.PingBasedCheck;
import ac.navro.proxy.check.impl.badpackets.BadPacketsC;
import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;


class LiveDetectionTest {
    record Fixture(SessionState state,AtomicLong clock,List<String> alerts,Timer positive,NegativeTimer negative) {
        static Fixture create(boolean detection)throws Exception{

            var state=AuthInputPacketsTest.session();var p=state.player();p.rebasePosition(new Vec3(0,64,0),false);
            state.teleports().setLastKnowValid(new Vec3(0,65.62001F,0));
            p.setProtocolVersion(2193);p.setName("TimerTest");p.inLoadingScreen=false;p.sinceLoadingScreen=0;p.commitInputTick(0);var clock=new AtomicLong(1_000_000_000L);p.sinceAuthInput=1000;var alerts=new ArrayList<String>();p.setAlertSink(alerts::add);
            p.loadingScreenStartMs=0;
            var timer=new Timer(p,clock::get,null);var negative=new NegativeTimer(p,clock::get,null);p.getCheckHolder().put(Timer.class,timer);p.getCheckHolder().put(NegativeTimer.class,negative);state.setDetectionEnabled(detection);return new Fixture(state,clock,alerts,timer,negative);
        }
        boolean input(long tick,long elapsed){clock.addAndGet(elapsed);return state.receiveClient(packet(tick),8500);}
        void ack(long id,long sent){state.player().getLatencyUtil().sentQueue().add(new LatencyUtil.Latency(id,sent/1_000_000L,sent,true,List.of()));var response=new NetworkStackLatencyPacket();response.setTimestamp(id*1_000_000L);assertTrue(NetworkLatencyPackets.onPacket(state.player(),response));}
    }
    static PlayerAuthInputPacket packet(long tick){return AuthInputPacketsTest.input(tick,0,64,0,new Vec3(0,-.0784F,0));}

    @Test void checksAlwaysRunAndTheObsoleteInspectSwitchIsRejected(){
        assertEquals(ProxyConfig.defaults(),ProxyConfig.parse(new String[0]));
        assertThrows(IllegalArgumentException.class,()->ProxyConfig.parse(new String[]{"--inspect"}));
        assertThrows(IllegalArgumentException.class,()->ProxyConfig.parse(new String[]{"--mode","inspect"}));
    }
    @Test void normalInputsAdvanceInternalTimeOnceRegardlessOfClaimedFrame()throws Exception{
        var f=Fixture.create(true);
        for(int i=1;i<=50;i++){assertTrue(f.input(i%3,50000000L));assertEquals(i,f.state.player().tick());assertEquals(i%3,f.state.player().simulationFrame());}
        assertTrue(f.alerts.isEmpty());
    }
    @Test void timerRejectsWithoutUndoingItsInternalArrivalOrForcingTeleport()throws Exception{
        var f=Fixture.create(true);f.state.player().sinceLoadingScreen=200;
        assertTrue(f.input(1000,50000000L));boolean rejected=false;
        for(int i=0;i<10;i++)if(!f.input(1000,0)){rejected=true;break;}
        assertTrue(rejected);assertTrue(f.state.player().tick()>1);assertEquals(0,f.state.player().pendingCorrectionRequests());
        assertTrue(f.alerts.stream().anyMatch(x->x.contains("Timer")));
    }
    @Test void duplicateFramesAreAcceptedInBothDetectionAndObservation()throws Exception{
        for(boolean detect:new boolean[]{true,false}){
            var f=Fixture.create(detect);assertTrue(f.input(100,50000000L));assertTrue(f.input(100,0));
            assertEquals(2,f.state.player().tick());assertTrue(f.alerts.isEmpty());
        }
    }
    @Test void liveAcksReachBothTimersAfterTheirTasksAndUnknownAcksDoNotConsumeMarkers()throws Exception{
        var f=Fixture.create(true);f.state.player().sinceLoadingScreen=200;assertTrue(f.input(1,50_000_000L));assertTrue(f.input(2,50_000_000L));assertFalse(f.negative.budget.beforeAuthInput);assertTrue(f.input(3,50_000_000L));assertTrue(f.positive.budget.beforeAuthInput);assertTrue(f.negative.budget.beforeAuthInput);
        long sent=f.clock.get()-10_000_000L;f.ack(77,sent);assertFalse(f.positive.budget.beforeAuthInput);assertFalse(f.negative.budget.beforeAuthInput);assertEquals(sent,f.negative.budget.lastMovementPlayerClock);
        var events=new ArrayList<String>();var p=f.state.player();p.getCheckHolder().put(Probe.class,new Probe(p,events));p.getLatencyUtil().queue(88,true,()->events.add("task"));assertFalse(p.getLatencyUtil().onResponse(999));assertTrue(events.isEmpty());assertTrue(p.getLatencyUtil().onResponse(88));assertEquals(List.of("task","ping"),events);
        f.state.setDetectionEnabled(false);p.getLatencyUtil().queue(89,true,()->events.add("inspect-task"));assertTrue(p.getLatencyUtil().onResponse(89));assertEquals(List.of("task","ping","inspect-task"),events);
    }
    static class Probe extends PingBasedCheck{final List<String> events;Probe(WirePlayerState p,List<String> e){super(p);events=e;}@Override public void onLatencyAccepted(LatencyUtil.Latency latency){events.add("ping");}}
    @Test void deathAndRespawnResetNegativeTimerOnlyWhenTheOutboundStateIsAcknowledged()throws Exception{
        var f=Fixture.create(true);var p=f.state.player();p.getLatencyUtil().queue(91,true);var death=new SetHealthPacket();death.setHealth(0);PacketListener.onPacketSent(p,death);assertFalse(p.acknowledgedDead);f.negative.budget.balance=-99;assertTrue(p.getLatencyUtil().onResponse(91));assertTrue(p.acknowledgedDead);assertEquals(NegativeTimer.DRIFT_NS,f.negative.budget.balance);
        p.getLatencyUtil().queue(92,true);var alive=new SetHealthPacket();alive.setHealth(20);PacketListener.onPacketSent(p,alive);assertTrue(p.acknowledgedDead);f.negative.budget.balance=-99;assertTrue(p.getLatencyUtil().onResponse(92));assertFalse(p.acknowledgedDead);assertEquals(NegativeTimer.DRIFT_NS,f.negative.budget.balance);
    }
}
