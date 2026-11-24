package ac.navro.proxy.nethernet;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.util.EntityMetadataUtil;
import com.google.gson.*;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SessionState movement boundary. Timing validators and transport are
 * outside this boundary; real input actions, physics, checks and ACK tasks run.
 * Recorded raw client position/delta, never old predictions, supply the oracle.
 */
class NativeSprintMovementTest {
    static JsonObject data()throws Exception{
        try(var in=new InputStreamReader(NativeSprintMovementTest.class.getResourceAsStream(
                "/nethernet-sprint-input-20261115.json"))){return JsonParser.parseReader(in).getAsJsonObject();}
    }
    static Vector3f vec(JsonArray a){return Vector3f.from(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    static SessionState session(){
        var s=new SessionState();s.setProtocolVersion(2225);s.player().setProtocolVersion(2225);s.setDetectionEnabled(true);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);
        start.setBlockNetworkIdsHashed(true);start.setPlayerPosition(Vector3f.from(.5,-60,.5));s.observeBackend(start);
        var w=s.world();int grass=NetworkBlockStateRegistry.forProtocol(2225,true).states().values().stream()
                .filter(b->b.is("minecraft:grass_block")).findFirst().orElseThrow().networkId();
        for(int cx=0;cx<=2;cx++)for(int cz=0;cz<=1;cz++){
            w.put(cx,cz,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)w.updateBlock(cx*16+x,-61,cz*16+z,0,grass);
        }
        s.observeClient(new SetLocalPlayerAsInitializedPacket());s.player().inLoadingScreen=false;s.player().sinceLoadingScreen=30;
        return s;
    }
    static PlayerAuthInputPacket input(JsonObject r){
        var p=new PlayerAuthInputPacket();p.setTick(r.get("tick").getAsLong());p.setPosition(vec(r.getAsJsonArray("position")));
        p.setDelta(vec(r.getAsJsonArray("delta")));p.setRotation(vec(r.getAsJsonArray("rotation")));
        var m=r.getAsJsonArray("motion");p.setMotion(Vector2f.from(m.get(0).getAsFloat(),m.get(1).getAsFloat()));
        m=r.getAsJsonArray("rawMoveVector");p.setRawMoveVector(Vector2f.from(m.get(0).getAsFloat(),m.get(1).getAsFloat()));
        p.setInputMode(InputMode.MOUSE);
        for(var action:r.getAsJsonArray("actions"))p.getInputData().add(PlayerAuthInputData.valueOf(action.getAsString()));
        return p;
    }
    static void attributes(SessionState s,long sourceTick,float value){
        var p=new UpdateAttributesPacket();p.setRuntimeEntityId(7);p.setTick(sourceTick);
        p.getAttributes().add(new AttributeData("minecraft:movement",0,Float.MAX_VALUE,value,.1F));
        s.player().getLatencyUtil().queue(sourceTick+10000,true);s.observeBackend(p);
        assertTrue(s.player().getLatencyUtil().onResponse(sourceTick+10000));
    }
    static void edge(SessionState s,long tick,boolean sprint){
        var p=AuthInputPacketsTest.input(tick,.5F,-60,.5F,Vec3.ZERO);
        p.setMotion(Vector2f.from(0,1));p.setRawMoveVector(Vector2f.from(0,1));
        p.getInputData().add(sprint?PlayerAuthInputData.START_SPRINTING:PlayerAuthInputData.STOP_SPRINTING);
        // Initial accepted action history is external to this recorded segment.
        s.player().apply(p,null,null);
    }
    @Test void recordedSprintStopAfterAttributeAckKeepsActualMovementAndChecksLive()throws Exception{
        var segment=data().getAsJsonArray("segments").get(0).getAsJsonObject();var s=session();
        // Explicit native adapter policy; legacy Navro references test the default.
        s.player().setNativeSprintSynchronization(true);
        edge(s,144,true);attributes(s,144,.13F);
        s.player().acceptTeleport(new Vec3(vec(segment.getAsJsonArray("initialFeet"))).down(1.62001F),true);
        s.player().setVelocity(new Vec3(vec(segment.getAsJsonArray("initialDelta"))));
        var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        for(var row:segment.getAsJsonArray("events")){
            var r=row.getAsJsonObject();s.observeClient(input(r));
            var result=s.authInputPackets().lastResult();assertNotNull(result);
            assertTrue(result.rawOffset()<.0001F,"tick="+r.get("tick")+" "+result);
        }
        assertTrue(alerts.isEmpty(),alerts.toString());assertFalse(s.player().predictionSprinting());
        assertEquals(.1F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        // Adversarial follow-up must still execute the actual solid-block check.
        var invalid=AuthInputPacketsTest.input(200,s.player().position().x,-60.5F,s.player().position().z,Vec3.ZERO);
        s.observeClient(invalid);assertTrue(alerts.stream().anyMatch(x->x.contains("Phase")),alerts.toString());
    }
    @Test void lateSourceTickCannotUndoNewerSprintStopOrStartAndCustomSpeedStillApplies(){
        var s=session();s.player().setNativeSprintSynchronization(true);
        edge(s,144,true);edge(s,199,false);attributes(s,144,.13F);
        var old=new SetEntityDataPacket();old.setRuntimeEntityId(7);old.setTick(144);
        EntityMetadataUtil.writeFlags(old.getMetadata(),Set.of(3,19));
        s.player().getLatencyUtil().queue(1,true);s.observeBackend(old);s.player().getLatencyUtil().onResponse(1);
        var p=AuthInputPacketsTest.input(200,.5F,-60,.5F,Vec3.ZERO);s.player().apply(p,null,null);
        assertFalse(s.player().predictionSprinting());assertEquals(.1F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        edge(s,210,true);attributes(s,199,.1F);
        old.setTick(199);EntityMetadataUtil.writeFlags(old.getMetadata(),Set.of(19));
        s.player().getLatencyUtil().queue(2,true);s.observeBackend(old);s.player().getLatencyUtil().onResponse(2);
        p.setTick(211);s.player().apply(p,null,null);
        assertTrue(s.player().predictionSprinting());assertEquals(.13F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        // A server-selected speed remains authoritative; no fixed default reset.
        attributes(s,211,.26F);edge(s,212,false);
        assertEquals(.2F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        // Current server metadata must still take effect; only older input
        // echoes are superseded. An unconditional metadata bypass fails here.
        old.setTick(230);EntityMetadataUtil.writeFlags(old.getMetadata(),Set.of(3,19));
        s.player().getLatencyUtil().queue(3,true);s.observeBackend(old);s.player().getLatencyUtil().onResponse(3);
        attributes(s,230,.39F);p.setTick(231);s.player().apply(p,null,null);
        assertTrue(s.player().predictionSprinting());
        assertEquals(.39F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        edge(s,232,false);assertEquals(.3F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
    }
    @Test void snapshotRestoresActionHistoryAndPendingMetadataSourceTick(){
        var s=session();s.player().setNativeSprintSynchronization(true);
        edge(s,144,true);attributes(s,144,.13F);
        var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(7);metadata.setTick(144);
        EntityMetadataUtil.writeFlags(metadata.getMetadata(),Set.of(3,19));
        s.player().getLatencyUtil().queue(1,true);s.observeBackend(metadata);
        var saved=s.player().snapshot(s.fluidState());
        edge(s,199,false);s.player().restore(saved,s.fluidState());
        edge(s,199,false);assertTrue(s.player().getLatencyUtil().onResponse(1));
        var p=AuthInputPacketsTest.input(200,.5F,-60,.5F,Vec3.ZERO);s.player().apply(p,null,null);
        assertFalse(s.player().predictionSprinting());attributes(s,144,.13F);
        assertEquals(.1F,s.player().attributes().get("minecraft:movement").getValue(),.000001F);
        assertEquals(144,saved.pendingPoseMetadata().get(0).sourceTick());
    }
}
