package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.protocol.BackendType;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import ac.navro.proxy.teleport.data.RewindData;
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
                f.ack(outbound);
                var current=AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0));
                if(!smooth)current.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
                f.state.observeClient(current);
                assertFalse(f.state.teleports().isTeleporting());
                assertEquals(8,f.state.player().position().x);
                assertEquals(0,f.state.authInputPackets().lastResult().rawOffset());
            }
        }
    }

    @Test void correctionConfirmationQueueMatchesTheDirectNativeReferenceIncludingLostHandle() throws Exception {
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
                assertEquals(row.get("drained").getAsInt(),processed.size(),row.toString());
                assertEquals(row.get("remaining").getAsInt(),state.teleports().getQueuedTeleports().size(),row.toString());
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
        assertInstanceOf(RewindData.class,state.teleports().getQueuedTeleports().peek());
        state.player().getLatencyUtil().onResponse(180);
        state.observeClient(AuthInputPacketsTest.input(11,8,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(0,state.authInputPackets().lastResult().rawOffset());
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,
                state.authInputPackets().context().bestPossibility.getType());
    }
}
