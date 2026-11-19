package ac.navro.proxy.data;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ItemUseTrackerLifecycleTest {
    static JsonObject header()throws Exception{try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(ItemUseTrackerLifecycleTest.class.getResourceAsStream("/navro-item-use-tracker.jsonl.gz")),StandardCharsets.UTF_8))){return JsonParser.parseString(in.readLine()).getAsJsonObject();}}
    static ItemUseTestModel model()throws Exception{var h=header();return new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air"));}
    @Test void predictionFactorStaysFrozenAcrossReleaseAndLiveAcksSurviveHistoryRestore()throws Exception {
        var m=model();var t=new ItemUseTracker<>(m);m.tick=5;t.use(m.item(0),261,true);t.applyPredictedUseStart();t.snapshotPredictionUseState();var old=t.snapshot();assertEquals(Float.floatToRawIntBits(0.122499995F),Float.floatToRawIntBits(t.getPredictionItemSlowdownFactor()));
        t.onMovementMetadataSent(false);t.release();assertTrue(t.hasPredictionItemSlowdown());assertFalse(m.hasFlag(-1));t.onMovementMetadataAcknowledged(false);t.use(m.item(0),261,false);assertTrue(t.isAwaitingMetadataStartConfirmation());t.applyPredictedUseStart();assertFalse(m.hasFlag(-1));t.snapshotPredictionUseState();assertEquals(1F,t.getPredictionItemSlowdownFactor());
        t.onMovementMetadataAcknowledged(true);t.applyPredictedUseStart();assertTrue(m.hasFlag(-1));assertEquals(1F,t.getPredictionItemSlowdownFactor());t.restore(old);assertEquals(Boolean.TRUE,t.getLastAcknowledgedMetadataUsing());assertEquals(5,t.getLastMetadataFalseAckPlayerTick());assertEquals(0,t.getPendingMetadataFalsePackets());assertTrue(t.hasPredictionItemSlowdown());assertNotSame(old.usedItem(),t.getUsedItem());
    }
    @Test void releaseAndNextAuthInputDistinguishSlotTransitionFromButtonUp()throws Exception {
        var m=model();var t=new ItemUseTracker<>(m);t.use(m.item(0),261,true);t.applyPredictedUseStart();t.release();assertTrue(t.isButtonReleasePending());assertTrue(t.isUseButtonLatched());t.finishAuthInput(true);assertFalse(t.isButtonReleasePending());assertTrue(t.isUseButtonLatched());assertTrue(t.resumeLatchedUse(m.item(1)));assertSame(m.item(1),t.getUsedItem());assertEquals(ItemUseTracker.DirtyUsing.NONE,t.getDirtyUsing());assertTrue(m.hasFlag(-1));
        t.release();t.finishAuthInput(false);assertFalse(t.isUseButtonLatched());assertFalse(t.resumeLatchedUse(m.item(0)));assertSame(m.air,t.getUsedItem());
    }
    @Test void snapshotsNormalizeEmptyItemsToSentinelWithoutChangingLiveIdentityOrAliasingCopies()throws Exception {
        var m=model();var t=new ItemUseTracker<>(m);m.item(0).count=0;t.use(m.item(0),261,true);assertSame(m.item(0),t.getUsedItem());var empty=t.snapshot();assertSame(m.air,empty.usedItem());assertNotSame(m.air,t.getUsedItem());m.item(0).count=7;assertSame(m.air,empty.usedItem());
        t.restore(empty);assertSame(m.air,t.getUsedItem());m.setFlag(-1,true);t.postTick();assertEquals(-1,t.getJavaItemId());assertFalse(m.hasFlag(-1));t.setUsedItem(null);assertNull(t.getUsedItem());assertSame(m.air,t.snapshot().usedItem());t.restore(null);assertNull(t.getUsedItem());
        t.use(m.item(0),261,true);var copy=t.snapshot();m.item(0).count=13;assertEquals(7,copy.usedItem().count);t.restore(copy);assertNotSame(copy.usedItem(),t.getUsedItem());assertEquals(7,t.getUsedItem().count);
    }
    @Test void preTickFoodLookupPrecedesNativeTridentCounterAndPreservesIntegerOverflow()throws Exception {
        var h=header();var m=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air")){int calls;@Override public Object foodByRelative(Item item){calls++;return super.foodByRelative(item);}};var t=new ItemUseTracker<>(m);t.use(m.item(1),455,true);m.since=Integer.MAX_VALUE;t.preTick();assertEquals(0,m.calls);assertEquals(Integer.MAX_VALUE,m.since);t.applyPredictedUseStart();t.preTick();assertEquals(1,m.calls);assertEquals(Integer.MIN_VALUE,m.since);t.setJavaItemId(261);t.preTick();assertEquals(2,m.calls);assertEquals(Integer.MIN_VALUE,m.since);
    }
    @Test void componentEligibilityShortCircuitsNativeGettersAndSkipStillEvaluatesPredicate()throws Exception {
        var h=header();var m=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air")){@Override public boolean canRelease(Item item){if(item!=null&&item.profile.get("component").getAsBoolean())throw new AssertionError("component already accepted");return super.canRelease(item);}};var t=new ItemUseTracker<>(m);var custom=m.inputs.stream().filter(i->i.profile.get("component").getAsBoolean()).findFirst().orElseThrow();t.use(custom,1,false);assertSame(custom,t.getUsedItem());assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,t.getDirtyUsing());assertFalse(m.hasFlag(-1));
        t.release();t.use(m.item(7),1,false);assertSame(m.air,t.getUsedItem());t.use(m.item(7),1,true);assertSame(m.item(7),t.getUsedItem());assertTrue(t.isUseButtonLatched());assertFalse(m.hasFlag(-1));assertThrows(NullPointerException.class,()->t.use(null,1,true));assertSame(m.item(7),t.getUsedItem());
    }
    @Test void spearIdentifierUsesRootLocaleAndOnlyNewSnapshotChangesSlowdownOrHistoricalAction()throws Exception {
        var m=model();var t=new ItemUseTracker<>(m);var spear=m.inputs.stream().filter(i->"MINECRAFT:WOODEN_SPEAR".equals(m.identifier(i))).findFirst().orElseThrow();t.use(spear,m.nativeItemId(spear),true);t.applyPredictedUseStart();m.tick=19;t.snapshotPredictionUseState();assertTrue(t.isUsingSpear());assertEquals(1F,t.getPredictionItemSlowdownFactor());t.setUsedItem(m.item(0));assertEquals(1F,t.getPredictionItemSlowdownFactor());m.setFlag(4,false);assertTrue(t.snapshotPredictionFlags(19).contains(4));assertFalse(t.snapshotPredictionFlags(20).contains(4));assertFalse(m.hasFlag(4));t.snapshotPredictionUseState();assertTrue(t.hasPredictionItemSlowdown());
    }
    @Test void diagnosticNamesArgumentsAndStateClearingOrderRemainOriginal()throws Exception {
        var m=model();var t=new ItemUseTracker<>(m);t.use(m.item(0),261,true);assertEquals(List.of("USE_REQUEST[itemId, 261, validatedStartEdge, true]","USE_REQUEST_APPLIED[itemId, 261, validatedStartEdge, true]"),m.debug);m.debug.clear();t.release();assertEquals(List.of("USE_RELEASE_BEGIN[]","USE_CLEAR_STATE_BEGIN[clearButtonLatch, false]","USE_CLEAR_STATE_END[clearButtonLatch, false]","USE_RELEASE_END[]"),m.debug);assertSame(m.air,t.getUsedItem());assertEquals(ItemUseTracker.DirtyUsing.NONE,t.getDirtyUsing());assertEquals(0,m.since);
    }
}
