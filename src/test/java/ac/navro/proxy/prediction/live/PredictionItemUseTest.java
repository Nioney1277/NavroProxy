package ac.navro.proxy.prediction.live;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;


import ac.navro.proxy.prediction.RiptidePhysics;


import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.inventory.NativePredictionItems;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PredictionItemUseTest {
    static ItemData item(String name,int damage){return ItemData.builder().definition(new ItemDefinition(){public String getIdentifier(){return name;}public int getRuntimeId(){return 123;}public boolean isComponentBased(){return false;}}).count(1).damage(damage).build();}
    static void held(SessionState s,ItemData item){s.inventory().inventoryContainer.set(0,item);s.inventory().heldItemSlot=0;}
    @Test void detachedDecoderPreservesRenamedAndMetadataBasedNativeItems(){
        for(int protocol:new int[]{898,944,1001,2168,2193,2225}){
            assertEquals(261,NativePredictionItems.resolve(protocol,item("minecraft:bow",0)).nativeId());
            var milk=NativePredictionItems.resolve(protocol,item("minecraft:milk_bucket",0));assertTrue(milk.canRelease());assertEquals(32,milk.duration());
            assertFalse(NativePredictionItems.resolve(protocol,item("minecraft:bucket",0)).canRelease());
            assertEquals(444,NativePredictionItems.resolve(protocol,item("minecraft:elytra",123)).nativeId());
            assertEquals(0,NativePredictionItems.resolve(protocol,ItemData.AIR).nativeId());
        }
    }
    @Test void clickAirStartsRequestAndStartEdgeFreezesOriginalSlowdown(){
        var s=AuthInputPacketsTest.session();var bow=item("minecraft:bow",0);held(s,bow);
        var transaction=new InventoryTransactionPacket();transaction.setTransactionType(InventoryTransactionType.ITEM_USE);transaction.setActionType(1);transaction.setItemInHand(bow);transaction.setHotbarSlot(0);transaction.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);
        s.observeClient(transaction);var tracker=s.authInputPackets().itemUse().tracker();assertEquals(261,tracker.getJavaItemId());assertFalse(tracker.hasPredictionItemSlowdown());
        var input=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));input.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_USING_ITEM);s.observeClient(input);
        assertEquals(.122499995F,tracker.getPredictionItemSlowdownFactor());assertTrue(s.authInputPackets().context().getItemUseTracker().hasPredictionItemSlowdown());
        var release=new InventoryTransactionPacket();release.setTransactionType(InventoryTransactionType.ITEM_RELEASE);release.setActionType(0);s.observeClient(release);
        assertFalse(s.authInputPackets().itemUse().usingItemFlag());
    }
    @Test void authoritativeUseMetadataWaitsForItsClosingAcknowledgment(){
        var s=AuthInputPacketsTest.session();held(s,item("minecraft:bow",0));var tracker=s.authInputPackets().itemUse().tracker();
        s.player().getLatencyUtil().queue(90,true);
        var packet=new SetEntityDataPacket();packet.setRuntimeEntityId(7);packet.getMetadata().setFlag(EntityFlag.USING_ITEM,true);s.observeBackend(packet);
        assertEquals(ac.navro.proxy.data.ItemUseTracker.DirtyUsing.NONE,tracker.getDirtyUsing());
        s.player().getLatencyUtil().onResponse(90);assertEquals(ac.navro.proxy.data.ItemUseTracker.DirtyUsing.METADATA,tracker.getDirtyUsing());
    }
    @Test void rejectedGlideAndBackwardSprintActionsCannotChangeCanonicalMovementState(){
        var s=AuthInputPacketsTest.session();var p=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));
        p.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_GLIDING);p.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_SPRINTING);s.observeClient(p);
        assertFalse(p.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_GLIDING));assertFalse(p.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_SPRINTING));assertFalse(s.player().gliding());assertFalse(s.player().predictionSprinting());
    }

    static ItemData origin(int id,int damage,NbtMap inner){return item("minecraft:info_update",0).toBuilder().tag(NbtMap.builder()
            .putInt("mv_origin_id",id).putInt("mv_origin_meta",damage).putCompound("mv_origin_nbt",inner).build()).build();}
    @Test void restoredBowUsesOriginalHandshakeAndSlowdownWithoutChangingTheWireCache(){
        var s=AuthInputPacketsTest.session();var bow=origin(261,25,NbtMap.builder().putString("display","inner").build());held(s,bow);
        var packet=new InventoryTransactionPacket();packet.setTransactionType(InventoryTransactionType.ITEM_USE);packet.setActionType(1);
        packet.setItemInHand(item("minecraft:bow",25));packet.setHotbarSlot(0);packet.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);
        s.observeClient(packet);var use=s.authInputPackets().itemUse();assertEquals(261,use.tracker().getJavaItemId());
        var input=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));input.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_USING_ITEM);s.observeClient(input);
        assertEquals(.122499995F,use.tracker().getPredictionItemSlowdownFactor());
        assertEquals("minecraft:info_update",s.inventory().inventoryContainer.getHeldItemData().getDefinition().getIdentifier());
        assertTrue(bow.getTag().containsKey("mv_origin_id"));assertEquals("minecraft:bow",packet.getItemInHand().getDefinition().getIdentifier());
    }
    @Test void serverHeldRestoredTridentHasPriorityAtReleaseBeforeInventoryAcknowledgment(){
        var s=AuthInputPacketsTest.session();held(s,item("minecraft:bow",0));var use=s.authInputPackets().itemUse();
        var ench=NbtMap.builder().putList("ench",org.cloudburstmc.nbt.NbtType.COMPOUND,java.util.List.of(NbtMap.builder().putInt("id",30).putInt("lvl",3).build())).build();
        var trident=origin(455,18,ench);s.player().getLatencyUtil().queue(90,true);
        var slot=new InventorySlotPacket();slot.setContainerId(0);slot.setSlot(0);slot.setItem(trident);s.observeBackend(slot);
        assertEquals("minecraft:bow",s.inventory().inventoryContainer.getHeldItemData().getDefinition().getIdentifier());
        use.sinceTridentUse(12);var release=new InventoryTransactionPacket();release.setTransactionType(InventoryTransactionType.ITEM_RELEASE);release.setActionType(0);s.observeClient(release);
        var context=s.authInputPackets().context();assertTrue(context.dirtyRiptide);assertEquals(3,RiptidePhysics.level(context.riptideItem));assertEquals(18,context.riptideItem.getDamage());
        assertTrue(s.player().getLatencyUtil().onResponse(90));assertEquals("minecraft:info_update",s.inventory().inventoryContainer.getHeldItemData().getDefinition().getIdentifier());
        assertEquals(3,use.riptideLevel(s.inventory().inventoryContainer.getHeldItemData()));
    }
    @Test void restoredBootsReadInnerEnchantmentsOnlyAfterTheirInventoryAcknowledgment(){
        var s=AuthInputPacketsTest.session();var context=s.authInputPackets().context();
        var ench=NbtMap.builder().putList("ench",org.cloudburstmc.nbt.NbtType.COMPOUND,java.util.List.of(NbtMap.builder().putShort("id",(short)8).putShort("lvl",(short)3).build())).build();
        var boots=origin(301,0,ench);s.player().getLatencyUtil().queue(91,true);
        var slot=new InventorySlotPacket();slot.setContainerId(120);slot.setSlot(3);slot.setItem(boots);s.observeBackend(slot);
        assertEquals(0,ac.navro.proxy.compensated.CompensatedInventory.getEnchantmentLevel(context.nativeItem(s.inventory().armorContainer.get(3).getData()),8));
        assertTrue(s.player().getLatencyUtil().onResponse(91));
        assertEquals(3,ac.navro.proxy.compensated.CompensatedInventory.getEnchantmentLevel(context.nativeItem(s.inventory().armorContainer.get(3).getData()),8));
        assertTrue(context.canStandOnSnow());assertTrue(boots.getTag().containsKey("mv_origin_nbt"));
    }

    @Test void hotbarEquipUsesNativeIdentityAndReleasesOnlyTheUsingStateOnAChangedItem() {
        var s=AuthInputPacketsTest.session();var bow=item("minecraft:bow",0);held(s,bow);
        s.inventory().inventoryContainer.set(1,origin(261,0,NbtMap.EMPTY));s.inventory().inventoryContainer.set(2,item("minecraft:stone",0));
        var click=new InventoryTransactionPacket();click.setTransactionType(InventoryTransactionType.ITEM_USE);click.setActionType(1);click.setItemInHand(bow);click.setHotbarSlot(0);click.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);s.observeClient(click);
        var input=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));input.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_USING_ITEM);s.observeClient(input);
        var use=s.authInputPackets().itemUse();assertTrue(use.usingItemFlag());assertTrue(use.tracker().isUseButtonLatched());
        var equip=new MobEquipmentPacket();equip.setRuntimeEntityId(7);equip.setContainerId(0);equip.setHotbarSlot(1);s.observeClient(equip);
        assertEquals(1,s.inventory().heldItemSlot);assertTrue(use.usingItemFlag());assertEquals(261,use.tracker().getJavaItemId());
        equip.setRuntimeEntityId(8);equip.setHotbarSlot(2);s.observeClient(equip);assertEquals(1,s.inventory().heldItemSlot);assertTrue(use.usingItemFlag());
        equip.setRuntimeEntityId(7);equip.setHotbarSlot(9);s.observeClient(equip);assertEquals(1,s.inventory().heldItemSlot);assertTrue(use.usingItemFlag());
        equip.setHotbarSlot(2);s.observeClient(equip);assertEquals(2,s.inventory().heldItemSlot);assertFalse(use.usingItemFlag());assertTrue(use.tracker().isUseButtonLatched());
        assertEquals(.122499995F,use.tracker().getPredictionItemSlowdownFactor());
        s.observeClient(AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0)));assertEquals(1F,use.tracker().getPredictionItemSlowdownFactor());
    }
}
