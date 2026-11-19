package ac.navro.proxy.validator.inventory;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.data.inventory.*;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ItemUseEntryLifecycleTest {
    private final WirePlayerState player=new WirePlayerState();
    private final CompensatedInventory inventory=new CompensatedInventory(player);
    private final ItemTransactionValidator validator=new ItemTransactionValidator(player,inventory);
    private final Map<ItemDefinition,Integer> ids=new IdentityHashMap<>();
    private ItemData item(String name,int nativeId,int count) {
        var definition=new SimpleItemDefinition(name,nativeId+10000,false);ids.put(definition,nativeId);
        return ItemData.builder().definition(definition).count(count).tag(NbtMap.builder().putString("payload",name).build()).build();
    }
    private InventoryTransactionPacket packet(ItemData held,int action) {
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_USE);p.setHotbarSlot(inventory.heldItemSlot);p.setItemInHand(held);p.setBlockPosition(Vector3i.ZERO);p.setActionType(action);return p;
    }
    private final class Context implements ItemTransactionValidator.TransactionContext {
        Vec3 position=Vec3.ZERO;List<String> events=new ArrayList<>();
        Function<InventoryTransactionPacket,Boolean> click=p->true,release=p->true;
        public Vec3 position(){events.add("position");return position;}
        public int nativeItemId(ItemData item){events.add("nativeId");return item==ItemData.AIR?0:Objects.requireNonNull(ids.get(item.getDefinition()));}
        public boolean validate(ItemData p,ItemData c){events.add("validate");return ItemTransactionValidator.validate(p,c);}
        public boolean handleClickBlock(InventoryTransactionPacket p){events.add("block");return click.apply(p);}
        public boolean handleClickAir(InventoryTransactionPacket p){events.add("air");return click.apply(p);}
        public boolean handleRelease(InventoryTransactionPacket p){events.add("release");return release.apply(p);}
        public void validatorDecision(InventoryTransactionPacket p,String reason){events.add(reason);}
    }
    @Test void selectedSlotAndEveryActionOldDefinitionAreValidatedBeforeDistanceRegardlessOfSource() {
        var held=item("minecraft:stone",1,8);var other=item("minecraft:apple",260,4);inventory.heldItemSlot=2;inventory.inventoryContainer.set(2,held);inventory.inventoryContainer.set(7,other);
        var p=packet(held,0);p.setHotbarSlot(7);p.setBlockPosition(Vector3i.from(1,0,0));var c=new Context();assertTrue(validator.handle(p,c));assertTrue(c.events.contains("block"));
        p.getActions().add(new InventoryActionData(null,7,other.toBuilder().damage(999).count(-20).tag(NbtMap.EMPTY).build(),ItemData.AIR));
        p.setItemInHand(other);c.events.clear();assertTrue(validator.handle(p,c));assertEquals(List.of("validate","nativeId","position","block"),c.events);
        p.getActions().add(new InventoryActionData(InventorySource.fromGlobalInventory(),2,other,held));c.events.clear();
        assertFalse(validator.handle(p,c));assertEquals(List.of("validate","validate"),c.events);assertEquals(8,inventory.inventoryContainer.getHeldItemCache().count());
    }
    @Test void nativeAirWithPositiveCountAndCountEmptyItemsRetainOriginalShortCircuit() {
        var air=item("minecraft:air",0,7);inventory.inventoryContainer.set(0,air);var p=packet(item("custom:unresolved_air",0,3),9);var c=new Context();
        assertFalse(air.isNull());assertTrue(validator.handle(p,c));assertEquals(List.of("nativeId","nativeId","position"),c.events);
        inventory.inventoryContainer.set(0,item("custom:empty",123,-1));p.setItemInHand(item("custom:also_empty",456,0));c.events.clear();
        assertTrue(validator.handle(p,c));assertEquals(List.of("position"),c.events);
    }
    @Test void debugDecisionKeepsExactTokenAndOnlyRunsForNoActionHeldMismatch() {
        var stone=item("minecraft:stone",1,2);var apple=item("minecraft:apple",260,1);inventory.inventoryContainer.set(0,stone);var p=packet(apple,1);var c=new Context();
        assertFalse(validator.handle(p,c));assertEquals(List.of("nativeId","validate","REJECT_HELD_ITEM_MISMATCH"),c.events);
        p.getActions().add(new InventoryActionData(null,0,apple,ItemData.AIR));c.events.clear();assertFalse(validator.handle(p,c));assertEquals(List.of("validate"),c.events);
        p.getActions().clear();p.setItemInHand(stone);p.setBlockPosition(null);c.events.clear();assertFalse(validator.handle(p,c));assertEquals(List.of("nativeId","validate"),c.events);
    }
    @Test void distanceUsesAcceptedFloatPositionAndOriginalSumOverflowExceptionForAllUseActions() {
        var held=item("minecraft:stone",1,2);inventory.inventoryContainer.set(0,held);var p=packet(held,9);var c=new Context();
        p.setPlayerPosition(org.cloudburstmc.math.vector.Vector3f.from(999999,0,0));
        p.setBlockPosition(Vector3i.from(12,0,0));assertTrue(validator.handle(p,c));c.position=new Vec3(-0.000001f,0,0);assertFalse(validator.handle(p,c));
        c.position=Vec3.ZERO;for(var pos:List.of(Vector3i.from(100,-100,0),Vector3i.from(Integer.MAX_VALUE,1,Integer.MIN_VALUE),Vector3i.from(Integer.MAX_VALUE,Integer.MAX_VALUE,2))) {p.setBlockPosition(pos);assertTrue(validator.handle(p,c));}
        p.setBlockPosition(Vector3i.from(13,0,0));assertFalse(validator.handle(p,c));c.position=new Vec3(Float.NaN,0,0);assertTrue(validator.handle(p,c));
        c.position=new Vec3(16777216,0,0);p.setBlockPosition(Vector3i.from(16777217,0,0));assertTrue(validator.handle(p,c));
    }
    @Test void clickAirCompletesBeforeReferenceSwapAndOnlyTrueRunsArmorStage() {
        var held=item("minecraft:stone",1,2);inventory.inventoryContainer.set(0,held);inventory.armorContainer.set(35,item("minecraft:apple",260,9));
        var oldHotbar=inventory.inventoryContainer.getHeldItemCache();var oldArmor=inventory.armorContainer.get(35);var bundle=new BundleData(inventory);oldHotbar.setBundle(bundle);
        var p=packet(held,1);p.getActions().add(new InventoryActionData(null,0,held,ItemData.AIR));p.getLegacySlots().add(new LegacySetItemSlotData(6,new byte[]{35,(byte)255}));var c=new Context();
        c.click=data->{assertSame(p,data);assertSame(oldHotbar,inventory.inventoryContainer.getHeldItemCache());assertSame(oldArmor,inventory.armorContainer.get(35));return false;};
        assertFalse(validator.handle(p,c));assertSame(oldHotbar,inventory.inventoryContainer.getHeldItemCache());
        c.click=data->true;assertTrue(validator.handle(p,c));assertSame(oldArmor,inventory.inventoryContainer.getHeldItemCache());assertSame(oldHotbar,inventory.armorContainer.get(35));assertSame(bundle,inventory.armorContainer.get(35).getBundle());
        assertEquals(2,inventory.armorContainer.get(35).count());assertEquals(9,inventory.inventoryContainer.getHeldItemCache().count());
    }
    @Test void separatePayloadViewUsesRawActionsAndLegacySlotsAndObservesChildMutation() {
        var held=item("minecraft:stone",1,2);inventory.inventoryContainer.set(0,held);var raw=packet(null,9);var data=packet(held,1);
        raw.setHotbarSlot(-1);raw.getActions().add(new InventoryActionData(null,0,held,ItemData.AIR));raw.getLegacySlots().add(new LegacySetItemSlotData(6,new byte[]{3}));inventory.armorContainer.set(3,item("minecraft:apple",260,4));
        var hotbar=inventory.inventoryContainer.getHeldItemCache();var armor=inventory.armorContainer.get(3);
        var c=new ItemTransactionValidator.TransactionContext(){
            public Vec3 position(){return Vec3.ZERO;}public int nativeItemId(ItemData item){return ids.get(item.getDefinition());}
            public InventoryTransactionPacket useItemData(InventoryTransactionPacket p){assertSame(raw,p);return data;}
            public boolean handleClickBlock(InventoryTransactionPacket p){throw new AssertionError();}
            public boolean handleClickAir(InventoryTransactionPacket p){assertSame(data,p);return true;}
            public boolean handleRelease(InventoryTransactionPacket p){throw new AssertionError();}
        };
        assertTrue(validator.handle(raw,c));assertSame(armor,inventory.inventoryContainer.getHeldItemCache());assertSame(hotbar,inventory.armorContainer.get(3));

        var next=packet(armor.getData(),1);next.getActions().add(new InventoryActionData(null,0,armor.getData(),ItemData.AIR));next.getLegacySlots().add(new LegacySetItemSlotData(6,new byte[]{3}));
        var ctx=new Context();ctx.click=p->{inventory.heldItemSlot=1;return true;};assertTrue(validator.handle(next,ctx));assertSame(armor,inventory.inventoryContainer.get(0));assertSame(hotbar,inventory.armorContainer.get(3));
    }
    @Test void dispatchRequiresChildResultsAndPropagatesFailuresWithoutRunningArmor() {
        var held=item("minecraft:stone",1,2);inventory.inventoryContainer.set(0,held);var p=packet(held,0);var c=new Context();c.click=data->false;
        assertFalse(validator.handle(p,c));p.setActionType(1);p.getActions().add(new InventoryActionData(null,0,held,ItemData.AIR));p.getLegacySlots().add(new LegacySetItemSlotData(6,new byte[]{0}));var alias=inventory.inventoryContainer.getHeldItemCache();
        c.click=data->{alias.count(11);throw new IllegalStateException("child failed");};assertEquals("child failed",assertThrows(IllegalStateException.class,()->validator.handle(p,c)).getMessage());assertSame(alias,inventory.inventoryContainer.getHeldItemCache());assertEquals(11,alias.count());
        p.setTransactionType(InventoryTransactionType.ITEM_RELEASE);c.release=data->{assertSame(p,data);return false;};assertFalse(validator.handle(p,c));
        c.events.clear();p.setTransactionType(InventoryTransactionType.INVENTORY_MISMATCH);assertTrue(validator.handle(p,c));p.setTransactionType(InventoryTransactionType.ITEM_USE_ON_ENTITY);assertTrue(validator.handle(p,c));assertTrue(c.events.isEmpty());
        p.getActions().clear();p.setTransactionType(null);assertFalse(validator.handle(p,c));
    }
    @Test void routedDropUsesNativeAirIdentityAndKeepsNormalInventoryPrediction() {
        var stone=item("minecraft:stone",1,5);inventory.inventoryContainer.set(0,stone);var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.NORMAL);var c=new Context();
        p.getActions().add(new InventoryActionData(InventorySource.fromContainerWindowId(0),0,stone,stone));
        var nativeAir=item("minecraft:air",0,1);p.getActions().add(new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM),0,ItemData.AIR,nativeAir));
        assertFalse(validator.handle(p,c));assertEquals(5,inventory.inventoryContainer.getHeldItemCache().count());p.getActions().set(1,new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM),0,ItemData.AIR,stone.toBuilder().count(2).build()));
        assertTrue(validator.handle(p,c));assertEquals(3,inventory.inventoryContainer.getHeldItemCache().count());assertEquals(stone.getTag(),inventory.inventoryContainer.getHeldItemData().getTag());
    }
}
