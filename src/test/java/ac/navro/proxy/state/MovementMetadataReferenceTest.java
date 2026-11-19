package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.prediction.fluid.FluidMovement;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class MovementMetadataReferenceTest {
    static int i(JsonObject o,String key){return o.get(key).getAsInt();}static boolean b(JsonObject o,String key){return o.get(key).getAsBoolean();}
    static Float f(int bits){return bits==0x7fffffff?null:Float.intBitsToFloat(bits);}
    static Set<Integer> flags(JsonElement e){if(e.isJsonNull())return null;var result=new HashSet<Integer>();for(var v:e.getAsJsonArray())result.add(v.getAsInt());return result;}
    @Test void originalFullMovementMetadataAndPoseOwnershipMatchInContinuousNativeSessions()throws Exception{
        var gson=new GsonBuilder().serializeNulls().create();int rows=0,metadata=0,errors=0;Set<Integer> ops=new HashSet<>();
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-movement-metadata.jsonl.gz")),StandardCharsets.UTF_8))){
            var h=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NMM1",h.get("magic").getAsString());assertEquals(32,i(h,"sessions"));assertEquals(256,i(h,"steps"));
            for(int session=0;session<32;session++){
                var m=new MovementMetadataTestModel(h,session);var t=m.tracker;
                for(int step=0;step<256;step++){
                    var r=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals(session,i(r,"session"));assertEquals(step,i(r,"step"));int op=i(r,"op"),item=i(r,"item"),v=i(r,"value");boolean b=b(r,"b"),c=b(r,"c");Set<Integer> update=flags(r.get("flags"));Object result=null;String error="";
                    try{switch(op){case 0,1,2,3->MovementMetadata.applyClientVisibleMovementMetadata(m,f(i(r,"width")),f(i(r,"height")),f(i(r,"scale")),update,b,c);case 4->FluidMovement.beginInput(m);case 5->FluidMovement.finishInput(m);case 6->FluidMovement.updatePose(m);case 7->FluidMovement.applySwimmingAction(m,b);case 8->m.trackedFlags.set(v,b);case 9->{var chest=m.item(item);m.chest=chest==null?null:m.cloneItem(chest);if(m.chest!=null)m.chest.count=b?1:0;}case 10->t.use(m.item(item),v,b);case 11->t.setUsedItem(m.item(item));case 12->m.serverUsing=b;case 13->t.onMovementMetadataSent(b);case 14->t.onMovementMetadataAcknowledged(b);case 15->{m.tick=r.get("tick").getAsLong();t.snapshotPredictionUseState();}case 16->FluidMovement.queueMetadataPose(m,EntityDimensions.fixed(0.58F,b?0.625F:1.8F).withEyeHeight(b?0.42F:1.62F));case 17->FluidMovement.acknowledgeDimensions(m);case 18->FluidMovement.acknowledgeWidth(m);case 19->m.geometry.setResolvedPosition(m.geometry.position().add(0.1F,0.0625F,-0.1F),m.boundingBox().offset(new Vec3(0.1F,0.0625F,-0.1F)),false);case 20->m.geometry.setResolvedPosition(m.geometry.position(),Box.EMPTY,false);case 21->m.applyDimensions(FluidMovement.horizontalPose(m.pose,m.dimensions(),b,c));case 22->result=MovementMetadata.hasElytraEquipped(m);case 23->{m.pose.captureInputState(b,c);m.trackedFlags.restoreFlags(update);}case 24->m.applyDimensions(EntityDimensions.fixed(0.58F,b?1.49F:1.8F).withEyeHeight(b?1.26F:1.62F));default->throw new AssertionError();}}catch(RuntimeException e){error=e.getClass().getSimpleName();}
                    String label="original metadata session="+session+" step="+step+" op="+op;assertEquals(r.get("error").getAsString(),error,label);assertEquals(r.get("result"),gson.toJsonTree(result),label);assertEquals(r.get("pose"),gson.toJsonTree(m.poseState()),label+" physical/pose");assertEquals(r.get("state"),gson.toJsonTree(m.state(t,Collections.nCopies(4,null))),label+" tracker");rows++;ops.add(op);if(op<4)metadata++;if(!error.isEmpty())errors++;m.events.clear();m.debug.clear();
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(8192,rows);assertEquals(25,ops.size());assertTrue(metadata>1000);assertTrue(errors>0);
    }
}
