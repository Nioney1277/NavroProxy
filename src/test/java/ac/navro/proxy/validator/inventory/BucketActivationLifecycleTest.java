package ac.navro.proxy.validator.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BucketActivationLifecycleTest {
    static final Vector3i CLICK=Vector3i.from(8,64,8),TARGET=Vector3i.from(9,64,8);
    static CompensatedWorld world(boolean hash){var w=new CompensatedWorld();w.setProtocolVersion(2193);var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));return w;}
    static ItemData item(int wireId,int count,boolean tag){return ItemData.builder().definition(new SimpleItemDefinition("native:constructor_view",wireId,false)).count(count).damage(333).tag(tag?NbtMap.builder().putString("payload","preserved until replaced").build():null).build();}
    static NetworkBlockState state(CompensatedWorld w,Vector3i pos,int layer){return w.networkBlockState(w.getRawBlockAt(pos.getX(),pos.getY(),pos.getZ(),layer));}
    static final class Fixture {
        final WirePlayerState player=new WirePlayerState();final CompensatedInventory inventory=new CompensatedInventory(player);final ItemTransactionValidator validator=new ItemTransactionValidator(player,inventory);
        Fixture(){inventory.heldItemSlot=2;inventory.inventoryContainer.set(2,item(9991,7,true));inventory.inventoryContainer.set(4,item(7777,20,true));}
        ItemData held(){return inventory.inventoryContainer.getHeldItemData();}
        ItemTransactionValidator.BucketContext context(int nativeId,int nativeDamage,List<Integer> created){return new ItemTransactionValidator.BucketContext(){
            public int nativeItemId(ItemData held){return nativeId;}public int nativeItemDamage(ItemData held){return nativeDamage;}
            public ItemData createNativeBucket(int damage){created.add(damage);return ItemData.builder().definition(new SimpleItemDefinition("native:bucket_constructor_result",19000+damage,false)).damage(damage).count(1).build();}
        };}
    }
    @Test void waterInLayerOneWinsOverClickedLavaAndOnlyItsLayerIsPredictedAndAckProtected() {
        for(boolean hash:new boolean[]{false,true})for(GameType mode:GameType.values()) {
            var f=new Fixture();f.player.acknowledgeGameType(mode);var w=world(hash);w.updateLegacyBlock(CLICK,0,10<<13);w.updateLegacyBlock(CLICK,1,(9<<13)|7);w.updateLegacyBlock(TARGET,0,1<<13);
            var alias=f.inventory.inventoryContainer.getHeldItemCache();var original=alias.getData();var calls=new ArrayList<Integer>();long sent=w.bucketPredictionRevision();
            assertTrue(f.validator.handleBucketUse(w,original,CLICK,TARGET,state(w,CLICK,0),f.context(325,0,calls)));assertEquals(List.of(8),calls);assertEquals(sent+1,w.bucketPredictionRevision());
            assertEquals(w.airNetworkId(),w.getRawBlockAt(8,64,8,1));assertEquals(w.networkIdForLegacy(10<<13),w.getRawBlockAt(8,64,8,0));assertEquals(w.networkIdForLegacy(1<<13),w.getRawBlockAt(9,64,8,0));
            assertNotSame(alias,f.inventory.inventoryContainer.getHeldItemCache());assertEquals(7,alias.count());assertEquals(original.getTag(),alias.getData().getTag());assertEquals(1,f.held().getCount());assertNull(f.held().getTag());assertEquals(20,f.inventory.inventoryContainer.getItemFromSlot(4).count());
            assertFalse(w.applyAcknowledgedBlock(8,64,8,1,w.networkIdForLegacy(9<<13),sent));assertTrue(w.applyAcknowledgedBlock(8,64,8,1,w.networkIdForLegacy(9<<13),w.bucketPredictionRevision()));assertEquals(1,f.held().getCount());
        }
    }
    @Test void filledBucketsAlwaysUseAdjacentTargetAndReplaceHeldWithFreshCountOneEvenWhenPredictionCannotWrite() {
        int[] damages={8,10,11},blocks={8,10,561};
        for(boolean hash:new boolean[]{false,true})for(int i=0;i<damages.length;i++)for(boolean inBounds:new boolean[]{false,true}) {
            var f=new Fixture();var w=world(hash);w.updateLegacyBlock(CLICK,0,(78<<13)|7);var target=inBounds?TARGET:Vector3i.from(32,320,0);var calls=new ArrayList<Integer>();var old=f.held();long before=w.bucketPredictionRevision();
            assertTrue(f.validator.handleBucketUse(w,old,CLICK,target,state(w,CLICK,0),f.context(325,damages[i],calls)));assertEquals(List.of(0),calls);assertEquals(before+(inBounds?1:0),w.bucketPredictionRevision());assertEquals(w.networkIdForLegacy((78<<13)|7),w.getRawBlockAt(8,64,8,0));
            if(inBounds)assertEquals(w.networkIdForLegacy(blocks[i]<<13),w.getRawBlockAt(9,64,8,0));assertEquals(1,f.held().getCount());assertNull(f.held().getTag());assertEquals(7,old.getCount());
        }
    }
    @Test void unsuccessfulPickupAndUnsupportedBucketDamageKeepCachesWhileNonBucketsStopBeforeDamageOrWorldReads() {
        var f=new Fixture();var w=world(false);w.updateLegacyBlock(CLICK,0,1<<13);var cache=f.inventory.inventoryContainer.getHeldItemCache();var calls=new ArrayList<Integer>();
        for(int damage:new int[]{0,2,3,5,65535,-1,Integer.MIN_VALUE}) {
            assertEquals(damage==0,f.validator.handleBucketUse(w,f.held(),CLICK,TARGET,state(w,CLICK,0),f.context(325,damage,calls)));assertSame(cache,f.inventory.inventoryContainer.getHeldItemCache());assertEquals(0,w.bucketPredictionRevision());assertTrue(calls.isEmpty());
        }
        assertFalse(f.validator.handleBucketUse(null,f.held(),null,null,null,new ItemTransactionValidator.BucketContext(){public int nativeItemId(ItemData held){return 1;}public int nativeItemDamage(ItemData held){throw new AssertionError("non-bucket damage read");}public ItemData createNativeBucket(int damage){throw new AssertionError("non-bucket factory");}}));
    }
    @Test void doorActivationFromUpperHalfTogglesOnlyMatchingLowerDoorAndRetainsOriginalRevisionAndAckRules() {
        for(boolean hash:new boolean[]{false,true}) {
            var f=new Fixture();var w=world(hash);var top=Vector3i.from(8,65,8);w.updateLegacyBlock(CLICK,0,64<<13);w.updateLegacyBlock(top,0,(64<<13)|8);long before=w.bucketPredictionRevision();
            f.validator.predictActivatedBlock(w,top,state(w,top,0),null);assertEquals(before,w.bucketPredictionRevision());assertEquals(w.networkIdForLegacy((64<<13)|4),w.getRawBlockAt(8,64,8,0));assertEquals(w.networkIdForLegacy((64<<13)|8),w.getRawBlockAt(8,65,8,0));
            assertTrue(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(64<<13),before));
            w.updateLegacyBlock(CLICK,0,193<<13);f.validator.predictActivatedBlock(w,top,state(w,top,0),null);assertEquals(w.networkIdForLegacy(193<<13),w.getRawBlockAt(8,64,8,0));
            w.predictBucketBlock(CLICK,0,64<<13);long prediction=w.bucketPredictionRevision();f.validator.predictActivatedBlock(w,CLICK,state(w,CLICK,0),null);assertEquals(prediction,w.bucketPredictionRevision());assertFalse(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(64<<13),before));
        }
    }
    @Test void trapdoorSamplerReceivesOriginalPhasesAndTraceAroundDirectUpdateAndGateUsesItsOwnBit() {
        for(boolean hash:new boolean[]{false,true}) {
            var f=new Fixture();var w=world(hash);int before=(96<<13)|3,after=(96<<13)|11;w.updateLegacyBlock(CLICK,0,before);var phases=new ArrayList<String>();
            f.validator.predictActivatedBlock(w,CLICK,state(w,CLICK,0),(p,b,a,phase,trace)->{assertSame(CLICK,p);assertEquals(before,b);assertEquals(after,a);phases.add(phase);boolean first=phase.equals("beforePrediction");assertEquals(first?0:123,trace);assertEquals(w.networkIdForLegacy(first?before:after),w.getRawBlockAt(8,64,8,0));return 123;});
            assertEquals(List.of("beforePrediction","afterPrediction"),phases);assertEquals(0,w.bucketPredictionRevision());w.updateLegacyBlock(CLICK,0,107<<13);f.validator.predictActivatedBlock(w,CLICK,state(w,CLICK,0),(p,b,a,phase,trace)->{throw new AssertionError("gate must not sample trapdoors");});assertEquals(w.networkIdForLegacy((107<<13)|4),w.getRawBlockAt(8,64,8,0));
        }
    }
    @Test void samplerFailuresRetainNativePartialWriteOrderAndNullOrUnusableBlocksDoNothing() {
        var f=new Fixture();var w=world(false);w.updateLegacyBlock(CLICK,0,96<<13);var before=state(w,CLICK,0);
        assertThrows(IllegalStateException.class,()->f.validator.predictActivatedBlock(w,CLICK,before,(p,b,a,phase,t)->{throw new IllegalStateException("before");}));assertEquals(w.networkIdForLegacy(96<<13),w.getRawBlockAt(8,64,8,0));
        var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->f.validator.predictActivatedBlock(w,CLICK,before,(p,b,a,phase,t)->{if(calls.incrementAndGet()==2)throw new IllegalStateException("after");return 4;}));assertEquals(w.networkIdForLegacy((96<<13)|8),w.getRawBlockAt(8,64,8,0));assertEquals(0,w.bucketPredictionRevision());
        f.validator.predictActivatedBlock(null,null,before,null);f.validator.predictActivatedBlock(null,CLICK,null,null);f.validator.predictActivatedBlock(null,CLICK,NetworkBlockState.legacy(1<<13),null);assertEquals(2,calls.get());
    }
    @Test void bucketFactoryFailureLeavesWorldPredictionAndFactorySlotCaptureMatchesOriginalEvaluationOrder() {
        var f=new Fixture();var w=world(false);w.updateLegacyBlock(CLICK,0,1<<13);var original=f.held();var ctx=new ItemTransactionValidator.BucketContext(){public int nativeItemId(ItemData held){return 325;}public int nativeItemDamage(ItemData held){return 8;}public ItemData createNativeBucket(int damage){assertEquals(w.networkIdForLegacy(8<<13),w.getRawBlockAt(9,64,8,0));throw new IllegalStateException("native constructor failed");}};
        assertThrows(IllegalStateException.class,()->f.validator.handleBucketUse(w,original,CLICK,TARGET,state(w,CLICK,0),ctx));assertSame(original,f.held());assertEquals(1,w.bucketPredictionRevision());
        var oldOther=f.inventory.inventoryContainer.getItemFromSlot(4);var replacement=item(21234,1,false);
        var mutate=new ItemTransactionValidator.BucketContext(){public int nativeItemId(ItemData held){return 325;}public int nativeItemDamage(ItemData held){return 8;}public ItemData createNativeBucket(int damage){assertEquals(0,damage);f.inventory.heldItemSlot=4;return replacement;}};
        assertTrue(f.validator.handleBucketUse(w,original,CLICK,TARGET,state(w,CLICK,0),mutate));assertSame(oldOther,f.inventory.inventoryContainer.getItemFromSlot(4));assertSame(replacement.getDefinition(),f.inventory.inventoryContainer.getItemFromSlot(2).getData().getDefinition());assertEquals(1,f.inventory.inventoryContainer.getItemFromSlot(2).count());
    }
    @Test void scaffoldingEarlyReturnUsesNativeItemBlockMappingAndOnlyQueriesTargetWhenNeeded() {
        var f=new Fixture();assertFalse(f.validator.isScaffoldingPlacement(null,null,null,null));assertFalse(f.validator.isScaffoldingPlacement(null,1,null,TARGET));
        int mapped=ac.navro.proxy.data.inventory.NativeItemPlacementProperties.forNumeric(255-420,0).blockId();assertEquals(420,mapped);
        assertTrue(f.validator.isScaffoldingPlacement(null,mapped,NetworkBlockState.legacy(420<<13),null));assertFalse(f.validator.isScaffoldingPlacement(null,mapped,null,null));
        for(boolean hash:new boolean[]{false,true}) {var w=world(hash);w.updateLegacyBlock(TARGET,0,420<<13);assertTrue(f.validator.isScaffoldingPlacement(w,mapped,state(w,CLICK,0),TARGET));w.updateLegacyBlock(TARGET,0,1<<13);assertFalse(f.validator.isScaffoldingPlacement(w,mapped,null,TARGET));assertEquals(0,w.bucketPredictionRevision());}
    }

}
