package ac.navro.proxy.prediction.live;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemUseSlotOrderTest {
    static ItemData componentItem(String name, NbtMap data) {
        return ItemData.builder().count(1).definition(new ItemDefinition() {
            public String getIdentifier(){return name;}
            public int getRuntimeId(){return 123;}
            public boolean isComponentBased(){return false;}
            public NbtMap getComponentData(){return data;}
        }).build();
    }
    static void click(SessionState s, ItemData held) {
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_USE);
        p.setActionType(1);p.setHotbarSlot(s.inventory().heldItemSlot);p.setItemInHand(held);
        p.setBlockPosition(Vector3i.ZERO);s.observeClient(p);
    }
    static void equip(SessionState s, int slot) {
        var p=new MobEquipmentPacket();p.setRuntimeEntityId(7);p.setContainerId(0);
        p.setHotbarSlot(slot);s.observeClient(p);
    }
    static void metadata(SessionState s, boolean using, long marker) {
        s.player().getLatencyUtil().queue(marker,true);
        var p=new SetEntityDataPacket();p.setRuntimeEntityId(7);
        p.getMetadata().setFlag(EntityFlag.USING_ITEM,using);s.observeBackend(p);
    }
    static final class Walking {
        final SessionState s=AuthInputPacketsTest.session();
        final List<String> alerts=new ArrayList<>();
        final ItemData bow=PredictionItemUseTest.item("minecraft:bow",0);
        long tick=10;
        float z=8, velocity;
        Walking() {
            s.player().setAlertSink(alerts::add);PredictionItemUseTest.held(s,bow);
            click(s,bow);move(.122499995F,true);
            metadata(s,true,900);assertTrue(s.player().getLatencyUtil().onResponse(900));
        }
        void move(float factor, boolean start) {
            // Independent flat-ground integration: acceleration, movement, then surface drag.
            float travel=velocity+.1F*.98F*factor;z+=travel;velocity=travel*.54600006F;
            var input=AuthInputPacketsTest.input(tick++,8,64,z,new Vec3(0,-.0784F,velocity));
            input.setMotion(Vector2f.from(0,factor));input.setRawMoveVector(Vector2f.from(0,1));
            input.getInputData().add(PlayerAuthInputData.UP);
            input.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
            if(start)input.getInputData().add(PlayerAuthInputData.START_USING_ITEM);
            s.observeClient(input);
        }
        void clean(){assertTrue(alerts.isEmpty(),alerts.toString());assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);}
    }
    @Test void slotChangesFinishTheScaledMovementThenStopBeforeAnyServerConfirmation() {
        for(var name:List.of("minecraft:air","minecraft:stone","minecraft:slime","minecraft:bed"))for(int delay:new int[]{0,1,4}) {
            var w=new Walking();var s=w.s;w.clean();
            s.inventory().inventoryContainer.set(1,name.equals("minecraft:air")?ItemData.AIR:PredictionItemUseTest.item(name,0));
            equip(s,1);assertEquals(1,s.inventory().heldItemSlot);
            assertTrue(s.authInputPackets().itemUse().usingItemFlag());
            w.move(.122499995F,false);w.clean();
            assertFalse(s.authInputPackets().itemUse().usingItemFlag());
            assertTrue(s.authInputPackets().itemUse().tracker().hasPredictionItemSlowdown());
            for(int i=0;i<delay;i++){w.move(1F,false);w.clean();}
            metadata(s,false,901);
            w.move(1F,false);w.clean();
            assertTrue(s.player().getLatencyUtil().onResponse(901));
            w.move(1F,false);w.clean();w.move(1F,false);w.clean();
            assertFalse(s.authInputPackets().itemUse().tracker().hasPredictionItemSlowdown());
        }
    }
    @Test void changingTheSlotCannotEraseTheAlreadyScaledMovement() {
        var w=new Walking();equip(w.s,1);w.move(1F,false);
        assertTrue(w.alerts.stream().anyMatch(a->a.contains("NoSlow")),w.alerts.toString());
    }
    @Test void slotChangesDoNotClearAReplacementUseStartedInTheMovementInput() {
        var w=new Walking();var s=w.s;
        s.inventory().inventoryContainer.set(1,PredictionItemUseTest.item("minecraft:cooked_beef",0));
        equip(s,1);w.move(.122499995F,true);w.clean();
        assertTrue(s.authInputPackets().itemUse().usingItemFlag());
        assertEquals("minecraft:cooked_beef",s.authInputPackets().itemUse().tracker().getUsedItem().getDefinition().getIdentifier());
        w.move(.122499995F,false);w.clean();
    }
    @Test void lateStartMetadataCannotResurrectTheItemDiscardedAtTheMovementBoundary() {
        var w=new Walking();equip(w.s,1);w.move(.122499995F,false);w.clean();
        metadata(w.s,true,901);assertTrue(w.s.player().getLatencyUtil().onResponse(901));
        w.move(1F,false);w.clean();assertFalse(w.s.authInputPackets().itemUse().usingItemFlag());
    }
    @Test void returningToTheLatchedItemWaitsForFreshUseMetadataAfterAnAcknowledgedStop() {
        var w=new Walking();var s=w.s;
        s.inventory().inventoryContainer.set(1,ItemData.AIR);
        equip(s,1);w.move(.122499995F,false);w.clean();
        metadata(s,false,901);w.move(1F,false);w.clean();
        assertTrue(s.player().getLatencyUtil().onResponse(901));
        equip(s,0);w.move(1F,true);w.clean();
        assertFalse(s.authInputPackets().itemUse().usingItemFlag());
        assertFalse(s.authInputPackets().itemUse().tracker().hasPredictionItemSlowdown());
        metadata(s,true,902);assertTrue(s.player().getLatencyUtil().onResponse(902));
        w.move(.122499995F,false);w.clean();
        w.move(1F,false);
        assertTrue(w.alerts.stream().anyMatch(a->a.contains("NoSlow")),w.alerts.toString());
    }
    @Test void anAcknowledgedStopStillAppliesBeforeTheNextMovement() {
        var w=new Walking();equip(w.s,1);metadata(w.s,false,901);
        assertTrue(w.s.player().getLatencyUtil().onResponse(901));
        w.move(1F,false);w.clean();w.move(1F,false);w.clean();
    }
    @Test void anExplicitReleaseEndsUseWithoutWaitingForMetadata() {
        var w=new Walking();equip(w.s,1);
        var p=new InventoryTransactionPacket();p.setTransactionType(InventoryTransactionType.ITEM_RELEASE);p.setActionType(0);
        w.s.observeClient(p);w.move(1F,false);w.clean();
    }
    @Test void changingTheItemCancelsAnUnconfirmedRequestInsteadOfApplyingItToTheNewItem() {
        var s=AuthInputPacketsTest.session();var bow=PredictionItemUseTest.item("minecraft:bow",0);
        PredictionItemUseTest.held(s,bow);s.inventory().inventoryContainer.set(1,PredictionItemUseTest.item("minecraft:stone",0));
        click(s,bow);assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,s.authInputPackets().itemUse().tracker().getDirtyUsing());
        equip(s,1);assertEquals(ItemUseTracker.DirtyUsing.NONE,s.authInputPackets().itemUse().tracker().getDirtyUsing());
        var input=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));
        input.getInputData().add(PlayerAuthInputData.START_USING_ITEM);s.observeClient(input);
        assertFalse(s.authInputPackets().itemUse().tracker().hasPredictionItemSlowdown());
    }
    @Test void genericComponentNbtDoesNotTurnBlocksIntoHoldToUseItems() {
        for(var name:List.of("minecraft:stone","minecraft:slime","minecraft:bed")) {
            var s=AuthInputPacketsTest.session();var block=componentItem(name,NbtMap.EMPTY);PredictionItemUseTest.held(s,block);
            var use=s.authInputPackets().itemUse();use.tracker().setUseButtonLatched(true);
            click(s,block);
            assertEquals(ItemUseTracker.DirtyUsing.NONE,use.tracker().getDirtyUsing(),name);
            assertFalse(use.usingItemFlag(),name);
            assertFalse(use.hasUseDurationComponent(use.nativeItem(block)),name);
        }
    }
    @Test void explicitUseDurationComponentsAndNativeFoodStillQualify() {
        var use=AuthInputPacketsTest.session().authInputPackets().itemUse();
        var data=NbtMap.builder().putCompound("components",NbtMap.builder().putInt("minecraft:use_duration",32).build()).build();
        assertTrue(use.hasUseDurationComponent(componentItem("minecraft:stone",data)));
        var food=PredictionItemUseTest.item("minecraft:cooked_beef",0);
        use.tracker().use(use.nativeItem(food),use.nativeItemId(food),false);
        assertEquals(ItemUseTracker.DirtyUsing.INVENTORY_TRANSACTION,use.tracker().getDirtyUsing());
    }
}
