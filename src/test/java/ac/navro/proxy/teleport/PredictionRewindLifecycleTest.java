package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.teleport.data.RewindData;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Constructed protocol scenarios. Expectations are authoritative server targets,
 * stationary support and a wall's geometric contact plane, not a second simulator. */
class PredictionRewindLifecycleTest {
    private static ac.navro.proxy.SessionState bdsSession() {
        return AuthInputPacketsTest.session(ac.navro.proxy.protocol.BackendType.BDS);
    }
    private static CorrectPlayerMovePredictionPacket correction(long tick, float x, Vec3 delta) {
        var p=new CorrectPlayerMovePredictionPacket();p.setTick(tick);p.setPosition(Vector3f.from(x,65.62001F,8));
        p.setDelta(delta.toVector3f());p.setOnGround(true);p.setPredictionType(PredictionType.PLAYER);
        p.setVehicleRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);return p;
    }
    @Test void acknowledgedBackendCorrectionRestoresTheTargetBeforeCheckingLiveInput() {
        var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(91,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        assertInstanceOf(RewindData.class,s.teleports().getQueuedTeleports().peek());assertFalse(s.teleports().isHardTeleporting());
        assertEquals(8,s.player().position().x);s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(8,s.player().position().x);s.player().getLatencyUtil().onResponse(91);assertEquals(8,s.player().position().x);
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertFalse(s.teleports().isTeleporting());assertEquals(10,s.player().position().x);
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertTrue(alerts.isEmpty(),alerts.toString());
    }
    @Test void historicalInputsReallyRunCollisionAndLiveInputChecksTheReplayedState() {
        var s=AuthInputPacketsTest.session();
        int stone=ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream().filter(b->b.is("minecraft:stone")&&b.blockData()==0).findFirst().orElseThrow().networkId();
        for(int y=64;y<67;y++)s.world().updateBlock(11,y,8,0,stone);
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(92,true);s.observeBackend(correction(10,10,new Vec3(.4F,0,0)));
        for(int tick=11;tick<=13;tick++)s.observeClient(AuthInputPacketsTest.input(tick,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(92);
        s.observeClient(AuthInputPacketsTest.input(14,10.7F,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(10.7F,s.player().position().x,1e-6F);assertEquals(11F,s.player().boundingBox().maxX,1e-6F);
        assertEquals(0,s.player().velocity().x);assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
    }

    @Test void bdsCorrectionCutoffUsesTheRaknetClosingBatch() {
        try(var f=new TeleportSenderTest.Fixture(bdsSession())) {
            var s=f.state;s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
            var earlierMotion=new SetEntityMotionPacket();earlierMotion.setRuntimeEntityId(7);
            earlierMotion.setTick(9);earlierMotion.setMotion(Vector3f.from(0,.4F,0));
            s.observeBackend(earlierMotion);f.client.sendPacket(earlierMotion);s.onBackendPacketQueued(earlierMotion);
            var correction=correction(10,10,new Vec3(0,-.0784F,0));
            s.observeBackend(correction);f.client.sendPacket(correction);s.onBackendPacketQueued(correction);
            var outbound=f.flush();assertInstanceOf(SetEntityMotionPacket.class,outbound.get(0));assertInstanceOf(CorrectPlayerMovePredictionPacket.class,outbound.get(1));
            assertFalse(s.teleports().getQueuedTeleports().peek().isAccepted());
            s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
            f.ack(outbound);assertEquals(8,s.player().position().x);
            s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
            assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertEquals(10,s.player().position().x);
            assertFalse(s.teleports().isTeleporting());
        }
    }

    @Test void acknowledgedBdsRewindSupersedesAnEarlierHardCorrectionInWireOrder() {
        var state = bdsSession();
        var deferred = new ArrayList<Runnable>();
        state.setBdsCorrectionAcknowledgmentDispatcher(deferred::add);
        var moves = AuthInputPacketsTest.transport(state);
        state.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        state.observeClient(AuthInputPacketsTest.input(11, 80, 64, 8, Vec3.ZERO));
        assertEquals(1, moves.size());
        assertTrue(state.teleports().isHardTeleporting());
        state.player().getLatencyUtil().queue(96, true);
        state.observeBackend(correction(10, 10, new Vec3(0, -.0784F, 0)));
        state.player().getLatencyUtil().onResponse(96);
        var mismatched = AuthInputPacketsTest.input(12, 10, 64, 8, new Vec3(0, -.0784F, 0));
        mismatched.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HANDLE_TELEPORT);
        state.observeClient(mismatched);
        assertTrue(state.teleports().isHardTeleporting());
        assertEquals(1, deferred.size());
        deferred.getFirst().run();
        assertFalse(state.teleports().isHardTeleporting());
        assertInstanceOf(RewindData.class, state.teleports().getQueuedTeleports().peek());
        state.observeClient(AuthInputPacketsTest.input(13, 10, 64, 8, new Vec3(0, -.0784F, 0)));
        assertFalse(state.teleports().isTeleporting());
        assertEquals(10, state.player().position().x);
        assertEquals(1, moves.size());
    }

    @Test void acknowledgedBackendTeleportSupersedesOlderHardCorrection() {
        var state = AuthInputPacketsTest.session();
        var moves = AuthInputPacketsTest.transport(state);
        state.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        state.observeClient(AuthInputPacketsTest.input(11, 80, 64, 8, Vec3.ZERO));
        assertEquals(1, moves.size());
        state.player().getLatencyUtil().onResponse(1000);
        assertTrue(state.teleports().isHardTeleporting());

        var teleport = new MovePlayerPacket();
        teleport.setRuntimeEntityId(7);
        teleport.setMode(MovePlayerPacket.Mode.TELEPORT);
        teleport.setPosition(Vector3f.from(10, 65.62001F, 8));
        teleport.setRotation(Vector3f.ZERO);
        state.player().getLatencyUtil().queue(1100, true);
        state.observeBackend(teleport);
        assertTrue(state.teleports().isHardTeleporting());
        state.observeClient(AuthInputPacketsTest.input(12, 10, 64, 8, Vec3.ZERO));
        assertTrue(state.teleports().isHardTeleporting());

        state.player().getLatencyUtil().onResponse(1100);
        assertFalse(state.teleports().isHardTeleporting());
        assertTrue(state.teleports().isTeleporting());
        var next = AuthInputPacketsTest.input(13, 10, 64, 8, Vec3.ZERO);
        state.observeClient(next);
        assertFalse(state.teleports().isTeleporting());
        assertEquals(10, state.player().position().x);
        assertEquals(1, moves.size());
    }

    @Test void bdsRewindKeepsPredictionEnabledOnTheFrameThatAppliesIt() {
        var state = bdsSession();
        var moves = AuthInputPacketsTest.transport(state);
        state.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        state.player().getLatencyUtil().queue(97, true);
        state.observeBackend(correction(10, 10, new Vec3(0, -.0784F, 0)));
        state.player().getLatencyUtil().onResponse(97);

        var staleInput = AuthInputPacketsTest.input(11, 8, 64, 8, new Vec3(0, -.0784F, 0));
        state.observeClient(staleInput);
        assertEquals(2F, state.authInputPackets().lastResult().rawOffset());
        assertFalse(state.authInputPackets().lastResult().accepted());
        assertEquals(10F, staleInput.getPosition().getX());
        assertTrue(state.teleports().isHardTeleporting());
        assertEquals(1,moves.size(),"A discrepant live input is checked even on the frame that applies the backend rewind");

        state.observeClient(AuthInputPacketsTest.input(12, 80, 64, 8, Vec3.ZERO));
        assertEquals(1, moves.size());
        assertTrue(state.teleports().isHardTeleporting());
    }

    @Test void replayKeepsNewlyAcknowledgedMetadataAndTheLiveInputTick() {
        var s=AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(93,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(93);
        var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(7);
        metadata.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.HEIGHT,1.49F);
        s.player().getLatencyUtil().queue(94,true);s.observeBackend(metadata);s.player().getLatencyUtil().onResponse(94);
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(12,s.player().tick());assertEquals(1.49F,s.player().dimensions().height());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
    }

    @Test void historicalReplayCannotRollBackMetadataStillWaitingForItsOwnAck() {
        var s = AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(170,true);
        s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(170);
        var metadata = new SetEntityDataPacket(); metadata.setRuntimeEntityId(7);
        metadata.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.HEIGHT,1.49F);
        s.player().getLatencyUtil().queue(171,true); s.observeBackend(metadata);
        long revision = s.player().latestPoseMetadataRevision();
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(1.8F,s.player().dimensions().height());
        assertEquals(revision,s.player().latestPoseMetadataRevision());
        s.player().getLatencyUtil().onResponse(171);
        s.observeClient(AuthInputPacketsTest.input(13,10,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(1.49F,s.player().dimensions().height());
        assertEquals(revision,s.player().acknowledgedPoseMetadataRevision());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
    }

    @Test void acknowledgedKnockbackIsConsumedByTheFirstReplayPredictionAsInNavro() {
        var s=AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(95,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(95);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setMotion(Vector3f.from(0,.4F,0));
        s.player().getLatencyUtil().queue(96,true);s.observeBackend(motion);s.player().getLatencyUtil().onResponse(96);
        // Navro TeleportHandler.processRewind runs tick 11 and then live tick 12.
        // The one acknowledged .4 impulse belongs to the first prediction, not both.
        s.observeClient(AuthInputPacketsTest.input(12,10,64.7136F,8,new Vec3(0,.22892801F,0)));
        assertEquals(64.7136F,s.player().position().y);assertEquals(.22892801F,s.player().velocity().y);
        assertNull(s.authInputPackets().context().certainVelocity);
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.NORMAL,s.authInputPackets().context().bestPossibility.getType());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
    }
    @Test void navroCorrectionKeepsTheAcknowledgedImpulseEvenWhenItPrecedesTheCorrection() {
        var s = AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        var motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(7);
        motion.setTick(9); motion.setMotion(Vector3f.from(0,.4F,0));
        s.player().getLatencyUtil().queue(160,true); s.observeBackend(motion);
        s.player().getLatencyUtil().onResponse(160);
        s.player().getLatencyUtil().queue(161,true);
        s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(161);
        s.observeClient(AuthInputPacketsTest.input(11,10,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,
                s.authInputPackets().context().bestPossibility.getType());
        assertNull(s.authInputPackets().context().certainVelocity);
    }

    @Test void correctionAtTheCurrentTickWaitsForTheFollowingInputInsteadOfBeingDropped() {
        var s = AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(162,true);
        s.observeBackend(correction(11,10,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(162);
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        assertTrue(s.teleports().isTeleporting()); assertEquals(8,s.player().position().x);
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertFalse(s.teleports().isTeleporting());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
        assertEquals(10,s.player().position().x);
    }

    @Test void missingHistoryUsesAnOrdinaryTeleportInsteadOfAcceptingRawMovement() {
        var s=AuthInputPacketsTest.session();var resets=AuthInputPacketsTest.transport(s);
        s.observeClient(AuthInputPacketsTest.input(30,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(97,true);s.observeBackend(correction(1,10,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(97);
        s.observeClient(AuthInputPacketsTest.input(31,1000,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(1,resets.size());assertEquals(10,resets.get(0).getPosition().getX());
        assertTrue(s.teleports().isTeleporting());assertFalse(s.teleports().isHardTeleporting());
        assertEquals(8,s.player().position().x);
    }

    @Test void liveCrawlActionHasTheSamePoseLifecycleWithAndWithoutCorrectionReplay() {
        var s=AuthInputPacketsTest.session();
        var control=AuthInputPacketsTest.session();control.player().rebasePosition(new Vec3(10,64,8),false);
        control.observeClient(AuthInputPacketsTest.input(10,10,64,8,new Vec3(0,-.0784F,0)));
        control.observeClient(AuthInputPacketsTest.input(11,10,64,8,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(98,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(98);
        var current=AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0));
        current.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_CRAWLING);
        control.observeClient(current.clone());s.observeClient(current);
        assertTrue(s.player().clientCrawling());
        assertEquals(control.player().dimensions(),s.player().dimensions());
        assertEquals(control.player().fluidPrediction().pendingPoseDimensions,s.player().fluidPrediction().pendingPoseDimensions);
        assertEquals(control.player().fluidPrediction().readyPoseDimensions,s.player().fluidPrediction().readyPoseDimensions);
        assertEquals(12,s.player().tick());assertEquals(0,s.authInputPackets().lastResult().rawOffset());
    }

    @Test void aVelocityPreviouslyConsumedByPredictionIsNotRecreatedFromHistoricalInput() {
        var s=AuthInputPacketsTest.session();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setMotion(Vector3f.from(0,.4F,0));
        s.player().getLatencyUtil().queue(110,true);s.observeBackend(motion);s.player().getLatencyUtil().onResponse(110);
        s.observeClient(AuthInputPacketsTest.input(11,8,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,s.authInputPackets().context().bestPossibility.getType());
        assertNull(s.authInputPackets().context().certainVelocity);
        s.player().getLatencyUtil().queue(111,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(111);
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(64,s.player().position().y);assertEquals(-.0784F,s.player().velocity().y);
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.NORMAL,s.authInputPackets().context().bestPossibility.getType());
    }

    @Test void backendCorrectionSupersedesEarlierMotionBeforeHistoricalReplay() {
        var s=bdsSession();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setMotion(Vector3f.from(0,.4F,0));
        s.player().getLatencyUtil().queue(120,true);s.observeBackend(motion);s.player().getLatencyUtil().onResponse(120);
        assertNotNull(s.authInputPackets().context().certainVelocity);
        s.player().getLatencyUtil().queue(121,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(121);
        s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertEquals(64,s.player().position().y);
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.NORMAL,s.authInputPackets().context().bestPossibility.getType());
        assertNull(s.authInputPackets().context().certainVelocity);assertTrue(alerts.isEmpty(),alerts.toString());
    }
    @Test void correctionCutoffPreservesALaterIdenticalMotionInsteadOfComparingVectorValues() {
        var s=bdsSession();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().queue(122,true);
        var old=new SetEntityMotionPacket();old.setRuntimeEntityId(7);old.setMotion(Vector3f.from(0,.4F,0));s.observeBackend(old);
        s.player().getLatencyUtil().onResponse(122);
        s.player().getLatencyUtil().queue(123,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        var newer=new SetEntityMotionPacket();newer.setRuntimeEntityId(7);newer.setMotion(Vector3f.from(0,.4F,0));s.observeBackend(newer);
        s.player().getLatencyUtil().onResponse(123);
        s.observeClient(AuthInputPacketsTest.input(11,10,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertEquals(64.4F,s.player().position().y);
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,s.authInputPackets().context().bestPossibility.getType());
    }

    @Test void historicalCorrectionCannotConsumeMotionWhoseBackendTickIsNewerThanItsBaseline() {
        var s=bdsSession();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setMotion(Vector3f.from(0,.4F,0));motion.setTick(11);
        s.player().getLatencyUtil().queue(124,true);s.observeBackend(motion);s.player().getLatencyUtil().onResponse(124);
        s.player().getLatencyUtil().queue(125,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        s.player().getLatencyUtil().onResponse(125);
        s.observeClient(AuthInputPacketsTest.input(11,10,64.4F,8,new Vec3(0,.3136F,0)));
        assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertEquals(64.4F,s.player().position().y);
        assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,s.authInputPackets().context().bestPossibility.getType());
    }

    @Test void motionDiagnosticsKeepRawAirborneInputsAndDoNotAlterTheObservedPacket() {
        var s=AuthInputPacketsTest.session();var records=new java.util.ArrayList<java.util.Map<String,Object>>();
        var trace=new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(s.player(),s.world(),"recording-contract",records::add);
        s.player().setWorldDiagnostics(trace);
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        trace.beginMotionTrace();var raw=AuthInputPacketsTest.input(11,8,64.42F,8,new Vec3(0,.33319998F,0));
        raw.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_JUMPING);
        var original=raw.getPosition();s.observeClient(raw);
        var entry=records.stream().filter(r->r.get("event").equals("motion_input_raw")).findFirst().orElseThrow();
        var input=(java.util.Map<?,?>)entry.get("rawInput");assertEquals(original.toString(),input.get("position"));
        assertEquals(11L,input.get("tick"));assertTrue(input.get("input").toString().contains("START_JUMPING"));
        assertTrue(records.stream().anyMatch(r->r.get("event").equals("collision_frame")&&Long.valueOf(11).equals(r.get("tick"))));
        assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
    }

    private static Vector3f recordedVector(String value) {
        var parts=value.substring(1,value.length()-1).split(", ");
        return Vector3f.from(Float.parseFloat(parts[0]),Float.parseFloat(parts[1]),Float.parseFloat(parts[2]));
    }
    @Test void recordedCorrectionEndpointsMatchWhenEarlierMotionIsAlreadyIncludedInTheBaseline()throws Exception {
        // Captured motion/correction/current-input endpoints. Intermediate stationary
        // inputs are constructed conditions; this does not claim full gameplay replay.
        try(var in=new java.io.InputStreamReader(getClass().getResourceAsStream("/recorded-motion-correction-endpoints.json"))) {
            var cases=com.google.gson.JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("cases");
            assertEquals(7,cases.size());
            for(var element:cases) {
                var row=element.getAsJsonObject();var c=row.getAsJsonObject("correction");var m=row.getAsJsonObject("motion");var input=row.getAsJsonObject("input");
                long source=c.get("sourceTick").getAsLong(),motionTick=m.get("tick").getAsLong(),live=input.get("tick").getAsLong();
                var position=recordedVector(c.get("position").getAsString());float y=position.getY()-1.62001F;
                var session=bdsSession();var alerts=new ArrayList<String>();session.player().setAlertSink(alerts::add);
                session.player().rebasePosition(new Vec3(position.getX(),y,position.getZ()),false);
                for(long tick=motionTick;tick<=source;tick++) {
                    session.observeClient(AuthInputPacketsTest.input(tick,position.getX(),y,position.getZ(),new Vec3(0,-.0784F,0)));
                    if(tick==motionTick) {
                        var v=m.getAsJsonObject("motion");var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);
                        motion.setTick(m.get("packetTick").getAsLong());motion.setMotion(Vector3f.from(v.get("x").getAsFloat(),v.get("y").getAsFloat(),v.get("z").getAsFloat()));
                        session.player().getLatencyUtil().queue(130,true);session.observeBackend(motion);
                    }
                }
                session.player().getLatencyUtil().onResponse(130);
                var packet=correction(source,position.getX(),new Vec3(recordedVector(c.get("delta").getAsString())));
                packet.setPosition(position);packet.setOnGround(c.get("onGround").getAsBoolean());
                session.player().getLatencyUtil().queue(131,true);session.observeBackend(packet);
                for(long tick=source+1;tick<live;tick++)session.observeClient(AuthInputPacketsTest.input(tick,position.getX(),y,position.getZ(),new Vec3(0,-.0784F,0)));
                session.player().getLatencyUtil().onResponse(131);
                var observed=recordedVector(input.get("position").getAsString());
                // Only the captured endpoint has an independent movement oracle.
                // Constructed preparation at an arbitrary airborne position is not
                // claimed to be a legitimate continuous trajectory.
                alerts.clear();
                var current=AuthInputPacketsTest.input(live,observed.getX(),observed.getY()-1.62001F,observed.getZ(),new Vec3(recordedVector(input.get("delta").getAsString())));
                current.setPosition(observed);current.setRotation(recordedVector(input.get("rotation").getAsString()));session.observeClient(current);
                assertEquals(0,session.authInputPackets().lastResult().rawOffset(),"Recorded endpoint tick "+live);
                assertTrue(alerts.isEmpty(),alerts.toString());
            }
        }
    }

    @Test void raknetInboundBatchFinishesItsOldInputBeforeAcceptingCorrectionAck() {
        var s=bdsSession();
        s.observeClient(AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0)));
        var boundary=new ac.navro.proxy.bds.BdsCorrectionAcknowledgments();
        s.setBdsCorrectionAcknowledgmentDispatcher(boundary::defer);
        s.player().getLatencyUtil().queue(140,true);s.observeBackend(correction(10,10,new Vec3(0,-.0784F,0)));
        var codec=ac.navro.proxy.SupportedCodecs.forProtocol(2193);
        var packetCodec=new org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3();packetCodec.setCodec(codec);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(boundary,
                new org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder(),packetCodec,
                new io.netty.channel.SimpleChannelInboundHandler<org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper>() {
                    protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper wrapper) {
                        if(wrapper.getPacket() instanceof NetworkStackLatencyPacket response)ac.navro.proxy.packets.other.NetworkLatencyPackets.onPacket(s.player(),response);
                        else s.observeClient(wrapper.getPacket());
                    }
                });
        try {
            var response=new NetworkStackLatencyPacket();response.setTimestamp(140_000_000L);
            var raw=io.netty.buffer.Unpooled.buffer();
            var oldInput = AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0));
            oldInput.setPlayMode(org.cloudburstmc.protocol.bedrock.data.ClientPlayMode.NORMAL);
            oldInput.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CROSSHAIR);
            oldInput.setAnalogMoveVector(org.cloudburstmc.math.vector.Vector2f.ZERO);
            oldInput.setCameraOrientation(Vector3f.ZERO);
            oldInput.setInteractRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);
            for(var packet:new BedrockPacket[]{response,oldInput}) {
                var frame=io.netty.buffer.Unpooled.buffer();org.cloudburstmc.protocol.common.util.VarInts.writeUnsignedInt(frame,codec.getPacketDefinition(packet.getClass()).getId());codec.tryEncode(codec.createHelper(),frame,packet);
                org.cloudburstmc.protocol.common.util.VarInts.writeUnsignedInt(raw,frame.readableBytes());raw.writeBytes(frame);frame.release();
            }
            channel.writeInbound(org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper.newInstance(null,raw));
            assertEquals(8,s.player().position().x,"Receipt must not rebase the input already beside the marker");
            assertTrue(s.teleports().getQueuedTeleports().peek().isAccepted());
            s.observeClient(AuthInputPacketsTest.input(12,10,64,8,new Vec3(0,-.0784F,0)));
            assertEquals(0,s.authInputPackets().lastResult().rawOffset());assertEquals(10,s.player().position().x);
        } finally {channel.finishAndReleaseAll();}
    }
}
