package ac.navro.proxy.nethernet;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;






class NetherNetSpawnLifecycleTest {
    private static byte[] recording(String name)throws Exception{
        try(var in=new GZIPInputStream(NetherNetSpawnLifecycleTest.class.getResourceAsStream(
                "/nethernet-bds-2225-spawn-"+name+".batch.gz"))){return in.readAllBytes();}
    }
    private static NetherNetBatchSession session()throws Exception{
        var s=new NetherNetBatchSession(null,null,true, ac.navro.proxy.protocol.BackendType.BDS);
        s.process(false,HexFormat.of().parseHex("06c101000008b1"));
        s.process(true,HexFormat.of().parseHex("0c8f0101000000000000000000"));
        s.process(true,recording("start"));
        var w=s.state().world();
        w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));
        int grass=NetworkBlockStateRegistry.forProtocol(2225,true).states().values().stream()
                .filter(b->b.is("minecraft:grass_block")).findFirst().orElseThrow().networkId();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)w.updateBlock(x,-61,z,0,grass);
        return s;
    }
    private static void ack(NetherNetBatchSession s)throws Exception{
        long id=s.state().player().getLatencyUtil().sentQueue().peekLast().id();
        var p=new NetworkStackLatencyPacket();p.setTimestamp(id*1_000_000L);
        s.process(false,s.encode(p));
    }
    private static void finishLoading(NetherNetBatchSession s)throws Exception{
        var p=new ServerboundLoadingScreenPacket();p.setType(ServerboundLoadingScreenPacketType.END_LOADING_SCREEN);
        s.process(false,s.encode(p));
    }
    private static PlayerAuthInputPacket input(long tick){


        var p=AuthInputPacketsTest.input(tick,.5F,-60,.5F,
                tick==0?Vec3.ZERO:new Vec3(0,-.0784F,0));
        p.setPosition(Vector3f.from(.5F,-58.37999F,.5F));
        p.setPlayMode(ClientPlayMode.NORMAL);p.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        p.setAnalogMoveVector(Vector2f.ZERO);p.setCameraOrientation(Vector3f.ZERO);p.setInteractRotation(Vector2f.ZERO);
        p.getInputData().clear();p.getInputData().add(PlayerAuthInputData.BLOCK_BREAKING_DELAY_ENABLED);
        if(tick>0)p.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
        return p;
    }
    @Test void recordedBdsReadyReplacesPlaceholderOnlyAfterClosingAckWithoutTeleportFlag()throws Exception{
        try(var s=session()){
            var alerts=new ArrayList<String>();s.state().player().setAlertSink(alerts::add);
            assertEquals(32769.62F,s.state().player().position().y);
            byte[] ready=recording("ready");
            assertArrayEquals(ready,s.process(true,ready).forwarded());
            s.process(false,s.encode(new SetLocalPlayerAsInitializedPacket()));
            var waiting=s.process(false,s.encode(input(0)));
            assertTrue(waiting.backendbound().isEmpty(),"Never rewrite pending spawn input to the placeholder position");
            assertNull(s.state().authInputPackets().lastResult());
            assertEquals(32769.62F,s.state().player().position().y);
            ack(s);
            assertEquals(-60,s.state().player().position().y);
            assertEquals(-58.37999F,s.state().teleports().getLastKnowValid().y);
            finishLoading(s);
            for(int tick=1;tick<=12;tick++){
                var result=s.process(false,s.encode(input(tick)));
                assertEquals(1,result.backendbound().size());
                var prediction=s.state().authInputPackets().lastResult();
                assertNotNull(prediction);assertTrue(prediction.rawOffset()<.0001F,prediction.toString());
                assertEquals(-60,s.state().player().position().y);
            }
            assertTrue(alerts.isEmpty(),alerts.toString());


            var invalid=input(13);invalid.setPosition(Vector3f.from(.5F,-59.0F,.5F));
            s.process(false,s.encode(invalid));
            assertTrue(alerts.stream().anyMatch(a->a.contains("Phase")),alerts.toString());
        }
    }
    @Test void readyBeforeActorInitializationAndAnotherActorsReadyCannotRebaseLocalPlayer()throws Exception{
        try(var s=session()){
            s.process(true,recording("ready"));ack(s);
            assertEquals(-60,s.state().player().position().y);
            assertFalse(s.state().spawned());
            var other=new RespawnPacket();other.setRuntimeEntityId(999);other.setState(RespawnPacket.State.SERVER_READY);
            other.setPosition(Vector3f.from(20,80,20));s.process(true,s.encode(other));
            if(!s.state().player().getLatencyUtil().sentQueue().isEmpty())ack(s);
            assertEquals(-60,s.state().player().position().y);
        }
    }
    @Test void aLaterSpawnWaitsForItsPositionAndPositiveHealthAcknowledgments()throws Exception{
        try(var s=session()){
            s.process(true,recording("ready"));ack(s);
            s.process(false,s.encode(new SetLocalPlayerAsInitializedPacket()));
            finishLoading(s);
            var dead=new SetHealthPacket();dead.setHealth(0);s.process(true,s.encode(dead));ack(s);
            var search=new RespawnPacket();search.setState(RespawnPacket.State.SERVER_SEARCHING);
            search.setPosition(Vector3f.from(.5F,32768,.5F));s.process(true,s.encode(search));
            var ready=new RespawnPacket();ready.setState(RespawnPacket.State.SERVER_READY);
            ready.setPosition(Vector3f.from(8,65.62001F,8));s.process(true,s.encode(ready));ack(s);
            assertEquals(64,s.state().player().position().y);
            assertTrue(s.state().player().awaitingRespawnHealth());
            var p=input(0);p.setPosition(ready.getPosition());
            assertTrue(s.process(false,s.encode(p)).backendbound().isEmpty());
            assertNull(s.state().authInputPackets().lastResult());
            var alive=new SetHealthPacket();alive.setHealth(20);s.process(true,s.encode(alive));
            assertTrue(s.state().player().awaitingRespawnHealth());ack(s);
            assertFalse(s.state().player().awaitingRespawnHealth());
            p=input(1);p.setPosition(ready.getPosition());
            assertEquals(1,s.process(false,s.encode(p)).backendbound().size());
            assertEquals(64,s.state().player().position().y);
            assertNotNull(s.state().authInputPackets().lastResult());
        }
    }
    @Test void acknowledgingAnOlderReadyCannotReleaseANewerPendingSpawn()throws Exception{
        try(var s=session()){
            s.process(true,recording("ready"));
            long older=s.state().player().getLatencyUtil().sentQueue().peekLast().id();
            var ready=new RespawnPacket();ready.setState(RespawnPacket.State.SERVER_READY);
            ready.setPosition(Vector3f.from(8,65.62001F,8));s.process(true,s.encode(ready));
            var response=new NetworkStackLatencyPacket();response.setTimestamp(older*1_000_000L);
            s.process(false,s.encode(response));
            assertEquals(32769.62F,s.state().player().position().y);
            assertTrue(s.state().awaitingBackendSpawnPosition());
            ack(s);assertFalse(s.state().awaitingBackendSpawnPosition());
            assertEquals(64,s.state().player().position().y);
        }
    }
    @Test void spawnAckPreservesAuthoritativeTeleportsSentAfterTheReadyPacket()throws Exception{
        try(var s=session()){
            var older=new MovePlayerPacket();older.setRuntimeEntityId(s.state().player().runtimeId());
            older.setMode(MovePlayerPacket.Mode.TELEPORT);older.setPosition(Vector3f.from(8,81.62001F,8));
            older.setRotation(Vector3f.ZERO);older.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
            s.process(true,s.encode(older));
            s.process(true,recording("ready"));
            long spawnAck=s.state().player().getLatencyUtil().sentQueue().peekLast().id();
            var newer=older.clone();newer.setPosition(Vector3f.from(8,65.62001F,8));s.process(true,s.encode(newer));
            var response=new NetworkStackLatencyPacket();response.setTimestamp(spawnAck*1_000_000L);
            s.process(false,s.encode(response));
            assertEquals(-60,s.state().player().position().y);
            assertEquals(1,s.state().teleports().getQueuedTeleports().size());
            assertEquals(65.62001F,s.state().teleports().getQueuedTeleports().peek().getPosition().y);
            assertFalse(s.state().teleports().getQueuedTeleports().peek().isAccepted());
            ack(s);assertTrue(s.state().teleports().getQueuedTeleports().peek().isAccepted());
            s.process(false,s.encode(new SetLocalPlayerAsInitializedPacket()));finishLoading(s);
            var confirmed=input(0);confirmed.setPosition(newer.getPosition());
            confirmed.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
            assertEquals(1,s.process(false,s.encode(confirmed)).backendbound().size());
            assertFalse(s.state().teleports().isTeleporting());assertEquals(64,s.state().player().position().y);
        }
    }
    @Test void spawnAckKeepsLaterMotionForTheActualPrediction()throws Exception{
        try(var s=session()){
            var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(s.state().player().runtimeId());
            motion.setMotion(Vector3f.from(.7F,.3F,0));s.process(true,s.encode(motion));
            s.process(true,recording("ready"));
            motion.setMotion(Vector3f.from(.4F,.3F,0));s.process(true,s.encode(motion));
            ack(s);
            s.process(false,s.encode(new SetLocalPlayerAsInitializedPacket()));finishLoading(s);
            s.process(false,s.encode(input(0)));



            s.process(false,s.encode(input(1)));
            var prediction=s.state().authInputPackets().lastResult();
            assertNotNull(prediction);assertTrue(prediction.predictedPosition().x>.8F,prediction.toString());
            assertTrue(prediction.predictedPosition().y>-59.8F,prediction.toString());
            assertTrue(prediction.rawOffset()>.2F,prediction.toString());
            assertEquals(ac.navro.proxy.prediction.engine.data.VectorType.VELOCITY,
                    s.state().authInputPackets().context().bestPossibility.getType());
        }
    }
}
