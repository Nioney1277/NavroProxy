package ac.navro.proxy.compensated;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.SessionState;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.util.*;

class InventoryResponseLifecycleTest {
    static SessionState session() {
        var s = new SessionState();
        s.setProtocolVersion(2193);
        s.player().setProtocolVersion(2193);
        s.setDetectionEnabled(true);
        return s;
    }

    static ItemData item(String name, int count, int id) {
        return ItemData.builder()
                .definition(new SimpleItemDefinition(name, 123, false))
                .count(count)
                .netId(id)
                .usingNetId(id > 0)
                .tag(NbtMap.builder().putString("server", "original").build())
                .build();
    }

    static void request(SessionState s, int id) {
        var p = new ItemStackRequestPacket();
        p.getRequests().add(new ItemStackRequest(id, new ItemStackRequestAction[0], new String[0]));
        assertTrue(s.receiveClient(p, 8500));
    }

    static ItemStackResponsePacket response(int id, ItemStackResponseStatus status, int stack) {
        var p = new ItemStackResponsePacket();
        p.getEntries()
                .add(
                        new ItemStackResponse(
                                status,
                                id,
                                List.of(
                                        new ItemStackResponseContainer(
                                                ContainerSlotType.INVENTORY,
                                                List.of(
                                                        new ItemStackResponseSlot(
                                                                0, 0, 2, stack, "", 0, "")),
                                                new FullContainerName(
                                                        ContainerSlotType.INVENTORY, null)))));
        return p;
    }

    static InventoryContentPacket full(ItemData item) {
        var p = new InventoryContentPacket();
        p.setContainerId(0);
        p.setContents(new ArrayList<>(Collections.nCopies(36, ItemData.AIR)));
        p.getContents().set(0, item);
        p.setContainerNameData(new FullContainerName(ContainerSlotType.INVENTORY, null));
        return p;
    }

    private static ItemStackRequestSlotData slot(ContainerSlotType type, int index, int stackId) {
        return new ItemStackRequestSlotData(type, index, stackId, new FullContainerName(type, null));
    }

