package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import com.google.gson.*;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class GroundEdgeLiveRegressionTest {
    private static Vec3 vec(JsonArray array) {
        return new Vec3(array.get(0).getAsFloat(),array.get(1).getAsFloat(),array.get(2).getAsFloat());
    }
    private static float[] floats(String text) {
        String[] parts=text.substring(1,text.length()-1).split(", ");
        float[] values=new float[parts.length];
        for(int i=0;i<parts.length;i++)values[i]=Float.parseFloat(parts[i]);
        return values;
    }
    @Test void recordedWalkingSprintingAndCoastingAtStoneEdgesMatchClientWithoutCorrection() throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/ground-edge-2225-live.json"),StandardCharsets.UTF_8)) {
            for(var element:JsonParser.parseReader(reader).getAsJsonArray()) {
                var sample=element.getAsJsonObject();String name=sample.get("name").getAsString();
                var s=new SessionState();s.setProtocolVersion(2225);s.player().setProtocolVersion(2225);
                var origin=vec(sample.getAsJsonArray("origin"));
                var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);
                start.setPlayerPosition(origin.toVector3f());s.observeBackend(start);
                int cx=(int)Math.floor(origin.x)>>4,cz=(int)Math.floor(origin.z)>>4;
                for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)
                    s.world().put(x,z,CompensatedWorld.CachedChunk.network(-64,320,24,s.world().airNetworkId()));
                var registry=NetworkBlockStateRegistry.forProtocol(2225,false);
                for(var blockElement:sample.getAsJsonArray("blocks")) {
                    var block=blockElement.getAsJsonObject();var pos=block.getAsJsonArray("position");
                    int id=registry.states().values().stream().filter(b->b.is(block.get("name").getAsString())).findFirst().orElseThrow().networkId();
                    s.world().updateBlock(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt(),0,id);
                }
                s.observeClient(new SetLocalPlayerAsInitializedPacket());
                s.player().inLoadingScreen=false;s.player().sinceLoadingScreen=20;
                s.player().commitInputTick(sample.get("tick").getAsLong());
                s.player().setCollisionResult(false,false,sample.get("onGround").getAsBoolean());
                s.player().setPredictionSprintingAction(sample.get("sprinting").getAsBoolean());
                s.player().setVelocity(vec(sample.getAsJsonArray("velocity")));
                s.setDetectionEnabled(true);s.authInputPackets().reset();
                var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
                var corrections=AuthInputPacketsTest.transport(s);
                for(var frameElement:sample.getAsJsonArray("frames")) {
                    var frame=frameElement.getAsJsonObject();var raw=frame.getAsJsonObject("raw");
                    var feet=vec(frame.getAsJsonArray("feet"));var delta=vec(frame.getAsJsonArray("delta"));
                    var input=AuthInputPacketsTest.input(raw.get("tick").getAsLong(),feet.x,feet.y,feet.z,delta);
                    float[] position=floats(raw.get("position").getAsString()),rotation=floats(raw.get("rotation").getAsString()),motion=floats(raw.get("motion").getAsString());
                    input.setPosition(Vector3f.from(position[0],position[1],position[2]));
                    input.setRotation(Vector3f.from(rotation[0],rotation[1],rotation[2]));
                    input.setMotion(Vector2f.from(motion[0],motion[1]));input.setRawMoveVector(input.getMotion());
                    String flags=raw.get("input").getAsString();
                    for(String flag:flags.substring(1,flags.length()-1).split(", "))if(!flag.isEmpty())input.getInputData().add(PlayerAuthInputData.valueOf(flag));
                    s.observeClient(input);
                    var result=s.authInputPackets().lastResult();assertNotNull(result,name);
                    assertTrue(result.rawOffset()<.0001F,name+": "+result);
                    assertEquals(delta.x,s.player().velocity().x,.000002F,name);
                    assertEquals(delta.z,s.player().velocity().z,.000002F,name);
                }
                assertTrue(alerts.isEmpty(),name+": "+alerts);
                assertTrue(corrections.isEmpty(),name+": "+corrections);
            }
        }
    }
}
