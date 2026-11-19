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

class FlagTrackerLifecycleTest {
    static final class Fixture {
        final FlagTracker<ItemUseTestModel.Item> flags=new FlagTracker<>();final ItemUseTestModel model;final ItemUseTracker<ItemUseTestModel.Item> use;final FlagTracker.Context<ItemUseTestModel.Item> context;
        Fixture()throws Exception{JsonObject h;try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-flag-tracker.jsonl.gz")),StandardCharsets.UTF_8))){h=JsonParser.parseString(in.readLine()).getAsJsonObject();}model=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air")){@Override public boolean hasFlag(int f){return Fixture.this.flags.has(f);}@Override public void setFlag(int f,boolean value){Fixture.this.flags.set(f,value);}@Override public Set<Integer> cloneFlags(){return Fixture.this.flags.cloneFlags();}};use=new ItemUseTracker<>(model);context=new FlagTracker.Context<>(){public ItemUseTracker<ItemUseTestModel.Item> getItemUseTracker(){return use;}public ItemUseTestModel.Item airItem(){return model.air;}};}
    }
    @Test void serverFlagsPreservePhysicalSneakSwimAndUseIdentityRatherThanEmptyPredicate()throws Exception {
        var f=new Fixture();f.flags.set(1,true);f.flags.set(57,true);var incoming=new HashSet<>(Set.of(-1,4,64));f.flags.set(f.context,incoming);assertTrue(f.flags.has(1));assertTrue(f.flags.has(57));assertFalse(f.flags.has(-1));assertTrue(f.flags.has(4));assertEquals(ItemUseTracker.DirtyUsing.METADATA,f.use.getDirtyUsing());assertNull(f.use.getLastAcknowledgedMetadataUsing());assertEquals(Set.of(-1,4,64),incoming);
        f.use.setUsedItem(f.model.item(3));assertTrue(f.model.isNull(f.model.item(3)));f.flags.set(f.context,incoming);assertTrue(f.flags.has(-1));assertSame(f.model.item(3),f.use.getUsedItem());assertEquals(ItemUseTracker.DirtyUsing.NONE,f.use.getDirtyUsing());
        f.use.setUsedItem(null);f.flags.set(f.context,incoming);assertTrue(f.flags.has(-1));assertNull(f.use.getUsedItem());
    }
    @Test void clientFlagsReplacePhysicalFlagsAndNativeCallbackCanReinstatePendingDirtyState()throws Exception {
        var f=new Fixture();f.use.use(f.model.item(0),261,true);f.use.applyPredictedUseStart();f.flags.set(1,true);f.flags.set(57,true);f.model.serverUsing=true;f.flags.set(f.context,Set.of(),false);assertFalse(f.flags.has(1));assertFalse(f.flags.has(57));assertFalse(f.flags.has(-1));assertTrue(f.use.isAwaitingMetadataStartConfirmation());assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,f.use.getDirtyUsing());assertNull(f.use.getLastAcknowledgedMetadataUsing());
        f.flags.flying(true);assertTrue(f.flags.isFlying());assertTrue(f.flags.isWasFlying());f.flags.setFlying(false);assertFalse(f.flags.isFlying());assertTrue(f.flags.isWasFlying());f.flags.clear();assertFalse(f.flags.isFlying());assertTrue(f.flags.isWasFlying());
    }
    @Test void replayRestoreAndCloneNeverStartMetadataHandshakeAndNullInputsKeepNativePartialOrder()throws Exception {
        var f=new Fixture();f.use.use(f.model.item(0),261,true);f.model.debug.clear();f.flags.restoreFlags(Set.of(-1,4,57,128));assertTrue(f.model.debug.isEmpty());assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,f.use.getDirtyUsing());var copy=f.flags.cloneFlags();copy.clear();assertTrue(f.flags.has(-1));assertTrue(f.flags.has(128));
        assertThrows(NullPointerException.class,()->f.flags.restoreFlags(null));assertTrue(f.flags.cloneFlags().isEmpty());assertTrue(f.model.debug.isEmpty());f.flags.set(1,true);assertThrows(NullPointerException.class,()->f.flags.set(null,Set.of(4,64)));assertEquals(Set.of(1,4,64),f.flags.cloneFlags());assertThrows(NullPointerException.class,()->f.flags.set(f.context,null));assertTrue(f.flags.cloneFlags().isEmpty());assertTrue(f.model.debug.isEmpty());
    }
    @Test void partialNativeWordsPreserveInternalFlagsAndIgnoreUpdatesOutside128Bits() {
        Set<Integer> current=Set.of(-1,1,4,57,63,64,92,127,128,Integer.MAX_VALUE),update=Set.of(-1,0,64,127,128,Integer.MIN_VALUE);
        assertEquals(Set.of(-1,0,64,92,127,128,Integer.MAX_VALUE),EntityMetadataUtil.mergeFlags(current,update,true,false));assertEquals(Set.of(-1,1,4,57,63,64,127,128,Integer.MAX_VALUE),EntityMetadataUtil.mergeFlags(current,update,false,true));assertEquals(current,EntityMetadataUtil.mergeFlags(current,update,false,false));
        var metadata=new EntityMetadataUtil.Metadata(){public boolean exists(int key){return key==0||key==92;}public float getFloat(int key){throw new AssertionError();}public long getLong(int key){return key==0?Long.MIN_VALUE:-1L;}};var flags=EntityMetadataUtil.copyFlags(metadata);assertEquals(65,flags.size());assertTrue(flags.contains(63));assertTrue(flags.contains(64));assertTrue(flags.contains(127));assertFalse(EntityMetadataUtil.hasFlag(metadata,128));assertFalse(EntityMetadataUtil.hasFlag(metadata,-1));assertFalse(EntityMetadataUtil.hasFlag(null,4));
    }
    @Test void flagHandshakeDoesNotAcknowledgePacketsAndHistoryRestoreCannotClearAckWait()throws Exception {
        var f=new Fixture();f.model.tick=10;f.model.serverUsing=true;f.use.use(f.model.item(0),261,true);f.use.applyPredictedUseStart();f.use.onMovementMetadataAcknowledged(false);assertTrue(f.use.isAwaitingMetadataStartConfirmation());f.model.debug.clear();f.flags.restoreFlags(Set.of(-1,4));assertTrue(f.use.isAwaitingMetadataStartConfirmation());assertTrue(f.model.debug.isEmpty());
        f.flags.set(f.context,Set.of(-1,4));assertFalse(f.use.isAwaitingMetadataStartConfirmation());assertEquals(Boolean.FALSE,f.use.getLastAcknowledgedMetadataUsing());assertEquals(10,f.use.getLastMetadataFalseAckPlayerTick());assertEquals(ItemUseTracker.DirtyUsing.NONE,f.use.getDirtyUsing());assertEquals(3,f.model.debug.size());
        f.use.use(f.model.item(0),261,false);assertTrue(f.use.isAwaitingMetadataStartConfirmation());assertEquals(Boolean.FALSE,f.use.getLastAcknowledgedMetadataUsing());
    }
}
