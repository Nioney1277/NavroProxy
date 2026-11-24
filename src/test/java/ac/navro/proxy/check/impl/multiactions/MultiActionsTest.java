package ac.navro.proxy.check.impl.multiactions;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MultiActionsTest {
    @Test void sprintUsesTheAnticheatStateRatherThanTheClaimedSprintFlag() {
        var f = fixture();
        var input = input(10);
        f.session.observeClient(input);
        f.session.player().snapshotPredictionMovementState(true, false);
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("A"), f.flags());

        f.alerts.clear();
        input = input(11);
        input.getInputData().add(PlayerAuthInputData.SPRINTING);
        f.session.observeClient(input);
        f.session.player().snapshotPredictionMovementState(false, false);
        assertTrue(f.session.receiveClient(click(), 8500));
        assertTrue(f.flags().isEmpty());
    }

    @Test void directionalBitsFlagEvenWhenThePredictedInventoryInputIsZero() {
        for (var direction : List.of(PlayerAuthInputData.UP, PlayerAuthInputData.DOWN,
                PlayerAuthInputData.LEFT, PlayerAuthInputData.RIGHT, PlayerAuthInputData.UP_LEFT,
                PlayerAuthInputData.UP_RIGHT, PlayerAuthInputData.DOWN_LEFT, PlayerAuthInputData.DOWN_RIGHT)) {
            var f = fixture();
            var input = input(10);
            input.getInputData().add(direction);
            f.session.player().doingInventoryAction = true;
            f.session.observeClient(input);
            assertEquals(0, f.session.player().input().lengthSquared());
            assertFalse(f.session.receiveClient(click(), 8500));
            assertEquals(List.of("B"), f.flags(), direction.toString());
        }
    }

    @Test void moveVectorFlagsWithoutPositionMovementAndVelocityDoesNotCountAsInput() {
        var f = fixture();
        var input = input(10);
        input.setMotion(Vector2f.from(.25F, 0));
        f.session.observeClient(input);
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("B"), f.flags());

        f.alerts.clear();
        f.session.observeClient(input(11));
        f.session.player().setVelocity(new Vec3(1, 1, 1));
        assertTrue(f.session.receiveClient(click(), 8500));
        assertTrue(f.flags().isEmpty());
    }

    @Test void embeddedRequestsUseTheSameInputsProcessedSprintState() {
        var f = fixture();
        var start = input(1);
        start.setMotion(Vector2f.from(0, 1));
        start.setRawMoveVector(Vector2f.from(0, 1));
        start.getInputData().add(PlayerAuthInputData.START_SPRINTING);
        embed(start);
        assertTrue(f.session.receiveClient(start, 8500));
        assertNull(start.getItemStackRequest());
        assertFalse(start.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));
        assertEquals(List.of("A", "B"), f.flags(), () -> f.alerts + " " + f.session.anomalies());

        f.alerts.clear();
        var stop = input(2);
        stop.getInputData().add(PlayerAuthInputData.STOP_SPRINTING);
        embed(stop);
        f.session.receiveClient(stop, 8500);
        assertFalse(f.session.player().predictionSprinting());
        assertTrue(f.flags().isEmpty());
    }

    @Test void serverOpeningGraceEndsAfterItsAckAndTheNextInput() {
        var f = fixture();
        f.session.player().snapshotPredictionMovementState(true, false);
        f.session.player().getLatencyUtil().queue(90, true);
        var open = new ContainerOpenPacket();
        open.setId((byte) 4);
        open.setType(ContainerType.CONTAINER);
        open.setBlockPosition(Vector3i.ZERO);
        f.session.observeBackend(open);
        assertTrue(f.session.receiveClient(click(), 8500));
        assertTrue(f.flags().isEmpty());
        assertTrue(f.session.player().getLatencyUtil().onResponse(90));
        assertTrue(f.session.receiveClient(click(), 8500));
        assertTrue(f.flags().isEmpty());

        var first = input(1);
        first.getInputData().add(PlayerAuthInputData.UP);
        embed(first);
        assertTrue(f.session.receiveClient(first, 8500), () -> f.alerts + " " + f.session.anomalies());
        assertTrue(f.flags().isEmpty());
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("B"), f.flags());
    }

    @Test void closePacketsNeverFlagAndInvalidateAnOlderOpenAck() {
        var f = fixture();
        f.session.player().getLatencyUtil().queue(91, true);
        var open = new ContainerOpenPacket();
        open.setId((byte) 5);
        open.setType(ContainerType.CONTAINER);
        open.setBlockPosition(Vector3i.ZERO);
        f.session.observeBackend(open);
        f.session.player().snapshotPredictionMovementState(true, false);
        var close = new ContainerClosePacket();
        close.setId((byte) 5);
        assertTrue(f.session.receiveClient(close, 8500));
        assertTrue(f.flags().isEmpty());
        assertTrue(f.session.player().getLatencyUtil().onResponse(91));
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("A"), f.flags());
    }

    @Test void hotbarDropAndAutomaticTransfersOutsideTheUiAreNotInventoryClicks() {
        var f = fixture();
        f.session.player().snapshotPredictionMovementState(true, false);
        assertTrue(f.session.receiveClient(request(new DropAction(1, slot(ContainerSlotType.HOTBAR), false)), 8500));
        assertTrue(f.session.receiveClient(request(new SwapAction(slot(ContainerSlotType.HOTBAR), slot(ContainerSlotType.ARMOR))), 8500));
        assertTrue(f.session.receiveClient(request(new ItemStackRequestAction[0]), 8500));
        assertTrue(f.flags().isEmpty());

        var open = new InteractPacket();
        open.setRuntimeEntityId(7);
        open.setAction(InteractPacket.Action.OPEN_INVENTORY);
        assertTrue(f.session.receiveClient(open, 8500));
        assertTrue(f.flags().isEmpty());
        assertFalse(f.session.receiveClient(request(new DropAction(1, slot(ContainerSlotType.HOTBAR), false)), 8500));
        assertEquals(List.of("A"), f.flags());
    }

    @Test void legacyUiTransactionsAreCheckedButWorldDropsAndItemUseAreExcluded() {
        var f = fixture();
        f.session.player().snapshotPredictionMovementState(true, false);
        var transaction = new InventoryTransactionPacket();
        transaction.setTransactionType(InventoryTransactionType.NORMAL);
        transaction.getActions().add(new InventoryActionData(InventorySource.fromContainerWindowId(124), 0, ItemData.AIR, ItemData.AIR));
        assertFalse(f.session.receiveClient(transaction, 8500));
        assertEquals(List.of("A"), f.flags());

        f.alerts.clear();
        transaction.getActions().add(new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM), 0, ItemData.AIR, ItemData.AIR));
        f.session.receiveClient(transaction, 8500);
        transaction.setTransactionType(InventoryTransactionType.ITEM_RELEASE);
        f.session.receiveClient(transaction, 8500);
        assertTrue(f.flags().isEmpty());
    }

    @Test void checksCanBeDisabledIndependentlyAndRespectExemptionAndInspectionMode() {
        var f = fixture();
        var input = input(10);
        input.getInputData().add(PlayerAuthInputData.RIGHT);
        f.session.observeClient(input);
        f.session.player().snapshotPredictionMovementState(true, false);
        f.session.player().setDisabledChecks(Set.of("MultiActions-A"));
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("B"), f.flags());
        f.alerts.clear();
        f.session.player().setDisabledChecks(Set.of("MultiActions-B"));
        assertFalse(f.session.receiveClient(click(), 8500));
        assertEquals(List.of("A"), f.flags());

        f.alerts.clear();
        f.session.player().setDisabledChecks(Set.of());
        f.session.player().setExempted(true);
        assertTrue(f.session.receiveClient(click(), 8500));
        f.session.player().setExempted(false);
        f.session.setDetectionEnabled(false);
        assertTrue(f.session.receiveClient(click(), 8500));
        assertTrue(f.flags().isEmpty());
    }

    @Test void severalClicksInOnePacketProduceOneViolationPerCheck() {
        var f = fixture();
        var input = input(10);
        input.getInputData().add(PlayerAuthInputData.LEFT);
        f.session.observeClient(input);
        f.session.player().snapshotPredictionMovementState(true, false);
        var packet = click();
        packet.getRequests().addAll(click().getRequests());
        assertFalse(f.session.receiveClient(packet, 8500));
        assertEquals(List.of("A", "B"), f.flags());
    }

    @Test void rejectedRequestsSendErrorsAndCurrentBackendContentsWithoutChangingInventory() {
        var f=fixture();var corrections=new ArrayList<BedrockPacket>();
        f.session.setClientStateCorrectionTransport(corrections::add);
        var item=ItemData.builder().definition(new SimpleItemDefinition("minecraft:stone",1,false))
                .count(16).netId(7).usingNetId(true).tag(NbtMap.builder().putString("original","tag").build()).build();
        for(int id:new int[]{0,119,120,124}){
            var content=new InventoryContentPacket();content.setContainerId(id);content.setContents(List.of(item));
            f.session.observeBackend(content);
        }
        f.session.player().getLatencyUtil().onOrderedResponse();
        f.session.player().snapshotPredictionMovementState(true,false);
        var packet=click();packet.getRequests().add(new ItemStackRequest(-19,click().getRequests().get(0).getActions(),new String[0]));
        assertFalse(f.session.receiveClient(packet,8500));
        var response=assertInstanceOf(ItemStackResponsePacket.class,corrections.get(0));
        assertEquals(List.of(1,-19),response.getEntries().stream().map(e->e.getRequestId()).toList());
        assertTrue(response.getEntries().stream().allMatch(e->e.getResult()==ItemStackResponseStatus.ERROR));
        var contents=corrections.stream().filter(p->p instanceof InventoryContentPacket).map(p->(InventoryContentPacket)p).toList();
        assertEquals(List.of(0,119,120,124),contents.stream().map(InventoryContentPacket::getContainerId).toList());
        for(var content:contents)assertEquals(item,content.getContents().get(0));
        assertEquals(item,f.session.inventory().inventoryContainer.get(0).getData());
        assertFalse(f.session.player().doingInventoryAction);
        f.session.onClientPacketForwarded(input(1));
        assertFalse(f.session.backendInventories().pending(0,0));
    }

    @Test void rejectedClicksDoNotResendStalePendingContentsAndNormalClicksResumeImmediately() {
        var f=fixture();var corrections=new ArrayList<BedrockPacket>();f.session.setClientStateCorrectionTransport(corrections::add);
        var content=new InventoryContentPacket();content.setContainerId(0);content.setContents(List.of(ItemData.AIR));
        f.session.observeBackend(content);f.session.backendInventories().markAllPending();
        f.session.player().snapshotPredictionMovementState(true,false);
        assertFalse(f.session.receiveClient(click(),8500));
        assertEquals(1,corrections.size());assertInstanceOf(ItemStackResponsePacket.class,corrections.get(0));
        corrections.clear();f.session.observeClient(input(1));f.session.player().snapshotPredictionMovementState(false,false);
        assertTrue(f.session.receiveClient(click(),8500));assertTrue(corrections.isEmpty());
    }

    @Test void rejectedEmbeddedRequestIsRemovedWhileMovementAdvancesAndNoResponseReplayIsRegistered() {
        var f=fixture();var corrections=new ArrayList<BedrockPacket>();f.session.setClientStateCorrectionTransport(corrections::add);
        var first=input(1);first.getInputData().add(PlayerAuthInputData.UP);embed(first);
        assertTrue(f.session.receiveClient(first,8500));
        assertNull(first.getItemStackRequest());assertFalse(first.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));
        assertEquals(1,f.session.player().simulationFrame());assertNotNull(f.session.authInputPackets().lastResult());
        assertInstanceOf(ItemStackResponsePacket.class,corrections.get(0));
        f.session.onClientPacketForwarded(first);
        corrections.clear();var second=input(2);embed(second);
        assertTrue(f.session.receiveClient(second,8500));assertNotNull(second.getItemStackRequest());
        assertTrue(second.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST));assertTrue(corrections.isEmpty());
    }

    @Test void disabledChecksExemptionAndInspectionDoNotRejectOrSendCorrections() {
        var f=fixture();var corrections=new ArrayList<BedrockPacket>();f.session.setClientStateCorrectionTransport(corrections::add);
        var input=input(1);input.getInputData().add(PlayerAuthInputData.UP);f.session.observeClient(input);
        f.session.player().snapshotPredictionMovementState(true,false);
        f.session.player().setDisabledChecks(Set.of("MultiActions-A","MultiActions-B"));assertTrue(f.session.receiveClient(click(),8500));
        f.session.player().setDisabledChecks(Set.of());f.session.player().setExempted(true);assertTrue(f.session.receiveClient(click(),8500));
        f.session.player().setExempted(false);f.session.setDetectionEnabled(false);assertTrue(f.session.receiveClient(click(),8500));
        assertTrue(corrections.isEmpty());
    }

    @Test void nethernetDropsTheRejectedRequestAndEncodesTheErrorForTheClient() throws Exception {
        try(var nativeSession=new ac.navro.proxy.nethernet.NetherNetBatchSession(null,null,true)){
            nativeSession.process(false,java.util.HexFormat.of().parseHex("06c101000008b1"));
            nativeSession.process(true,java.util.HexFormat.of().parseHex("0c8f0101000000000000000000"));
            nativeSession.state().player().snapshotPredictionMovementState(true,false);
            var result=nativeSession.process(false,nativeSession.encode(click()));
            assertTrue(result.backendbound().isEmpty());assertNull(result.forwarded());
            var responses=new ArrayList<ItemStackResponsePacket>();
            for(byte[] batch:result.clientbound()){
                var data=io.netty.buffer.Unpooled.wrappedBuffer(batch);
                try{
                    assertEquals(255,data.readUnsignedByte());
                    int length=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(data);
                    var frame=data.readSlice(length);
                    int id=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(frame);
                    var codec=ac.navro.proxy.SupportedCodecs.forProtocol(2225);
                    var decoded=codec.tryDecode(codec.createHelper(),frame,id);
                    if(decoded instanceof ItemStackResponsePacket response)responses.add(response);
                    assertFalse(frame.isReadable());assertFalse(data.isReadable());
                }finally{data.release();}
            }
            assertEquals(1,responses.size());
            assertEquals(1,responses.get(0).getEntries().size());
            assertEquals(1,responses.get(0).getEntries().get(0).getRequestId());
            assertEquals(ItemStackResponseStatus.ERROR,responses.get(0).getEntries().get(0).getResult());
        }
    }

    private static PlayerAuthInputPacket input(long tick) {
        return AuthInputPacketsTest.input(tick, 8, 64, 8, new Vec3(0, -.0784F, 0));
    }

    private static ItemStackRequestSlotData slot(ContainerSlotType type) {
        return new ItemStackRequestSlotData(type, 0, 1, new FullContainerName(type, null));
    }

    private static ItemStackRequestPacket click() {
        return request(new TakeAction(1, slot(ContainerSlotType.INVENTORY), slot(ContainerSlotType.CURSOR)));
    }

    private static ItemStackRequestPacket request(ItemStackRequestAction... actions) {
        var packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(1, actions, new String[0]));
        return packet;
    }

    private static void embed(PlayerAuthInputPacket input) {
        input.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST);
        input.setItemStackRequest(click().getRequests().get(0));
    }

    private static Fixture fixture() {
        var session = AuthInputPacketsTest.session();
        session.player().commitInputTick(0);
        var alerts = new ArrayList<String>();
        session.player().setAlertSink(alerts::add);
        return new Fixture(session, alerts);
    }

    private record Fixture(SessionState session, List<String> alerts) {
        List<String> flags() {
            return alerts.stream().filter(a -> a.contains("MultiActions ("))
                    .map(a -> a.contains("MultiActions (A)") ? "A" : "B").toList();
        }
    }
}
