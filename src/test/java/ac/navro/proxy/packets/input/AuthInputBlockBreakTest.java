package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.BackendBlockSnapshots;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthInputBlockBreakTest {
    private static final Vector3i FLOOR=Vector3i.from(8,63,8);
    private record Definition(int runtimeId) implements BlockDefinition {
        public int getRuntimeId(){return runtimeId;}
    }
    private static int id(SessionState s,String name){
        return NetworkBlockStateRegistry.forProtocol(s.player().protocolVersion(),false).states().values().stream()
                .filter(b->b.is(name)&&b.blockData()==0).findFirst().orElseThrow().networkId();
    }
    private static LevelChunkPacket chunk(SessionState s){
        var p=new LevelChunkPacket();p.setChunkX(0);p.setChunkZ(0);p.setDimension(0);p.setSubChunksLength(1);
        var data=Unpooled.buffer();data.writeByte(9).writeByte(2).writeByte(3);
        for(int value:new int[]{id(s,"minecraft:stone"),s.world().airNetworkId()}){
            data.writeByte(1);VarInts.writeInt(data,value);
        }
        p.setData(data);return p;
    }
    private static SessionState session(int protocol){
        var s=new SessionState();s.setProtocolVersion(protocol);s.player().setProtocolVersion(protocol);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setPlayerPosition(Vector3f.from(8.5,64,8.5));s.observeBackend(start);
        var chunk=chunk(s);try{s.observeBackend(chunk);}finally{chunk.release();}
        s.observeClient(new SetLocalPlayerAsInitializedPacket());s.player().inLoadingScreen=false;s.player().sinceLoadingScreen=20;
        s.player().setCollisionResult(false,false,true);s.setDetectionEnabled(true);
        s.observeClient(input(10,64));return s;
    }
    private static PlayerBlockActionData action(PlayerActionType type,Vector3i pos){
        var a=new PlayerBlockActionData();a.setAction(type);a.setBlockPosition(pos);a.setFace(1);return a;
    }
    private static PlayerAuthInputPacket input(long tick,float y,PlayerBlockActionData... actions){
        var p=AuthInputPacketsTest.input(tick,8.5F,y,8.5F,new Vec3(0,y<64?-.155232F:-.0784F,0));
        if(actions.length>0){p.getInputData().add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);p.getPlayerActions().addAll(List.of(actions));}
        return p;
    }
    private static void start(SessionState s){s.observeClient(input(11,64,action(PlayerActionType.START_BREAK,FLOOR)));}
    private static void finish(SessionState s){s.observeClient(input(12,64-.0784F,
            action(PlayerActionType.BLOCK_CONTINUE_DESTROY,FLOOR),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR)));}
    private static int raw(SessionState s){return s.world().getRawBlockAt(8,63,8,0);}
    private static UpdateBlockPacket update(SessionState s,String name){
        var p=new UpdateBlockPacket();p.setBlockPosition(FLOOR);p.setDefinition(new Definition(id(s,name)));return p;
    }

    @Test void reportedFootBlockBreakIsPredictedBeforeCollisionOnBothLiveProtocols(){
        for(int protocol:new int[]{2168,2193}){
            var s=session(protocol);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
            var logs=new ArrayList<Map<String,Object>>();s.player().setWorldDiagnostics(new WorldCollisionDiagnostics(s.player(),s.world(),"dig",logs::add));
            start(s);var p=input(12,64-.0784F,action(PlayerActionType.BLOCK_CONTINUE_DESTROY,FLOOR),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR));
            assertTrue(s.receiveClient(p,8500));
            assertEquals(s.world().airNetworkId(),raw(s));assertTrue(s.world().isPredictedBlockBreak(FLOOR));
            assertEquals(64-.0784F,s.player().position().y,1E-5F);assertEquals(-.0784F,s.authInputPackets().context().afterCollision.y,1E-5F);
            assertTrue(s.authInputPackets().lastResult().rawOffset()<1E-4F);assertTrue(alerts.isEmpty(),alerts.toString());
            assertEquals(2,p.getPlayerActions().size());assertTrue(p.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS));
            assertTrue(logs.stream().anyMatch(r->"block_break_validated".equals(r.get("event"))&&((Number)r.get("revisionAfter")).longValue()>((Number)r.get("revisionBefore")).longValue()));
            assertFalse(logs.stream().anyMatch(r->"phase_hit".equals(r.get("event"))));
        }
    }
    @Test void ordinaryPhaseStillDetectsEnteringAnUnbrokenFloor(){
        var s=session(2193);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.observeClient(input(11,64-.0784F));assertEquals(id(s,"minecraft:stone"),raw(s));
        assertTrue(alerts.stream().anyMatch(a->a.contains("Phase")),alerts.toString());
    }
    @Test void rejectedCompletionFiltersTheForwardedActionsAndCorrectsBothServerLayers(){
        var s=session(2193);var corrections=new ArrayList<BackendBlockSnapshots.Blocks>();s.setBlockCorrectionTransport((p,b)->{assertEquals(FLOOR,p);corrections.add(b);});
        var p=input(11,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR));s.observeClient(p);
        assertTrue(p.getPlayerActions().isEmpty());assertFalse(p.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS));
        assertEquals(List.of(new BackendBlockSnapshots.Blocks(id(s,"minecraft:stone"),s.world().airNetworkId())),corrections);
        assertFalse(s.world().isPredictedBlockBreak(FLOOR));assertEquals(id(s,"minecraft:stone"),raw(s));
    }
    @Test void lateOlderChunkAckCannotReplaceAnAcceptedLiveBreak(){
        var s=session(2193);s.player().getLatencyUtil().queue(90,true);var old=chunk(s);
        try{s.observeBackend(old);}finally{old.release();}
        start(s);finish(s);assertTrue(s.player().getLatencyUtil().onResponse(90));
        assertEquals(s.world().airNetworkId(),raw(s));assertTrue(s.world().isPredictedBlockBreak(FLOOR));
    }
    @Test void backendRejectionOnlyRestoresCollisionTerrainAtItsAcknowledgment(){
        var s=session(2193);start(s);finish(s);s.player().getLatencyUtil().queue(90,true);s.observeBackend(update(s,"minecraft:stone"));
        assertEquals(s.world().airNetworkId(),raw(s));assertTrue(s.world().isPredictedBlockBreak(FLOOR));
        assertTrue(s.player().getLatencyUtil().onResponse(90));assertEquals(id(s,"minecraft:stone"),raw(s));assertFalse(s.world().isPredictedBlockBreak(FLOOR));
    }
    @Test void correctionUsesLatestBackendSnapshotWhileCollisionStillWaitsForAck(){
        var s=session(2193);start(s);finish(s);s.player().getLatencyUtil().queue(90,true);s.observeBackend(update(s,"minecraft:dirt"));
        var corrections=new ArrayList<BackendBlockSnapshots.Blocks>();s.setBlockCorrectionTransport((p,b)->corrections.add(b));
        s.observeClient(input(13,64-.0784F,action(PlayerActionType.START_BREAK,FLOOR)));
        assertEquals(List.of(new BackendBlockSnapshots.Blocks(id(s,"minecraft:dirt"),s.world().airNetworkId())),corrections);
        assertEquals(s.world().airNetworkId(),raw(s));assertTrue(s.player().getLatencyUtil().onResponse(90));assertEquals(id(s,"minecraft:dirt"),raw(s));
    }
    @Test void rejectedInputCannotMutateDiggingStateOrPredictAir(){
        var s=session(2193);start(s);var p=input(11,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR));
        assertFalse(s.receiveClient(p,8500));assertFalse(s.world().isPredictedBlockBreak(FLOOR));assertEquals(id(s,"minecraft:stone"),raw(s));
    }
    @Test void inspectionAndExemptionLeaveDiggingActionsAndTerrainUntouched(){
        for(boolean exempt:new boolean[]{false,true}){
            var s=session(2193);if(exempt)s.player().setExempted(true);else s.setDetectionEnabled(false);
            var p=input(11,64,action(PlayerActionType.START_BREAK,FLOOR),action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR));s.observeClient(p);
            assertEquals(2,p.getPlayerActions().size());assertEquals(id(s,"minecraft:stone"),raw(s));assertFalse(s.world().isPredictedBlockBreak(FLOOR));
        }
    }
    @Test void unseenCorrectionTerrainIsNeverInventedFromThePredictionCache(){
        var s=AuthInputPacketsTest.session();var corrections=new ArrayList<BackendBlockSnapshots.Blocks>();s.setBlockCorrectionTransport((p,b)->corrections.add(b));
        s.observeClient(input(11,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR)));
        assertTrue(corrections.isEmpty());assertEquals(id(s,"minecraft:stone"),raw(s));
    }
    @Test void worldEpochChangeCannotCompleteThePreviousDiggingOperation(){
        var s=session(2193);start(s);s.world().clearChunks();var chunk=chunk(s);
        try{s.observeBackend(chunk);}finally{chunk.release();}
        var p=input(12,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR));s.observeClient(p);
        assertTrue(p.getPlayerActions().isEmpty());assertFalse(s.world().isPredictedBlockBreak(FLOOR));assertEquals(id(s,"minecraft:stone"),raw(s));
    }
    @Test void backendSnapshotRejectsUnknownStreamedSectionsAndClearsOnDimensionChange(){
        var s=session(2193);var source=new BackendBlockSnapshots();source.setProtocolVersion(2193);source.observe(new StartGamePacket());
        var stream=new LevelChunkPacket();stream.setChunkX(0);stream.setChunkZ(0);stream.setDimension(0);stream.setRequestSubChunks(true);source.observe(stream);
        assertNull(source.at(FLOOR));var chunk=chunk(s);try{source.observe(chunk);}finally{chunk.release();}
        assertEquals(id(s,"minecraft:stone"),source.at(FLOOR).layerZero());
        var change=new ChangeDimensionPacket();change.setDimension(1);source.observe(change);assertNull(source.at(FLOOR));
    }
    @Test void correctionCannotCrossAnUnacknowledgedSameDimensionReset(){
        var s=session(2193);var corrections=new ArrayList<BackendBlockSnapshots.Blocks>();s.setBlockCorrectionTransport((p,b)->corrections.add(b));
        s.player().getLatencyUtil().queue(90,true);
        var change=new ChangeDimensionPacket();change.setDimension(0);change.setPosition(Vector3f.from(8.5,64,8.5));s.observeBackend(change);
        var chunk=chunk(s);try{s.observeBackend(chunk);}finally{chunk.release();}
        s.observeClient(input(11,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR)));assertTrue(corrections.isEmpty());
        assertTrue(s.player().getLatencyUtil().onResponse(90));
        s.observeClient(input(12,64,action(PlayerActionType.BLOCK_PREDICT_DESTROY,FLOOR)));assertEquals(1,corrections.size());
        assertEquals(id(s,"minecraft:stone"),corrections.get(0).layerZero());
    }
}
