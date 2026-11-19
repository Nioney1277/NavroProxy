package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.backend.BackendStateSync;
import ac.navro.proxy.protocol.BackendType;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.math.vector.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;


class PlacementClockCapabilityTest {
    static ScriptMessagePacket sample(String nonce,long sequence,int tick) {
        var packet=new ScriptMessagePacket();packet.setChannel(BackendStateSync.CHANNEL);
        packet.setMessage("tick|"+nonce+"|"+sequence+"|"+tick);return packet;
    }
    @Test void noClockSkipsBothCheckEntrypointsWithoutHoldingTheTransactionAndNamesDoNotEnableIt() {
        for(var type:BackendType.values()) {
            var state=AuthInputPacketsTest.session(type);
            state.inventory().inventoryContainer.set(0,PlacementPipelineLifecycleTest.stone(4));
            state.player().commitInputTick(10);state.player().sinceLoadingScreen=0;
            var trace=new ArrayList<Map<String,Object>>();var alerts=new ArrayList<String>();
            state.player().setAlertSink(alerts::add);
            state.player().setWorldDiagnostics(new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(state.player(),state.world(),"capability",trace::add));
            var packet=PlacementPipelineLifecycleTest.transaction();packet.setBlockPosition(Vector3i.from(8,64,10));
            state.receiveClient(packet,8500);
            assertEquals(0,state.placementPipeline().awaitingDataCount());
            assertTrue(trace.stream().noneMatch(r->r.get("event").equals("placement_confirmation_pending")));
            assertTrue(alerts.stream().noneMatch(s->s.contains("AirLiquidPlace")));
            var input=PlacementPipelineLifecycleTest.input(11,0);
            input.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
            var embedded=new ItemUseTransaction();embedded.setActionType(0);embedded.setBlockPosition(Vector3i.from(8,64,10));embedded.setBlockFace(2);embedded.setHotbarSlot(0);
            embedded.setItemInHand(PlacementPipelineLifecycleTest.stone(4));embedded.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);embedded.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);input.setItemUseTransaction(embedded);
            state.placementPipeline().begin(input);
            assertNotNull(input.getItemUseTransaction(),"Missing clock cannot remove a client's item-use transaction");
            assertFalse(state.world().hasNativeServerTick());
        }
    }
    @Test void negotiatedClockEnablesNativeRejectionForEitherBackendAndResetDisablesItAgain() {
        for(var type:BackendType.values()) {
            var state=AuthInputPacketsTest.session(type);
            state.inventory().inventoryContainer.set(0,PlacementPipelineLifecycleTest.stone(4));
            var alerts=new ArrayList<String>();state.player().setAlertSink(alerts::add);
            var hello=state.backendSync().begin();String nonce=hello.getMessage().substring(6);
            assertTrue(state.consumeBackendSync(sample(nonce,0,901)));
            assertTrue(state.world().hasNativeServerTick());assertEquals(901,state.backendSync().tick());
            var packet=PlacementPipelineLifecycleTest.transaction();packet.setBlockPosition(Vector3i.from(8,64,10));
            state.placementPipeline().handleTransaction(packet);
            assertTrue(alerts.stream().anyMatch(s->s.contains("AirLiquidPlace")&&s.contains("§7x§a1")),alerts.toString());
            assertEquals(0,state.placementPipeline().awaitingDataCount());
            assertEquals(4,state.inventory().inventoryContainer.get(0).count());
            state.backendSync().reset();assertFalse(state.world().hasNativeServerTick());
            alerts.clear();state.placementPipeline().handleTransaction(packet);
            assertTrue(alerts.stream().noneMatch(s->s.contains("AirLiquidPlace")));
        }
    }
    @Test void staleWrongConnectionAndClientSamplesNeverEstablishCapability() {
        var state=AuthInputPacketsTest.session();String old=state.backendSync().begin().getMessage().substring(6);
        String current=state.backendSync().begin().getMessage().substring(6);
        state.consumeBackendSync(sample(old,0,100));assertFalse(state.world().hasNativeServerTick());
        assertFalse(state.receiveClient(sample(current,0,100),8500));assertFalse(state.world().hasNativeServerTick());
        state.consumeBackendSync(sample(current,2,600));assertEquals(600,state.backendSync().tick());
        state.consumeBackendSync(sample(current,1,500));assertEquals(600,state.backendSync().tick());
        state.consumeBackendSync(sample(current,2,700));assertEquals(600,state.backendSync().tick());
    }
}
