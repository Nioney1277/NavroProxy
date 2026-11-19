package ac.navro.proxy.data;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ItemUseTrackerReferenceTest {
    static int i(JsonObject o,String key){return o.get(key).getAsInt();}static boolean b(JsonObject o,String key){return o.get(key).getAsBoolean();}
    @Test void fullOriginalContinuousStateMatchesNativeSnapshotsAcksIdentityFlagsAndNumericEdges()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int errors=0,rows=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-item-use-tracker.jsonl.gz")),StandardCharsets.UTF_8))){var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NIU1",header.get("magic").getAsString());assertEquals(64,i(header,"sessions"));assertEquals(256,i(header,"steps"));
            for(int session=0;session<64;session++){
                var model=new ItemUseTestModel(header.getAsJsonArray("profiles"),header.getAsJsonObject("air"));model.tick=session*100L;var tracker=new ItemUseTracker<>(model);List<ItemUseTracker.Snapshot<ItemUseTestModel.Item>> saved=new ArrayList<>(Collections.nCopies(4,null));
                for(int step=0;step<256;step++){
                    var row=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals(session,i(row,"session"));assertEquals(step,i(row,"step"));int op=i(row,"op"),item=i(row,"item"),value=i(row,"value"),slot=i(row,"slot");boolean b=b(row,"b"),c=b(row,"c");long tick=row.get("tick").getAsLong();int factor=i(row,"factor");Object result=null;String error="";
                    try{switch(op){case 0->model.tick=tick;case 1->model.setFlag(new int[]{-1,4,32,97,100}[Math.floorMod(value,5)],b);case 2->model.serverUsing=b;case 3->tracker.use(model.item(item),value,b);case 4->result=tracker.resumeLatchedUse(model.item(item));case 5->tracker.release();case 6->tracker.finishAuthInput(b);case 7->tracker.onMetadataUseStateChanged(b,c);case 8->tracker.onMovementMetadataSent(b);case 9->tracker.onMovementMetadataAcknowledged(b);case 10->tracker.snapshotPredictionUseState();case 11->tracker.applyPredictedUseStart();case 12->tracker.preTick();case 13->tracker.postTick();case 14->tracker.onHeldItemChanged(model.item(item));case 15->saved.set(slot,tracker.snapshot());case 16->tracker.restore(b?saved.get(slot):null);case 17->tracker.setUsedItem(model.item(item));case 18->tracker.setJavaItemId(value);case 19->tracker.setDirtyUsing(value<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(value,3)]);case 20->tracker.setUseDuration(value);case 21->tracker.setUseButtonLatched(b);case 22->tracker.setButtonReleasePending(b);case 23->model.since=value;case 24->result=new TreeSet<>(tracker.snapshotPredictionFlags(tick));case 25->{var f=ItemUseTracker.class.getDeclaredField("pendingMetadataFalsePackets");f.setAccessible(true);f.setInt(tracker,value);}case 26->tracker.restore(new ItemUseTracker.Snapshot<>(model.item(item),value,value<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(value,3)],value,b,c,c,b,c,tick,Float.intBitsToFloat(factor),value));case 27->model.inputs.get(Math.floorMod(value,model.inputs.size())).count=value;default->throw new AssertionError();}}catch(RuntimeException e){error=e.getClass().getSimpleName();}
                    String label="original tracker session="+session+" step="+step+" op="+op;assertEquals(row.get("error").getAsString(),error,label);assertEquals(row.get("result"),gson.toJsonTree(result),label+" result");assertEquals(row.get("state"),gson.toJsonTree(model.state(tracker,saved)),label+" state");if(!error.isEmpty())errors++;rows++;model.debug.clear();
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(16384,rows);assertTrue(errors>25);
    }
}
