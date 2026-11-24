package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import com.google.gson.*;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GlideSequencePipelineTest {
    static Vector3f vec(JsonArray a){return Vector3f.from(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    @Test void originalContinuousAirborneTravelKeepsRotationAndBoostAcrossTeleportWaits() throws Exception {
        var s=AuthInputPacketsTest.session();s.world().clearChunks();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)s.world().put(x,z,CompensatedWorld.CachedChunk.network(-64,320,24,s.world().airNetworkId()));
        var elytra=ItemData.builder().count(1).definition(new ItemDefinition(){public String getIdentifier(){return "minecraft:elytra";}public int getRuntimeId(){return 444;}public boolean isComponentBased(){return false;}}).build();
        s.inventory().armorContainer.set(1,elytra);
        try(var in=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/navro-glide-sequence.jsonl"),StandardCharsets.UTF_8))){
            var header=JsonParser.parseString(in.readLine()).getAsJsonObject();
            var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setPlayerPosition(vec(header.getAsJsonArray("initialPosition")));
            var initial=header.getAsJsonArray("initialRotation");start.setRotation(org.cloudburstmc.math.vector.Vector2f.from(initial.get(0).getAsFloat(),initial.get(1).getAsFloat()));
            s.player().apply(start);
            s.player().rebasePosition(new Vec3(vec(header.getAsJsonArray("initialPosition"))),false);
            s.player().setVelocity(new Vec3(vec(header.getAsJsonArray("initialVelocity"))));s.player().setCollisionResult(false,false,false);
            var rotation=header.getAsJsonArray("initialRotation");
            MovePlayerPacket teleport=null;
            for(String line;(line=in.readLine())!=null;){
                var row=JsonParser.parseString(line).getAsJsonObject();long tick=row.get("tick").getAsLong();
                boolean held=row.get("held").getAsBoolean(),confirm=row.get("confirm").getAsBoolean();
                var position=vec(row.getAsJsonArray("position"));var velocity=vec(row.getAsJsonArray("velocity"));
                if(tick==24){
                    s.player().getLatencyUtil().queue(100,true);teleport=new MovePlayerPacket();teleport.setRuntimeEntityId(7);teleport.setPosition(position.add(0,1.62001F,0));teleport.setRotation(Vector3f.ZERO);teleport.setMode(MovePlayerPacket.Mode.TELEPORT);s.observeBackend(teleport);
                }
                if(confirm)assertTrue(s.player().getLatencyUtil().onResponse(100));
                if(row.get("boostEvent").getAsInt()>=0){
                    var effect=new MovementEffectPacket();effect.setEntityRuntimeId(7);effect.setEffectType(MovementEffectType.GLIDE_BOOST);effect.setDuration(row.get("boostEvent").getAsInt());
                    s.player().getLatencyUtil().queue(99,true);s.observeBackend(effect);assertTrue(s.player().getLatencyUtil().onResponse(99));
                }
                var p=AuthInputPacketsTest.input(tick,position.getX(),position.getY(),position.getZ(),new Vec3(velocity));
                rotation=row.getAsJsonArray("rotation");p.setRotation(Vector3f.from(rotation.get(0).getAsFloat(),rotation.get(1).getAsFloat(),rotation.get(1).getAsFloat()));
                if(tick==10)p.getInputData().add(PlayerAuthInputData.START_GLIDING);
                if(confirm){p.setPosition(teleport.getPosition());p.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);}
                s.observeClient(p);
                if(!held){
                    var expectedPrevious=row.getAsJsonArray("previousRotation");
                    assertEquals(expectedPrevious.get(0).getAsFloat(),s.authInputPackets().context().prevPitch,"previous input pitch tick="+tick);
                    assertEquals(expectedPrevious.get(1).getAsFloat(),s.authInputPackets().context().prevYaw,"previous input yaw tick="+tick);
                    var actual=s.authInputPackets().lastResult().predictedVelocity();
                    assertEquals(velocity.getX(),actual.x,1e-6F,"native velocity X tick="+tick);
                    assertEquals(velocity.getY(),actual.y,1e-6F,"native velocity Y tick="+tick);
                    assertEquals(velocity.getZ(),actual.z,1e-6F,"native velocity Z tick="+tick);
                    assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F,"native airborne displacement tick="+tick);
                }
                assertTrue(s.player().gliding());
                assertEquals(row.get("boostBeforePostTick").getAsInt()-1,s.player().glideBoostTicks(),"boost progression tick="+tick);
            }
        }
    }
}
