package ac.navro.proxy.state.tracker;

import ac.navro.proxy.player.data.tracker.FlagTracker;


import ac.navro.proxy.data.*;
import ac.navro.proxy.util.EntityMetadataUtil;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class FlagTrackerReferenceTest {
    static int i(JsonObject o,String key){return o.get(key).getAsInt();}static boolean b(JsonObject o,String key){return o.get(key).getAsBoolean();}
    static Set<Integer> set(JsonElement e){if(e.isJsonNull())return null;Set<Integer> values=new HashSet<>();for(var v:e.getAsJsonArray())values.add(v.getAsInt());return values;}
    @Test void originalFlagUseHandshakeAndReplayStateMatchAcrossContinuousNativeSessions()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int rows=0,errors=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-flag-tracker.jsonl.gz")),StandardCharsets.UTF_8))){var h=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NFT1",h.get("magic").getAsString());assertEquals(48,i(h,"sessions"));assertEquals(192,i(h,"steps"));
            for(int session=0;session<48;session++){
                var nativeFlags=new FlagTracker<ItemUseTestModel.Item>();var model=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air")){@Override public boolean hasFlag(int f){return nativeFlags.has(f);}@Override public void setFlag(int f,boolean value){nativeFlags.set(f,value);}@Override public Set<Integer> cloneFlags(){return nativeFlags.cloneFlags();}};model.tick=session;var tracker=new ItemUseTracker<>(model);var context=new FlagTracker.Context<ItemUseTestModel.Item>(){public ItemUseTracker<ItemUseTestModel.Item> getItemUseTracker(){return tracker;}public ItemUseTestModel.Item airItem(){return model.air;}};List<ItemUseTracker.Snapshot<ItemUseTestModel.Item>> saved=new ArrayList<>(Collections.nCopies(4,null));
                for(int step=0;step<192;step++){
                    var r=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals(session,i(r,"session"));assertEquals(step,i(r,"step"));int op=i(r,"op"),index=i(r,"item"),v=i(r,"value"),slot=i(r,"slot");boolean b=b(r,"b"),c=b(r,"c");long tick=r.get("tick").getAsLong();Set<Integer> update=set(r.get("update"));Object result=null;String error="";
                    try{switch(op){case 0->nativeFlags.set(v,b);case 1->{if(c)nativeFlags.set(b(r,"nilPlayer")?null:context,update);else nativeFlags.set(b(r,"nilPlayer")?null:context,update,b);}case 2->nativeFlags.restoreFlags(update);case 3->nativeFlags.clear();case 4->nativeFlags.setFlying(b);case 5->nativeFlags.flying(b);case 6->tracker.use(model.item(index),v,b);case 7->tracker.release();case 8->tracker.setUsedItem(model.item(index));case 9->tracker.setDirtyUsing(v<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(v,3)]);case 10->model.serverUsing=b;case 11->tracker.applyPredictedUseStart();case 12->tracker.snapshotPredictionUseState();case 13->tracker.onMovementMetadataAcknowledged(b);case 14->tracker.onMovementMetadataSent(b);case 15->saved.set(slot,tracker.snapshot());case 16->tracker.restore(b?saved.get(slot):null);case 17->model.tick=tick;case 18->tracker.postTick();case 19->tracker.onHeldItemChanged(model.item(index));case 20->{var copy=nativeFlags.cloneFlags();copy.add(v);result=new TreeSet<>(copy);}case 21->{var next=EntityMetadataUtil.mergeFlags(nativeFlags.cloneFlags(),update,b,c);if(b){next.remove(-1);if(next.contains(4))next.add(-1);}nativeFlags.set(b(r,"nilPlayer")?null:context,next);}default->throw new AssertionError();}}catch(RuntimeException e){error=e.getClass().getSimpleName();}
                    String label="native flags session="+session+" step="+step+" op="+op;assertEquals(r.get("error").getAsString(),error,label);assertEquals(r.get("result"),gson.toJsonTree(result),label);assertEquals(b(r,"flying"),nativeFlags.isFlying(),label);assertEquals(b(r,"wasFlying"),nativeFlags.isWasFlying(),label);assertEquals(r.get("state"),gson.toJsonTree(model.state(tracker,saved)),label);model.debug.clear();rows++;if(!error.isEmpty())errors++;
                }
            }
            assertNull(in.readLine());
        }
        assertEquals(9216,rows);assertTrue(errors>50);
    }
}
