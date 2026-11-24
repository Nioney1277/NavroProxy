package ac.navro.proxy.check.impl.elytra;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ElytraPipelineTest {
    private static ItemData elytra(){return ItemData.builder().count(1).definition(new ItemDefinition(){public String getIdentifier(){return "minecraft:elytra";}public int getRuntimeId(){return 444;}public boolean isComponentBased(){return false;}}).build();}
    @Test void invalidGlideClaimsBothZeroAndLargeOffsetsAndKeepsEmbeddedPayload() {
        for(float x:new float[]{8,80}){
            var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();var corrections=new ArrayList<BedrockPacket>();
            s.player().sinceAuthInput=System.currentTimeMillis()-500;
            s.player().setAlertSink(alerts::add);s.setClientStateCorrectionTransport(corrections::add);AuthInputPacketsTest.transport(s);
            var armor=new InventoryContentPacket();armor.setContainerId(120);armor.setContents(List.of(ItemData.AIR,ItemData.AIR,ItemData.AIR,ItemData.AIR));s.observeBackend(armor);
            var input=AuthInputPacketsTest.input(10,x,64,8,new Vec3(0,-.0784F,0));
            input.getInputData().add(PlayerAuthInputData.START_GLIDING);
            // Payload exists but PERFORM_ITEM_INTERACTION is absent: unrelated content remains intact.
            var payload=new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction();input.setItemUseTransaction(payload);
            assertFalse(s.receiveClient(input,8500));
            assertSame(payload,input.getItemUseTransaction());
            assertFalse(input.getInputData().contains(PlayerAuthInputData.START_GLIDING),
                    ()->"alerts="+alerts+", anomalies="+s.anomalies());
            assertTrue(input.getInputData().contains(PlayerAuthInputData.STOP_GLIDING));
            assertFalse(s.player().gliding());assertEquals(1,alerts.size(),alerts.toString());assertTrue(alerts.get(0).contains("Elytra (A)"));
            assertEquals(1,s.player().pendingCorrectionRequests());assertTrue(s.teleports().isTeleporting());
            assertEquals(2,corrections.size());assertFalse(((SetEntityDataPacket)corrections.get(0)).getMetadata().getFlag(EntityFlag.GLIDING));
            assertEquals(120,((InventoryContentPacket)corrections.get(1)).getContainerId());
        }
    }
    @Test void inventoryAckOwnsGlidingPermissionAndNoElytraInOtherSlotsCanAuthorizeIt() {
        var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.player().sinceAuthInput=System.currentTimeMillis()-500;
        s.inventory().inventoryContainer.set(0,elytra());s.inventory().armorContainer.set(0,elytra());
        var equipment=new InventorySlotPacket();equipment.setContainerId(120);equipment.setSlot(1);equipment.setItem(elytra());
        s.player().getLatencyUtil().queue(90,true);s.observeBackend(equipment);
        var invalid=AuthInputPacketsTest.input(10,8,64,8,new Vec3(0,-.0784F,0));invalid.getInputData().add(PlayerAuthInputData.START_GLIDING);
        s.receiveClient(invalid,8500);assertEquals(1,alerts.size());
        assertTrue(s.player().getLatencyUtil().onResponse(90));
        var valid=AuthInputPacketsTest.input(11,8,64,8,new Vec3(0,-.0784F,0));valid.getInputData().add(PlayerAuthInputData.START_GLIDING);
        s.receiveClient(valid,8500);assertEquals(1,alerts.size());assertTrue(valid.getInputData().contains(PlayerAuthInputData.START_GLIDING));
    }
}
