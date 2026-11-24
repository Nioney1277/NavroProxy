package ac.navro.proxy.validator.inventory;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ClickBlockLifecycleTest {
    static final Vector3i POS=Vector3i.from(8,64,8),TARGET=Vector3i.from(9,64,8);
    record Info(int id,int damage,NativeItemPlacementProperties.Properties placement){}
    static final class World extends CompensatedWorld {Runnable afterPrediction=()->{};List<String> events;@Override public void predictLocalBlock(Vector3i p,int layer,int full){events.add("prediction");super.predictLocalBlock(p,layer,full);afterPrediction.run();}}
    static final class Fixture {
        final WirePlayerState player=new WirePlayerState();final CompensatedInventory inventory=new CompensatedInventory(player);final ItemTransactionValidator validator=new ItemTransactionValidator(player,inventory);final World world=new World();final List<String> events=new ArrayList<>();final Map<ItemDefinition,Info> items=new IdentityHashMap<>();final List<Vector3i> restores=new ArrayList<>();
        ItemTransactionValidator.PlacementChecks checks;boolean sneak;Integer face=5;Consumer<Vector3i> restore=p->{};int nextWire=30000;
        Fixture(boolean hash){player.tickSinceBlockResync=13;player.acknowledgeGameType(GameType.SURVIVAL);inventory.heldItemSlot=2;world.events=events;world.setProtocolVersion(2193);var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);world.apply(start);world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));inventory.inventoryContainer.set(2,stack("minecraft:stone",1,0,7));inventory.inventoryContainer.set(4,stack("minecraft:apple",260,0,20));world.updateLegacyBlock(POS,0,1<<13);}
        ItemData stack(String name,int nativeId,int damage,int count){var definition=new SimpleItemDefinition(name,nextWire++,false);items.put(definition,new Info(nativeId,damage,NativeItemPlacementProperties.forNumeric(nativeId,damage)));return ItemData.builder().definition(definition).damage(damage).count(count).tag(NbtMap.builder().putString("payload","kept").build()).build();}
        ItemData held(){return inventory.inventoryContainer.getHeldItemData();}
        InventoryTransactionPacket packet(){var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_USE);p.setActionType(0);p.setHotbarSlot(2);p.setBlockPosition(POS);p.setItemInHand(held());p.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);p.setClickPosition(Vector3f.from(0,0.5,0));return p;}
        ItemTransactionValidator.PlacementChecks checks(ItemTransactionValidator.AirLiquidPlaceCheck air,ItemTransactionValidator.PlacementObserver rotation){return new ItemTransactionValidator.PlacementChecks(){public ItemTransactionValidator.AirLiquidPlaceCheck airLiquidPlace(){events.add("airLookup");return air;}public ItemTransactionValidator.PlacementObserver rotationPlace(){events.add("rotationLookup");return rotation;}};}
        final ItemTransactionValidator.ClickBlockContext context=new ItemTransactionValidator.ClickBlockContext(){
            public CompensatedWorld world(){events.add("world");return world;}public boolean validate(ItemData p,ItemData c){return ItemTransactionValidator.validate(p,c);}public Integer resolvedFace(InventoryTransactionPacket p){events.add("face");return face;}public boolean sneaking(){events.add("sneak");return sneak;}public ItemTransactionValidator.PlacementChecks checks(){events.add("holder");return checks;}public ItemTransactionValidator.ActivationObserver trapdoorSampler(){events.add("samplerLookup");return (p,b,a,phase,trace)->0;}
            Info info(ItemData i){return Objects.requireNonNull(items.get(i.getDefinition()));}public int nativeItemId(ItemData i){events.add("id");return info(i).id();}public int nativeItemDamage(ItemData i){return info(i).damage();}public int nativeBlockId(ItemData i){events.add("mapped");return info(i).placement().blockId();}public boolean nativeIsNull(ItemData i){events.add("empty");return i.getCount()<=0||info(i).id()==0;}public boolean nativeCanBePlaced(ItemData i){events.add("canPlace");return info(i).placement().canBePlaced();}public NativeItemPlacementProperties.Properties placementProperties(ItemData i){events.add("placementView");return info(i).placement();}public ItemData createNativeBucket(int damage){var item=stack("native:bucket",325,damage,1);return item.toBuilder().tag(null).build();}public void restoreCorrectBlock(Vector3i p){restores.add(p);restore.accept(p);}
        };
    }
    @Test void nullPositionClaimMismatchPredictionFailureAndMissingFaceStopBeforeWorld() {
        var f=new Fixture(false);var p=f.packet();p.setBlockPosition(null);assertFalse(f.validator.handleClickBlock(p,null));p=f.packet();p.setItemInHand(null);assertTrue(f.validator.handleClickBlock(p,null));
        p=f.packet();p.setItemInHand(f.stack("minecraft:apple",260,0,1));assertTrue(f.validator.handleClickBlock(p,f.context));assertTrue(f.events.isEmpty());
        p=f.packet();p.setClientInteractPrediction(null);assertTrue(f.validator.handleClickBlock(p,f.context));assertTrue(f.events.isEmpty());p.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);f.face=null;assertFalse(f.validator.handleClickBlock(p,f.context));assertEquals(List.of("face"),f.events);
    }
    @Test void framesAndProperBellHitsReturnBeforeChecksAndInventoryPrediction() {
        for(boolean hash:new boolean[]{false,true}){var f=new Fixture(hash);f.checks=f.checks((d,h,c)->{throw new AssertionError("unexpected check");},null);f.world.updateLegacyBlock(POS,0,199<<13);var alias=f.inventory.inventoryContainer.getHeldItemCache();assertTrue(f.validator.handleClickBlock(f.packet(),f.context));assertFalse(f.events.contains("empty"));assertFalse(f.events.contains("holder"));
            f.events.clear();f.world.updateLegacyBlock(POS,0,(461<<13)|4);assertTrue(f.validator.handleClickBlock(f.packet(),f.context));assertFalse(f.events.contains("holder"));assertFalse(f.events.contains("prediction"));assertSame(alias,f.inventory.inventoryContainer.getHeldItemCache());assertEquals(7,alias.count());assertEquals(0,f.world.bucketPredictionRevision());}
    }
    @Test void sneakingOnlySuppressesActivationWithAnExistingNativeHeldItem() {
        for(boolean hash:new boolean[]{false,true}){var f=new Fixture(hash);f.sneak=true;f.world.updateLegacyBlock(POS,0,64<<13);assertTrue(f.validator.handleClickBlock(f.packet(),f.context));assertEquals(f.world.networkIdForLegacy(64<<13),f.world.getRawBlockAt(8,64,8,0));assertEquals(f.world.networkIdForLegacy(1<<13),f.world.getRawBlockAt(9,64,8,0));assertEquals(6,f.held().getCount());
            var empty=new Fixture(hash);empty.sneak=true;empty.inventory.inventoryContainer.set(2,empty.stack("minecraft:air",0,0,7));empty.world.updateLegacyBlock(POS,0,64<<13);assertTrue(empty.validator.handleClickBlock(empty.packet(),empty.context));assertEquals(empty.world.networkIdForLegacy((64<<13)|4),empty.world.getRawBlockAt(8,64,8,0));assertEquals(7,empty.held().getCount());assertEquals(0,empty.world.bucketPredictionRevision());assertFalse(empty.events.contains("sneak"));}
    }
    @Test void rejectingOrThrowingCheckOwnsCancellationBeforeScaffoldingAirCorrectionsAndPrediction() {
        var f=new Fixture(false);f.inventory.inventoryContainer.set(2,f.stack("minecraft:scaffolding",255-420,0,7));f.world.updateLegacyBlock(POS,0,0);f.world.updateLegacyBlock(TARGET,0,420<<13);var alias=f.inventory.inventoryContainer.getHeldItemCache();f.checks=f.checks((data,held,state)->{assertSame(alias.getData(),held);return true;},null);
        assertFalse(f.validator.handleClickBlock(f.packet(),f.context));assertFalse(f.events.contains("mapped"));assertFalse(f.events.contains("prediction"));assertTrue(f.restores.isEmpty());assertEquals(13,f.player.tickSinceBlockResync);assertSame(alias,f.inventory.inventoryContainer.getHeldItemCache());
        f.checks=f.checks((data,held,state)->{throw new IllegalStateException("check failed");},null);assertThrows(IllegalStateException.class,()->f.validator.handleClickBlock(f.packet(),f.context));assertTrue(f.restores.isEmpty());assertEquals(0,f.world.bucketPredictionRevision());
    }
    @Test void rotationLookupOccursAfterPredictionAndUsesChangedHolderAndCurrentHeldSlot() {
        for(boolean hash:new boolean[]{false,true}){var f=new Fixture(hash);var original=f.inventory.inventoryContainer.getHeldItemCache();var other=f.inventory.inventoryContainer.getItemFromSlot(4);var p=f.packet();p.setHotbarSlot(7);
            f.checks=f.checks(null,(data,target,id)->{throw new AssertionError("stale rotation check");});var after=f.checks(null,(data,target,id)->{assertSame(p,data);assertEquals(TARGET,target);assertEquals(1,id);assertEquals(f.world.networkIdForLegacy(1<<13),f.world.getRawBlockAt(9,64,8,0));assertEquals(7,original.count());f.inventory.heldItemSlot=4;});f.world.afterPrediction=()->f.checks=after;
            assertTrue(f.validator.handleClickBlock(p,f.context));assertEquals(7,original.count());assertEquals(19,other.count());assertEquals(4,f.inventory.heldItemSlot);assertTrue(f.events.indexOf("prediction")<f.events.indexOf("rotationLookup"));assertEquals(2,f.events.stream().filter("holder"::equals).count());}
    }
    @Test void lateCheckFailureKeepsWorldPredictionAndLateCreativeModeSkipsConsumption() {
        var f=new Fixture(false);var alias=f.inventory.inventoryContainer.getHeldItemCache();f.checks=f.checks(null,null);f.world.afterPrediction=()->f.checks=f.checks(null,(data,target,id)->{throw new IllegalStateException("rotation failed");});assertThrows(IllegalStateException.class,()->f.validator.handleClickBlock(f.packet(),f.context));assertEquals(1,f.world.bucketPredictionRevision());assertSame(alias,f.inventory.inventoryContainer.getHeldItemCache());assertEquals(7,alias.count());
        var creative=new Fixture(false);creative.checks=creative.checks((data,held,state)->{creative.player.acknowledgeGameType(GameType.CREATIVE);return false;},null);assertTrue(creative.validator.handleClickBlock(creative.packet(),creative.context));assertEquals(7,creative.held().getCount());assertEquals(1,creative.world.bucketPredictionRevision());
    }
    @Test void airCorrectionsUseAdjacentThenClickedAndSetResyncOnlyAfterBothCallsComplete() {
        var f=new Fixture(false);f.world.updateLegacyBlock(POS,0,0);var alias=f.inventory.inventoryContainer.getHeldItemCache();assertFalse(f.validator.handleClickBlock(f.packet(),f.context));assertEquals(List.of(TARGET,POS),f.restores);assertEquals(5,f.player.tickSinceBlockResync);assertSame(alias,f.inventory.inventoryContainer.getHeldItemCache());assertEquals(0,f.world.bucketPredictionRevision());
        var fail=new Fixture(false);fail.world.updateLegacyBlock(POS,0,0);fail.restore=p->{if(p.equals(POS))throw new IllegalStateException("second correction failed");};assertThrows(IllegalStateException.class,()->fail.validator.handleClickBlock(fail.packet(),fail.context));assertEquals(List.of(TARGET,POS),fail.restores);assertEquals(13,fail.player.tickSinceBlockResync);assertEquals(7,fail.held().getCount());
    }
    @Test void actualUseEntryCanCallCompleteClickDispatcherAndRetainsItsOwnUpstreamGates() {
        var f=new Fixture(false);f.inventory.inventoryContainer.set(2,f.stack("minecraft:stone",1,0,1));var p=f.packet();var context=new ItemTransactionValidator.TransactionContext(){public Vec3 position(){return new Vec3(8,64,8);}public int nativeItemId(ItemData item){return f.items.get(item.getDefinition()).id();}public boolean handleClickBlock(InventoryTransactionPacket data){assertSame(p,data);return f.validator.handleClickBlock(data,f.context);}public boolean handleClickAir(InventoryTransactionPacket data){throw new AssertionError();}public boolean handleRelease(InventoryTransactionPacket packet){throw new AssertionError();}};
        p.setHotbarSlot(9);assertFalse(f.validator.handle(p,context));assertEquals(0,f.world.bucketPredictionRevision());p.setHotbarSlot(7);assertTrue(f.validator.handle(p,context));assertEquals(1,f.world.bucketPredictionRevision());assertSame(ItemCache.AIR,f.inventory.inventoryContainer.getHeldItemCache());
    }
}
