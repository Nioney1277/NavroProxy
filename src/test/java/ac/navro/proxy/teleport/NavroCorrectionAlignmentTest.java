package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.protocol.BackendType;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import com.google.gson.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;



class NavroCorrectionAlignmentTest {
    @Test void nativeHardAndSmoothModesChooseTheSameCorrectionPacketsThroughRaknet() throws Exception {
        try(var in=new InputStreamReader(getClass().getResourceAsStream("/navro-correction-mode-reference.json"))) {
            var modes=JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("modes");
            assertEquals(2,modes.size());
            for(var item:modes)try(var f=new TeleportSenderTest.Fixture(AuthInputPacketsTest.session())) {
                var mode=item.getAsJsonObject();boolean smooth=mode.get("smooth").getAsBoolean();
                f.state.setSmoothSetback(smooth);
                f.state.observeClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO));
                var outbound=f.flush();assertEquals(2,outbound.size());
                var first=outbound.get(0);
                assertEquals(mode.get("packet").getAsString().equals("CorrectPlayerMovePredictionPacket"),
                        first instanceof CorrectPlayerMovePredictionPacket);
                assertEquals(!smooth,f.state.teleports().isHardTeleporting());
                assertEquals(smooth,f.state.teleports().hasPendingCorrection());
                f.ack(outbound);
                assertFalse(f.state.teleports().hasPendingCorrection());
                var current=AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0));
                if(!smooth)current.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
                f.state.observeClient(current);
                assertFalse(f.state.teleports().isTeleporting());
                assertEquals(8,f.state.player().position().x);
                assertEquals(0,f.state.authInputPackets().lastResult().rawOffset());
            }
        }
    }

    @Test void correctionConfirmationKeepsNativeTargetAndHandleRulesWithoutTrustingClientFrameOrder() throws Exception {
        try(var in=new InputStreamReader(getClass().getResourceAsStream("/navro-correction-mode-reference.json"))) {
            var cases=JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("cases");
            assertEquals(16,cases.size());
            for(var item:cases) {
                var row=item.getAsJsonObject();var state=AuthInputPacketsTest.session();
                state.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
                var frame=ac.navro.proxy.teleport.data.RewindHistory.capture(state.authInputPackets().context(),10,state.playerYOffset());
                var reset=new CorrectionResetData(frame);if(row.get("ack").getAsBoolean())reset.accept();
                state.teleports().getQueuedTeleports().add(reset);
                var input=AuthInputPacketsTest.input(row.get("tick").getAsLong(),row.get("target").getAsBoolean()?8:80,64,8,Vec3.ZERO);
                if(row.get("handle").getAsBoolean())input.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
                var processed=new ArrayList<Object>();state.teleports().drainReadyTeleports(input,processed::add);


                int expected=row.get("target").getAsBoolean() && row.get("handle").getAsBoolean()?1:0;
                assertEquals(expected,processed.size(),row.toString());
                assertEquals(1-expected,state.teleports().getQueuedTeleports().size(),row.toString());
            }
        }
    }

    @Test void confirmedHardTeleportFinishesTheNativePredictionStageAndCachesItsCorrectionFrame() {
        var state=AuthInputPacketsTest.session();var moves=AuthInputPacketsTest.transport(state);
        state.observeClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO));assertEquals(1,moves.size());
        var confirmation=AuthInputPacketsTest.input(11,8,64,8,Vec3.ZERO);
        confirmation.setPosition(moves.get(0).getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        state.observeClient(confirmation);
        assertFalse(state.teleports().isTeleporting());
        assertNotNull(state.authInputPackets().lastResult(),"Native post-prediction must complete the confirmation frame");
        assertEquals(11,state.authInputPackets().lastResult().tick());
        assertEquals(0,state.authInputPackets().lastResult().rawOffset());
        assertEquals(64,state.player().position().y,"Confirmation must not execute another travel tick");
        state.rewindPrediction(11);
        assertEquals(2,moves.size(),"Navro caches the confirmed frame in its post-prediction stage");
        assertEquals(moves.get(0).getPosition(),moves.get(1).getPosition());
    }

    @Test void proxySmoothCorrectionDoesNotAcquireTheBdsBackendMotionCutoff() {
        var state=AuthInputPacketsTest.session(BackendType.BDS);state.setSmoothSetback(true);
        var corrections=new ArrayList<CorrectPlayerMovePredictionPacket>();
        state.setSoftCorrectionTransport(packet->{
            state.player().getLatencyUtil().queue(180,true);
            state.onProxyPredictionCorrection(packet);corrections.add(packet);return true;
        });
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setTick(9);
        motion.setMotion(org.cloudburstmc.math.vector.Vector3f.from(0,.4F,0));
        state.player().getLatencyUtil().queue(179,true);state.observeBackend(motion);


        state.observeClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO));
        assertEquals(1,corrections.size());
        assertTrue(state.teleports().hasPendingCorrection());
        assertTrue(state.teleports().getQueuedTeleports().isEmpty());
        state.player().getLatencyUtil().onResponse(180);
        assertFalse(state.teleports().hasPendingCorrection());
        assertTrue(state.teleports().isCorrectionCooldown());
        state.observeClient(AuthInputPacketsTest.input(11,8,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(0,state.authInputPackets().lastResult().rawOffset());
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,
                state.authInputPackets().context().bestPossibility.getType());
    }

    static class FrozenTimer extends ac.navro.proxy.check.impl.timer.Timer {
        FrozenTimer(ac.navro.proxy.state.WirePlayerState player) { super(player,()->1_000_000_000L,ignored->{}); }
    }
    @Test void timerRejectedHardConfirmationReleasesOnlyTheTeleport() {
        var state=AuthInputPacketsTest.session();
        var moves=AuthInputPacketsTest.transport(state);
        state.player().sinceAuthInput=System.currentTimeMillis()-1000;
        assertFalse(state.receiveClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO),0));
        assertEquals(1,moves.size());
        assertTrue(state.teleports().isHardTeleporting());
        state.player().sinceLoadingScreen=200;
        var timer=new FrozenTimer(state.player());
        for(int i=0;i<3;i++)assertFalse(timer.isInvalid());
        state.player().getCheckHolder().put(ac.navro.proxy.check.impl.timer.Timer.class,timer);
        var wrong=AuthInputPacketsTest.input(1000,30,64,8,Vec3.ZERO);
        wrong.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        state.player().sinceAuthInput=System.currentTimeMillis()-120_000;
        assertFalse(state.receiveClient(wrong,0));
        assertTrue(state.teleports().isHardTeleporting());
        var confirm=AuthInputPacketsTest.input(2000,8,64,8,Vec3.ZERO);
        confirm.setPosition(moves.getFirst().getPosition());
        confirm.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        state.player().sinceAuthInput=System.currentTimeMillis()-120_000;
        assertFalse(state.receiveClient(confirm,0));
        assertEquals("Timer",state.anomalies().getLast().kind());
        assertFalse(state.teleports().isTeleporting());
        assertEquals(10,state.lastInputTick());
        assertEquals(12,state.player().tick());assertEquals(2000,state.player().simulationFrame());
        assertFalse(state.teleports().getAuthInputHistory().containsKey(11L));
        assertFalse(state.teleports().getAuthInputHistory().containsKey(12L));
        assertEquals(1,moves.size());
    }

    @Test void softCorrectionContinuesForwardingPredictedPositionsWhileAckIsPending() {
        try (var fixture=new TeleportSenderTest.Fixture(AuthInputPacketsTest.session())) {
            var state=fixture.state;
            state.setSmoothSetback(true);
            state.observeClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO));
            var outbound=fixture.flush();
            var correction=assertInstanceOf(CorrectPlayerMovePredictionPacket.class,outbound.getFirst());
            assertEquals(65.62001F+.001F,correction.getPosition().getY());
            var pending=AuthInputPacketsTest.input(11,81,64,8,Vec3.ZERO);
            assertTrue(state.receiveClient(pending,8500));
            assertEquals(8,pending.getPosition().getX());
            assertNotNull(state.authInputPackets().lastResult());
            assertEquals(11,state.authInputPackets().lastResult().tick());
            assertTrue(state.teleports().hasPendingCorrection());
            fixture.ack(outbound);
            assertFalse(state.teleports().hasPendingCorrection());
            assertTrue(state.teleports().isCorrectionCooldown());
            state.observeClient(AuthInputPacketsTest.input(12,8,64,8,new Vec3(0,-.0784F,0)));
            assertFalse(state.teleports().isCorrectionCooldown());
        }
    }

    @Test void softCorrectionSyncsFlagsAndSpeedAtTheCorrectionTick() {
        var state=AuthInputPacketsTest.session();
        state.setSmoothSetback(true);
        var metadata=new SetEntityDataPacket();
        metadata.setRuntimeEntityId(7);
        metadata.setTick(2);
        metadata.getMetadata().setFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.HAS_COLLISION,true);
        state.player().getLatencyUtil().queue(510,true);
        state.observeBackend(metadata);
        assertEquals(0,metadata.getTick());
        var speed=new UpdateAttributesPacket();
        speed.setRuntimeEntityId(7);
        speed.setTick(3);
        speed.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:movement",0,1,.1F));
        state.observeBackend(speed);
        assertEquals(0,speed.getTick());
        state.player().getLatencyUtil().onResponse(510);
        var outbound=new ArrayList<BedrockPacket>();
        state.setClientStateCorrectionTransport(outbound::add);
        state.setSoftCorrectionTransport(packet->{state.onProxyPredictionCorrection(packet);outbound.add(packet);return true;});
        state.observeClient(AuthInputPacketsTest.input(10,80,64,8,Vec3.ZERO));
        assertEquals(3,outbound.size());
        var sync=assertInstanceOf(SetEntityDataPacket.class,outbound.get(0));
        assertEquals(10,sync.getTick());
        assertTrue(sync.getMetadata().getFlags().get(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.HAS_COLLISION));
        var speedSync=assertInstanceOf(UpdateAttributesPacket.class,outbound.get(1));
        assertEquals(10,speedSync.getTick());
        assertEquals(.1F,speedSync.getAttributes().getFirst().getValue());
        assertEquals(10,assertInstanceOf(CorrectPlayerMovePredictionPacket.class,outbound.get(2)).getTick());
    }

    @Test void blockSyncOnlyVisitsMatureAcknowledgedChunks() {
        var state=AuthInputPacketsTest.session();
        state.world().getChunk(0,0).markLoaded(11);
        var positions=new ArrayList<org.cloudburstmc.math.vector.Vector3i>();
        state.teleports().syncBlocks(state.world(),new Vec3(8,64,8),210,positions::add);
        assertTrue(positions.isEmpty());
        state.teleports().syncBlocks(state.world(),new Vec3(8,64,8),211,positions::add);
        assertEquals(27,positions.size());
    }
}
