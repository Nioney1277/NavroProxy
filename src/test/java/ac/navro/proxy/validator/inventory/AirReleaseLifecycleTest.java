package ac.navro.proxy.validator.inventory;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.*;
import ac.navro.proxy.state.WirePlayerState;
import com.google.gson.*;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class AirReleaseLifecycleTest {
    static final class Fixture {
        final ItemUseTestModel model;final ItemUseTracker<ItemUseTestModel.Item> tracker;final WirePlayerState player=new WirePlayerState();final CompensatedInventory inventory=new CompensatedInventory(player);final ItemTransactionValidator validator=new ItemTransactionValidator(player,inventory);final Map<ItemData,ItemUseTestModel.Item> bindings=new IdentityHashMap<>();final List<String> reads=new ArrayList<>();boolean serverGlide,serverElytra,noInventory,denyTracker;int flight=1;boolean denyFlight;
        Fixture()throws Exception{try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-air-release.jsonl.gz")),StandardCharsets.UTF_8))){var h=JsonParser.parseString(in.readLine()).getAsJsonObject();model=new ItemUseTestModel(h.getAsJsonArray("profiles"),h.getAsJsonObject("air"));}tracker=new ItemUseTracker<>(model);inventory.heldItemSlot=2;held(0,7);chest(6,1);}
        ItemData data(ItemUseTestModel.Item item){var p=item.profile;return ItemData.builder().definition(new SimpleItemDefinition(p.get("name").isJsonNull()?null:p.get("name").getAsString(),p.get("runtime").getAsInt(),false)).count(item.count).tag(NbtMap.builder().putString("payload","unchanged").build()).build();}
        void held(int index,int count){var item=new ItemUseTestModel.Item(index,model.inputs.get(index).profile,count);inventory.inventoryContainer.set(2,data(item));bindings.put(inventory.inventoryContainer.getHeldItemData(),item);}
        void chest(int index,int count){var item=new ItemUseTestModel.Item(index,model.inputs.get(index).profile,count);inventory.armorContainer.set(1,data(item));bindings.put(inventory.armorContainer.get(1).getData(),item);}
        ItemUseTestModel.Item bound(ItemData item){return item==ItemData.AIR?model.air:Objects.requireNonNull(bindings.get(item));}
        InventoryTransactionPacket packet(){var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_USE);p.setActionType(1);p.setHotbarSlot(2);p.setBlockPosition(Vector3i.ZERO);p.setItemInHand(inventory.inventoryContainer.getHeldItemData());return p;}
        final ItemTransactionValidator.ClickAirContext<ItemUseTestModel.Item> air=new ItemTransactionValidator.ClickAirContext<>(){
            public boolean validate(ItemData p,ItemData c){return ItemTransactionValidator.validate(p,c);}public boolean nativeFirework(ItemData item){return bound(item).profile.get("firework").getAsBoolean();}public boolean serverGliding(){reads.add("serverGlide");return serverGlide;}public boolean serverChestIsElytra(){reads.add("serverChest");if(noInventory)throw new NullPointerException();return serverElytra;}public boolean compensatedGliding(){reads.add("compGlide");return model.hasFlag(32);}public boolean nativeIsNull(ItemData item){reads.add("chestEmpty");return model.isNull(bound(item));}public int nativeItemId(ItemData item){reads.add("nativeId");return model.nativeItemId(bound(item));}public int nativeFireworkFlight(ItemData item){reads.add("flight");if(denyFlight)throw new AssertionError("no boost expected");return flight;}public boolean usingItemFlag(){reads.add("using");return model.hasFlag(-1);}public ItemUseTracker<ItemUseTestModel.Item> tracker(){reads.add("tracker");if(denyTracker)throw new AssertionError("fireworks must not use tracker");return tracker;}public ItemUseTestModel.Item nativeItem(ItemData item){return bound(item);}
        };
    }
    @Test void repeatedClickAirDoesNotRestartTrackerAndRootEntryStillPerformsOriginalArmorSwap()throws Exception {
        var f=new Fixture();var t=f.tracker;t.use(f.model.item(0),261,true);t.applyPredictedUseStart();f.model.since=27;var used=t.getUsedItem();var hotbar=f.inventory.inventoryContainer.getHeldItemCache();var armor=f.inventory.armorContainer.get(1);var p=f.packet();p.getActions().add(new InventoryActionData(InventorySource.fromGlobalInventory(),2,p.getItemInHand(),ItemData.AIR));p.getLegacySlots().add(new LegacySetItemSlotData(6,new byte[]{1}));
        var context=new ItemTransactionValidator.TransactionContext(){public Vec3 position(){return Vec3.ZERO;}public int nativeItemId(ItemData item){return f.model.nativeItemId(f.bound(item));}public boolean handleClickAir(InventoryTransactionPacket data){return f.validator.handleClickAir(data,f.air);}public boolean handleClickBlock(InventoryTransactionPacket data){throw new AssertionError();}public boolean handleRelease(InventoryTransactionPacket data){throw new AssertionError();}};
        assertTrue(f.validator.handle(p,context));assertSame(used,t.getUsedItem());assertEquals(27,f.model.since);assertFalse(f.reads.contains("tracker"));assertSame(armor,f.inventory.inventoryContainer.getHeldItemCache());assertSame(hotbar,f.inventory.armorContainer.get(1));assertEquals("unchanged",f.inventory.armorContainer.get(1).getData().getTag().getString("payload"));
    }
    @Test void releaseCapturesCandidatesInServerActiveCompensatedOrderBeforeClearingChargeAndDirtyState()throws Exception {
        var f=new Fixture();f.held(3,1);var t=f.tracker;t.use(f.model.item(3),455,true);t.applyPredictedUseStart();f.model.since=9;var p=f.packet();p.setActionType(0);var visited=new ArrayList<ItemUseTestModel.Item>();var chosen=new ArrayList<ItemUseTestModel.Item>();var charge=new ArrayList<Integer>();
        var context=new ItemTransactionValidator.ReleaseContext<ItemUseTestModel.Item>(){public InventoryTransactionPacket releaseItemData(InventoryTransactionPacket packet){return packet;}public ItemUseTracker<ItemUseTestModel.Item> tracker(){return t;}public ItemUseTestModel.Item nativeItem(ItemData item){return f.bound(item);}public ItemUseTestModel.Item serverHeldItem(){return f.model.item(2);}public int riptideLevel(ItemUseTestModel.Item item){visited.add(item);return item==null||f.model.isNull(item)?0:item.profile.get("riptide").getAsInt();}public void setDirtyRiptide(int since,ItemUseTestModel.Item item){charge.add(since);chosen.add(item);}};
        assertTrue(f.validator.handleRelease(p,context));assertEquals(List.of(f.model.item(2)),visited);assertEquals(List.of(f.model.item(2)),chosen);assertEquals(List.of(9),charge);assertEquals(0,f.model.since);assertSame(f.model.air,t.getUsedItem());assertEquals(ItemUseTracker.DirtyUsing.NONE,t.getDirtyUsing());assertFalse(f.model.hasFlag(-1));
        t.use(f.model.item(3),455,true);var original=t.getUsedItem();f.model.since=11;visited.clear();chosen.clear();var mutate=new ItemTransactionValidator.ReleaseContext<ItemUseTestModel.Item>(){public InventoryTransactionPacket releaseItemData(InventoryTransactionPacket packet){return packet;}public ItemUseTracker<ItemUseTestModel.Item> tracker(){return t;}public ItemUseTestModel.Item nativeItem(ItemData item){return f.bound(item);}public ItemUseTestModel.Item serverHeldItem(){return f.model.item(4);}public int riptideLevel(ItemUseTestModel.Item item){visited.add(item);if(item==f.model.item(4)){t.setUsedItem(f.model.item(0));return 0;}return item.profile.get("riptide").getAsInt();}public void setDirtyRiptide(int since,ItemUseTestModel.Item item){assertEquals(11,since);chosen.add(item);}};
        assertTrue(f.validator.handleRelease(p,mutate));assertEquals(List.of(f.model.item(4),original),visited);assertEquals(List.of(original),chosen);
    }
    @Test void absentOrNonReleasePayloadIsNoopAndServerReadFailurePrecedesTrackerClear()throws Exception {
        var f=new Fixture();var t=f.tracker;t.use(f.model.item(3),455,true);t.applyPredictedUseStart();f.model.since=17;var original=t.getUsedItem();var p=f.packet();var calls=new ArrayList<String>();boolean[] hasData={false};
        var context=new ItemTransactionValidator.ReleaseContext<ItemUseTestModel.Item>(){public InventoryTransactionPacket releaseItemData(InventoryTransactionPacket packet){calls.add("data");return hasData[0]?packet:null;}public ItemUseTracker<ItemUseTestModel.Item> tracker(){calls.add("tracker");return t;}public ItemUseTestModel.Item nativeItem(ItemData item){calls.add("held");return f.bound(item);}public ItemUseTestModel.Item serverHeldItem(){calls.add("server");throw new NullPointerException();}public int riptideLevel(ItemUseTestModel.Item item){throw new AssertionError();}public void setDirtyRiptide(int since,ItemUseTestModel.Item item){throw new AssertionError();}};
        assertTrue(f.validator.handleRelease(p,context));assertEquals(List.of("data"),calls);hasData[0]=true;for(int action:new int[]{1,99}){calls.clear();p.setActionType(action);assertTrue(f.validator.handleRelease(p,context));assertEquals(List.of("data"),calls);}p.setActionType(0);calls.clear();assertThrows(NullPointerException.class,()->f.validator.handleRelease(p,context));assertEquals(List.of("data","tracker","held","server"),calls);assertSame(original,t.getUsedItem());assertEquals(17,f.model.since);assertTrue(f.model.hasFlag(-1));
    }
    @Test void fireworksUseSeparateNativeAndCompensatedElytraRulesAndNeverEnterTracker()throws Exception {
        var f=new Fixture();f.held(5,7);f.chest(11,1);f.serverGlide=true;f.serverElytra=false;f.model.setFlag(32,true);f.model.setFlag(-1,true);f.denyTracker=true;f.flight=2;var held=f.inventory.inventoryContainer.getHeldItemCache();assertTrue(f.validator.handleClickAir(f.packet(),f.air));assertEquals(41,f.player.glideBoostTicks());assertFalse(f.player.nukkitGlideBoostPending);assertFalse(f.reads.contains("using"));assertFalse(f.reads.contains("tracker"));assertSame(f.model.air,f.tracker.getUsedItem());assertEquals(7,held.count());
        f.serverElytra=true;f.flight=1;assertTrue(f.validator.handleClickAir(f.packet(),f.air));assertTrue(f.player.nukkitGlideBoostPending);assertEquals(41,f.player.glideBoostTicks());f.serverElytra=false;assertTrue(f.validator.handleClickAir(f.packet(),f.air));assertTrue(f.player.nukkitGlideBoostPending);
        f.chest(6,0);f.denyFlight=true;f.reads.clear();assertTrue(f.validator.handleClickAir(f.packet(),f.air));assertFalse(f.reads.contains("nativeId"));assertFalse(f.reads.contains("flight"));
        f.noInventory=true;f.serverGlide=false;f.reads.clear();assertThrows(NullPointerException.class,()->f.validator.handleClickAir(f.packet(),f.air));assertEquals(List.of("serverGlide","serverChest"),f.reads);assertEquals(41,f.player.glideBoostTicks());
    }
}