    private static ItemStackRequestPacket moveRequest(SessionState s, int id, ItemStackRequestAction... actions) {
        var packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(id, actions, new String[0]));
        assertTrue(s.receiveClient(packet, 8500));
        s.onClientPacketForwarded(packet);
        return packet;
    }

    private static ItemStackResponseSlot result(int index, int count, int stackId) {
        return new ItemStackResponseSlot(index, index, count, stackId, "", 0, "");
    }

    private static ItemStackResponse ok(int request, ContainerSlotType type, ItemStackResponseSlot... slots) {
        return new ItemStackResponse(ItemStackResponseStatus.OK, request,
                List.of(new ItemStackResponseContainer(type, List.of(slots), new FullContainerName(type, null))));
    }

    @Test
    void movesKnownStackThroughCursorAcrossRequestsBeforeTheirResponses() {
        var s = session();
        var logs = item("minecraft:oak_log", 64, 104);
        s.observeBackend(full(logs));
        var cursor = new InventorySlotPacket();
        cursor.setContainerId(124);
        cursor.setSlot(0);
        cursor.setItem(ItemData.AIR);
        s.observeBackend(cursor);
        moveRequest(s, -187, new PlaceAction(64,
                slot(ContainerSlotType.INVENTORY, 0, 104), slot(ContainerSlotType.CURSOR, 0, 0)));
        moveRequest(s, -189, new PlaceAction(64,
                slot(ContainerSlotType.CURSOR, 0, -187), slot(ContainerSlotType.HOTBAR, 3, 0)));
        var responses = new ItemStackResponsePacket();
        responses.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, -187, List.of(
                ok(-187, ContainerSlotType.INVENTORY, result(0, 0, 0)).getContainers().get(0),
                ok(-187, ContainerSlotType.CURSOR, result(0, 64, 1170)).getContainers().get(0))));
        responses.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, -189, List.of(
                ok(-189, ContainerSlotType.CURSOR, result(0, 0, 0)).getContainers().get(0),
                ok(-189, ContainerSlotType.HOTBAR, result(3, 64, 1184)).getContainers().get(0))));
        s.player().getLatencyUtil().queue(190, true);
        s.observeBackend(responses);
        assertEquals(1184, s.backendInventories().slot(0, 3).getNetId());
        assertTrue(s.inventory().inventoryContainer.get(3).isEmpty());
        assertTrue(s.player().getLatencyUtil().onResponse(190));
        assertTrue(s.inventory().inventoryContainer.get(0).isEmpty());
        assertEquals("minecraft:oak_log", s.inventory().inventoryContainer.get(3).getData().getDefinition().getIdentifier());
        assertEquals(logs.getTag(), s.inventory().inventoryContainer.get(3).getData().getTag());
    }

    @Test
    void creativeOutputUsesServerCatalogueAndDefersToCompleteSlotInEitherOrder() {
        for (int completeOrder : new int[] {0, 1, 2}) {
            var s = session();
            var chicken = item("minecraft:cooked_chicken", 1, 0);
            s.player().acknowledgeGameType(ac.navro.proxy.data.vanilla.GameType.CREATIVE);
            s.observeBackend(full(ItemData.AIR));
            s.inventory().getCreativeData().put(52, chicken);
            moveRequest(s, -9, new CraftCreativeAction(52, 1),
                    new PlaceAction(64, slot(ContainerSlotType.CREATED_OUTPUT, 50, -9),
                            slot(ContainerSlotType.HOTBAR, 2, 0)));
            var complete = new InventorySlotPacket();
            complete.setContainerId(0);
            complete.setSlot(2);
            var serverItem = chicken.toBuilder().count(64).usingNetId(true).netId(4)
                    .tag(NbtMap.builder().putString("server", "complete").build()).build();
            complete.setItem(serverItem);
            var response = new ItemStackResponsePacket();
            response.getEntries().add(ok(-9, ContainerSlotType.HOTBAR_AND_INVENTORY, result(2, 64, 4)));
            if (completeOrder == 1) s.observeBackend(complete);
            s.observeBackend(response);
            if (completeOrder == 2) s.observeBackend(complete);
            var actual = s.inventory().inventoryContainer.get(2).getData();
            assertEquals(64, actual.getCount());
            assertEquals(4, actual.getNetId());
            assertEquals("minecraft:cooked_chicken", actual.getDefinition().getIdentifier());
            assertEquals(completeOrder == 0 ? chicken.getTag() : serverItem.getTag(), actual.getTag());
            assertTrue(s.inventoryResponseSync().pending().isEmpty());
        }
    }

    @Test
    void countOnlySuccessRetainsPendingIdentityAndCannotOverwriteFullNbtOrCommitClientClaims() {
        var s = session();
        s.observeBackend(full(item("minecraft:stone", 4, 10)));
        request(s, 11);
        assertTrue(s.inventoryResponseSync().requests().contains(11));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        var p = response(11, ItemStackResponseStatus.OK, 20);
        s.observeBackend(p);
        assertTrue(s.inventoryResponseSync().requests().isEmpty());
        assertEquals(1, s.inventoryResponseSync().pending().size());
        var pending = s.inventoryResponseSync().pending().iterator().next();
        assertEquals(11, pending.requestId());
        assertEquals(2, pending.count());
        assertEquals(20, pending.stackId());
        assertTrue(s.backendInventories().pending(0, 0));
        assertNull(s.backendInventories().slot(0, 0));
        assertNull(s.backendInventories().contents(0));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertEquals(
                "original",
                s.inventory().inventoryContainer.get(0).getData().getTag().getString("server"));
    }

    @Test
    void actualBackendIdentityReplacesPendingResponseOnlyAtItsOwnAcknowledgment() {
        var s = session();
        s.observeBackend(full(item("minecraft:stone", 4, 10)));
        request(s, 12);
        s.observeBackend(response(12, ItemStackResponseStatus.OK, 20));
        var slot = new InventorySlotPacket();
        slot.setContainerId(0);
        slot.setSlot(0);
        slot.setItem(item("minecraft:diamond", 2, 20));
        s.player().getLatencyUtil().queue(90, true);
        s.observeBackend(slot);
        assertTrue(s.inventoryResponseSync().pending().isEmpty());
        assertEquals(
                "minecraft:diamond",
                s.backendInventories().slot(0, 0).getDefinition().getIdentifier());
        assertEquals(
                "minecraft:stone",
                s.inventory().inventoryContainer.get(0).getData().getDefinition().getIdentifier());
        assertFalse(s.player().getLatencyUtil().onResponse(999));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertTrue(s.player().getLatencyUtil().onResponse(90));
        assertEquals(
                "minecraft:diamond",
                s.inventory().inventoryContainer.get(0).getData().getDefinition().getIdentifier());
        assertEquals(2, s.inventory().inventoryContainer.get(0).count());
    }

    @Test
    void responseAliasesClearOnTheNativeSlotPacketAndCreatedOutputFitsTheHud() {
        var state = session();
        var response = new ItemStackResponsePacket();
        response.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, 61, List.of(
                responseContainer(ContainerSlotType.OFFHAND, 1, 20),
                responseContainer(ContainerSlotType.CURSOR, 0, 21),
                responseContainer(ContainerSlotType.CREATED_OUTPUT, 50, 22))));
        state.observeBackend(response);
        assertTrue(state.backendInventories().pending(119, 0));
        assertFalse(state.backendInventories().pending(119, 1));
        assertTrue(state.backendInventories().pending(124, 50));

        state.player().getLatencyUtil().queue(95, true);
        state.observeBackend(authoritativeSlot(119, 0, 20));
        state.observeBackend(authoritativeSlot(124, 0, 21));
        state.observeBackend(authoritativeSlot(124, 50, 22));
        assertTrue(state.inventoryResponseSync().pending().isEmpty());
        assertTrue(state.inventory().hudContainer.get(50).isEmpty());
        assertTrue(state.player().getLatencyUtil().onResponse(95));
        assertEquals(20, state.inventory().offhandContainer.get(0).getData().getNetId());
        assertEquals(21, state.inventory().hudContainer.get(0).getData().getNetId());
        assertEquals(22, state.inventory().hudContainer.get(50).getData().getNetId());
    }

    @Test
    void reusedWindowDoesNotAcceptOldRequestEvidenceButStillTracksPlayerSlots() {
        var state = session();
        var open = new ContainerOpenPacket();
        open.setId((byte) 5);
        open.setType(ContainerType.CONTAINER);
        open.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);
        state.observeBackend(open);
        state.observeBackend(authoritativeSlot(5, 0, 20));
        request(state, 62);

        var close = new ContainerClosePacket();
        close.setId((byte) 5);
        state.observeBackend(close);
        assertNull(state.backendInventories().slot(5, 0));
        state.observeBackend(open);
        state.observeBackend(authoritativeSlot(5, 0, 21));

        var response = new ItemStackResponsePacket();
        response.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, 62, List.of(
                responseContainer(ContainerSlotType.LEVEL_ENTITY, 0, 20),
                responseContainer(ContainerSlotType.HOTBAR, 0, 23))));
        state.observeBackend(response);
        assertEquals(21, state.backendInventories().slot(5, 0).getNetId());
        assertTrue(state.backendInventories().pending(0, 0));
    }

    private static ItemStackResponseContainer responseContainer(ContainerSlotType type, int slot, int netId) {
        return new ItemStackResponseContainer(type,
                List.of(new ItemStackResponseSlot(slot, slot, 2, netId, "", 0, "")),
                new FullContainerName(type, null));
    }

    @Test
    void anEmptyWorkstationResponseClearsOnlyTheMatchingWindowAtAck() {
        var state = session();
        var open = new ContainerOpenPacket();
        open.setId((byte) 5);
        open.setType(ContainerType.ENCHANTMENT);
        open.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);
        state.observeBackend(open);
        state.observeBackend(authoritativeSlot(124, 15, 26));
        request(state, 63);
        var response = new ItemStackResponsePacket();
        var slot = new ItemStackResponseSlot(15, 15, 0, 0, "", 0, "");
        var type = ContainerSlotType.ENCHANTING_MATERIAL;
        response.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, 63,
                List.of(new ItemStackResponseContainer(type, List.of(slot), new FullContainerName(type, null)))));
        state.player().getLatencyUtil().queue(96, true);
        state.observeBackend(response);
        assertEquals(26, state.inventory().openContainer.get(15).getData().getNetId());
        assertTrue(state.player().getLatencyUtil().onResponse(96));
        assertTrue(state.inventory().openContainer.get(15).isEmpty());
        assertTrue(state.inventory().hudContainer.get(15).isEmpty());
    }

    private static InventorySlotPacket authoritativeSlot(int container, int slot, int netId) {
        var packet = new InventorySlotPacket();
        packet.setContainerId(container);
        packet.setSlot(slot);
        packet.setItem(item("minecraft:stone", 2, netId));
        return packet;
    }

    @Test
    void rejectionDoesNotCreateSuccessfulSlotUpdatesAndDimensionResetDropsRequestEvidence() {
        var s = session();
        request(s, 13);
        s.observeBackend(response(13, ItemStackResponseStatus.ERROR, 20));
        assertTrue(s.inventoryResponseSync().pending().isEmpty());
        assertTrue(s.inventoryResponseSync().requests().isEmpty());
        request(s, 14);
        s.observeBackend(response(14, ItemStackResponseStatus.OK, 21));
        assertFalse(s.inventoryResponseSync().pending().isEmpty());
        var dimension = new ChangeDimensionPacket();
        dimension.setDimension(1);
        dimension.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);
        s.observeBackend(dimension);
        assertTrue(s.inventoryResponseSync().pending().isEmpty());
        assertTrue(s.inventoryResponseSync().requests().isEmpty());
        assertNull(s.backendInventories().slot(0, 0));
    }

    @Test
    void fullSnapshotOwnsNbtAndContainerMetadataAndInvalidatedSlotsCannotBeUsedForCorrection() {
        var backend = new BackendInventorySnapshots();
        var p = full(item("minecraft:bow", 4, 10));
        backend.observe(p);
        p.getContents().clear();
        var packet = backend.fullPacket(0);
        assertEquals(36, packet.getContents().size());
        assertEquals(ContainerSlotType.INVENTORY, packet.getContainerNameData().getContainer());
        assertEquals("minecraft:bow", packet.getContents().get(0).getDefinition().getIdentifier());
        backend.markPending(0, 0);
        assertNull(backend.fullPacket(0));
        var slot = new InventorySlotPacket();
        slot.setContainerId(0);
        slot.setSlot(0);
        slot.setItem(item("minecraft:diamond", 3, 11));
        backend.observe(slot);
        assertEquals(
                "minecraft:diamond",
                backend.fullPacket(0).getContents().get(0).getDefinition().getIdentifier());
    }

    @Test
    void forwardedUseCannotResendOtherSlotsFromBeforeTheBackendMutation() {
        var s = session();
        var before = full(item("minecraft:stone", 4, 10));
        before.getContents().set(1, item("minecraft:bow", 1, 11));
        s.observeBackend(before);
        var use = new InventoryTransactionPacket();
        use.setTransactionType(
                org.cloudburstmc.protocol.bedrock.data.inventory.transaction
                        .InventoryTransactionType.ITEM_USE);
        use.setHotbarSlot(0);
        s.onClientPacketForwarded(use);
        var corrected = new ArrayList<BedrockPacket>();
        s.placementPipeline().setTransports(p -> true, corrected::add);
        s.placementPipeline().resyncSlot(0);
        var held = new InventorySlotPacket();
        held.setContainerId(0);
        held.setSlot(0);
        held.setItem(item("minecraft:stone", 3, 10));
        s.observeBackend(held);
        assertTrue(
                corrected.isEmpty(),
                "Refreshing only the held slot must not resend stale other slots");
        assertEquals(1, s.placementPipeline().pendingSlotCorrectionCount());
        var after = full(item("minecraft:stone", 3, 10));
        after.getContents().set(1, item("minecraft:diamond", 2, 22));
        s.observeBackend(after);
        assertEquals(1, corrected.size());
        var sent = (InventoryContentPacket) corrected.get(0);
        assertEquals(
                "minecraft:diamond", sent.getContents().get(1).getDefinition().getIdentifier());
        assertEquals(2, sent.getContents().get(1).getCount());
        assertEquals("original", sent.getContents().get(1).getTag().getString("server"));
        assertEquals(0, s.placementPipeline().pendingSlotCorrectionCount());
    }

    @Test
    void confirmedEmptyResponseNeedsNoInventedItemIdentityAndCommitsOnlyOnItsAcknowledgment() {
        var s = session();
        s.observeBackend(full(item("minecraft:bow", 1, 10)));
        s.observeBackend(response(20, ItemStackResponseStatus.OK, 10));
        assertEquals(1, s.inventoryResponseSync().pending().size());
        s.backendInventories().markPending(0, 0);
        request(s, 21);
        var response = new ItemStackResponsePacket();
        response.getEntries()
                .add(
                        new ItemStackResponse(
                                ItemStackResponseStatus.OK,
                                21,
                                List.of(
                                        new ItemStackResponseContainer(
                                                ContainerSlotType.HOTBAR,
                                                List.of(
                                                        new ItemStackResponseSlot(
                                                                0, 0, 0, 0, "", 0, "")),
                                                new FullContainerName(
                                                        ContainerSlotType.HOTBAR, null)))));
        s.player().getLatencyUtil().queue(91, true);
        s.observeBackend(response);
        assertTrue(s.inventoryResponseSync().pending().isEmpty());
        assertEquals(0, s.backendInventories().slot(0, 0).getCount());
        assertEquals(1, s.inventory().inventoryContainer.get(0).count());
        assertFalse(s.player().getLatencyUtil().onResponse(999));
        assertEquals(1, s.inventory().inventoryContainer.get(0).count());
        assertTrue(s.player().getLatencyUtil().onResponse(91));
        assertSame(
                ac.navro.proxy.data.inventory.ItemCache.AIR,
                s.inventory().inventoryContainer.get(0));
    }

    @Test
    void laterNonemptyResponseForSameSlotPreventsEarlierEmptyCommitInTheSameBatch() {
        var s = session();
        s.observeBackend(full(item("minecraft:bow", 1, 10)));
        request(s, 31);
        request(s, 32);
        var response = new ItemStackResponsePacket();
        response.getEntries()
                .add(
                        new ItemStackResponse(
                                ItemStackResponseStatus.OK,
                                31,
                                List.of(
                                        new ItemStackResponseContainer(
                                                ContainerSlotType.INVENTORY,
                                                List.of(
                                                        new ItemStackResponseSlot(
                                                                0, 0, 0, 0, "", 0, "")),
                                                new FullContainerName(
                                                        ContainerSlotType.INVENTORY, null)))));
        response.getEntries().addAll(response(32, ItemStackResponseStatus.OK, 22).getEntries());
        s.player().getLatencyUtil().queue(92, true);
        s.observeBackend(response);
        assertNull(s.backendInventories().slot(0, 0));
        assertEquals(1, s.inventoryResponseSync().pending().size());
        assertTrue(s.player().getLatencyUtil().onResponse(92));
        assertEquals(
                "minecraft:bow",
                s.inventory().inventoryContainer.get(0).getData().getDefinition().getIdentifier());
        assertEquals(1, s.inventory().inventoryContainer.get(0).count());
    }

    @Test
    void emptyAndUnknownIdentityUseTheSamePhysicalSlotAcrossContainerAliases() {
        for (boolean emptyLast : new boolean[] {false, true}) {
            var state = session();
            state.observeBackend(full(item("minecraft:bow", 1, 10)));
            request(state, 41);
            request(state, 42);
            var empty =
                    new ItemStackResponse(
                            ItemStackResponseStatus.OK,
                            41,
                            List.of(
                                    new ItemStackResponseContainer(
                                            ContainerSlotType.HOTBAR,
                                            List.of(
                                                    new ItemStackResponseSlot(
                                                            0, 0, 0, 0, "", 0, "")),
                                            new FullContainerName(
                                                    ContainerSlotType.HOTBAR, null))));
            var nonempty = response(42, ItemStackResponseStatus.OK, 22).getEntries().get(0);
            var packet = new ItemStackResponsePacket();
            packet.getEntries().add(emptyLast ? nonempty : empty);
            packet.getEntries().add(emptyLast ? empty : nonempty);
            state.player().getLatencyUtil().queue(93, true);
            state.observeBackend(packet);
            assertEquals(
                    "minecraft:bow",
                    state.inventory()
                            .inventoryContainer
                            .get(0)
                            .getData()
                            .getDefinition()
                            .getIdentifier(),
                    "Response receipt must not commit before ACK");
            assertTrue(state.player().getLatencyUtil().onResponse(93));
            if (emptyLast) {
                assertSame(
                        ac.navro.proxy.data.inventory.ItemCache.AIR,
                        state.inventory().inventoryContainer.get(0));
                assertTrue(state.inventoryResponseSync().pending().isEmpty());
                assertEquals(0, state.backendInventories().slot(0, 0).getCount());
            } else {
                assertEquals(
                        "minecraft:bow",
                        state.inventory()
                                .inventoryContainer
                                .get(0)
                                .getData()
                                .getDefinition()
                                .getIdentifier());
                assertEquals(1, state.inventoryResponseSync().pending().size());
                assertNull(state.backendInventories().slot(0, 0));
            }
        }
    }

    @Test
    void contradictoryEmptyFieldsCannotCommitAnInventedEmptySlot() {
        for (int[] fields : new int[][] {{0, 22}, {2, 0}}) {
            var state = session();
            state.observeBackend(full(item("minecraft:bow", 1, 10)));
            request(state, 51);
            var packet = new ItemStackResponsePacket();
            packet.getEntries()
                    .add(
                            new ItemStackResponse(
                                    ItemStackResponseStatus.OK,
                                    51,
                                    List.of(
                                            new ItemStackResponseContainer(
                                                    ContainerSlotType.INVENTORY,
                                                    List.of(
                                                            new ItemStackResponseSlot(
                                                                    0, 0, fields[0], fields[1], "",
                                                                    0, "")),
                                                    new FullContainerName(
                                                            ContainerSlotType.INVENTORY, null)))));
            state.player().getLatencyUtil().queue(94, true);
            state.observeBackend(packet);
            assertTrue(state.player().getLatencyUtil().onResponse(94));
            assertEquals(1, state.inventory().inventoryContainer.get(0).count());
            assertEquals(
                    "minecraft:bow",
                    state.inventory()
                            .inventoryContainer
                            .get(0)
                            .getData()
                            .getDefinition()
                            .getIdentifier());
            assertNull(state.backendInventories().slot(0, 0));
            assertEquals(1, state.inventoryResponseSync().pending().size());
        }
    }
}
