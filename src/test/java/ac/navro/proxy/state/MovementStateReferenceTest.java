package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import com.google.gson.*;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class MovementStateReferenceTest {
    static Map<String,Object> state(WirePlayerState w)throws Exception {
        var pose=w.fluidPrediction();var out=new LinkedHashMap<String,Object>();
        out.put("dimensions",MovementMetadataTestModel.dims(w.dimensions()));out.put("position",MovementMetadataTestModel.vec(w.position()));out.put("box",MovementMetadataTestModel.box(w.boundingBox()));out.put("revision",w.aabbResizeRevision());
        out.put("serverSprinting",w.serverSprinting());out.put("clientSprinting",w.clientSprinting());out.put("clientSwimming",w.clientSwimming());
        for(String name:new String[]{"wasSwimming","wasCrawling","horizontalPoseApplied","inputHorizontalPoseApplied","inputNonCrawlingHorizontal","inputActive","readyPoseBeforeMovement","inputReadyPoseBeforeMovement"})out.put(name,pose.getClass().getField(name).get(pose));
        for(String name:new String[]{"dimensionsBeforeHorizontalPose","inputDimensions","inputStandingDimensions","pendingPoseDimensions","readyPoseDimensions","inputReadyPoseDimensions"})out.put(name,MovementMetadataTestModel.dims((EntityDimensions)pose.getClass().getField(name).get(pose)));
        out.put("inputAabb",MovementMetadataTestModel.snapshot(pose.inputAabb));out.put("flags",new TreeSet<>(w.trackedFlags().cloneFlags()));
        out.put("predictionSprinting",w.predictionSprinting());out.put("predictionSwimming",w.predictionSwimming());out.put("flying",w.flying());out.put("wasFlying",w.wasFlying());out.put("speed",Float.floatToRawIntBits(w.attributes().get("minecraft:movement").getValue()));return out;
    }
    @Test void liveWireMetadataAndActionsMatchOriginalNavroAcrossContinuousTransitions()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int rows=0;var operations=new HashSet<Integer>();
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-movement-state.jsonl.gz")),StandardCharsets.UTF_8))) {
            var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NMS1",header.get("magic").getAsString());
            for(int sn=0;sn<header.get("sessions").getAsInt();sn++) {
                var s=MovementStateSyncTest.session();var w=s.player();w.setResolvedPosition(new Vec3(sn*123.456F,sn-16.25F,-sn*123.456F),Box.EMPTY,false);
                for(int step=0;step<header.get("steps").getAsInt();step++) {
                    var r=JsonParser.parseString(in.readLine()).getAsJsonObject();int op=r.get("op").getAsInt();String label="native movement state session="+sn+" step="+step+" op="+op;
                    switch(op) {
                        case 0 -> {
                            var p=MovementStateSyncTest.input(step,r.get("forward").getAsFloat());for(var a:r.getAsJsonArray("actions"))p.getInputData().add(PlayerAuthInputData.valueOf(a.getAsString()));
                            MovementStateSyncTest.apply(s,p);assertEquals(r.get("accepted"),gson.toJsonTree(p.getInputData().stream().map(Enum::name).sorted().toList()),label+" accepted actions");
                        }
                        case 1 -> {
                            var flags=new HashSet<Integer>();for(var bit:r.getAsJsonArray("flags"))flags.add(bit.getAsInt());
                            var p=MovementStateSyncTest.metadata(s,flags,r.get("base").getAsBoolean(),r.get("extended").getAsBoolean());
                            if(!r.get("width").isJsonNull())p.getMetadata().put(EntityDataTypes.WIDTH,r.get("width").getAsFloat());
                            if(!r.get("height").isJsonNull())p.getMetadata().put(EntityDataTypes.HEIGHT,r.get("height").getAsFloat());
                            if(!r.get("scale").isJsonNull())p.getMetadata().put(EntityDataTypes.SCALE,r.get("scale").getAsFloat());
                            var before=gson.toJsonTree(state(w));w.getLatencyUtil().queue(1000L+step,true);s.observeBackend(p);assertEquals(before,gson.toJsonTree(state(w)),label+" before ACK");
                            assertTrue(w.getLatencyUtil().onOrderedResponse());
                        }
                        case 2 -> s.inventory().armorContainer.set(1,MovementStateSyncTest.elytra(r.get("count").getAsInt()));
                        case 3 -> w.finishInput();
                        case 4 -> w.acknowledgeAbilities(r.get("mayFly").getAsBoolean()?Set.of(Ability.MAY_FLY):Set.of());
                        default -> throw new AssertionError(op);
                    }
                    assertEquals(r.get("state"),gson.toJsonTree(state(w)),label);rows++;operations.add(op);
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(4096,rows);assertEquals(5,operations.size());
    }
}
