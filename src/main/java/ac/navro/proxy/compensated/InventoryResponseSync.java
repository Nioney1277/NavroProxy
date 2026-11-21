package ac.navro.proxy.compensated;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.inventory.InventoryType;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class InventoryResponseSync {
    public record PendingSlot(
            int requestId,
            ContainerSlotType type,
            int slot,
            int count,
            int stackId,
            String customName,
            int durabilityCorrection,
            String filteredCustomName) {}

    private record PendingKey(ContainerSlotType container, ContainerSlot address) {}

    private record ContainerSlot(int containerId, int slot) {}

    private record Request(long world, long window, long wireRevision, long sequence,
                           ItemStackRequestAction[] actions, Map<ContainerSlot, ItemData> before,
                           Map<Integer, ItemData> creativeItems) {}

    private record SlotResult(int requestId, long sequence, long fullRevision) {}

    private final SessionState state;
    private final LinkedHashMap<Integer, Request> requests = new LinkedHashMap<>();
    private final Map<ContainerSlot, SlotResult> slotResults = new HashMap<>();
    private long requestSequence;
    private final Map<PendingKey, PendingSlot> pendingSlots = new LinkedHashMap<>();
    private long windowGeneration;
    private int openWindow = -1;
    private InventoryType openWindowType;

    public InventoryResponseSync(SessionState state) {
        this.state = state;
    }

    public void receive(ItemStackRequestPacket packet) {
        packet.getRequests().forEach(this::receive);
    }

    public void receive(PlayerAuthInputPacket packet) {
        if (packet.getItemStackRequest() != null) receive(packet.getItemStackRequest());
    }

    private void receive(ItemStackRequest request) {
        if (requests.containsKey(request.getRequestId())) return;
        if (requests.size() >= 256) requests.pollFirstEntry();
        var actions = request.getActions();
        if (actions == null || actions.length > 64) actions = new ItemStackRequestAction[0];
        var before = new HashMap<ContainerSlot, ItemData>();
        var creativeItems = new HashMap<Integer, ItemData>();
        for (var action : actions) {
            if (action instanceof TransferItemStackRequestAction transfer) {
                capture(before, transfer.getSource());
                capture(before, transfer.getDestination());
            } else if (action instanceof SwapAction swap) {
                capture(before, swap.getSource());
                capture(before, swap.getDestination());
            } else if (action instanceof DropAction drop) {
                capture(before, drop.getSource());
            } else if (action instanceof DestroyAction destroy) {
                capture(before, destroy.getSource());
            } else if (action instanceof CraftCreativeAction creative && state.player().gameType() == GameType.CREATIVE) {
                int id = creative.getCreativeItemNetworkId();
                ItemData item = state.inventory().getCreativeData().get(id);
                if (item != null) creativeItems.put(id, ItemUtil.copy(item));
            }
        }
        requests.put(request.getRequestId(), new Request(state.backendInventories().generation(),
                windowGeneration, state.backendInventories().wireRevision(), ++requestSequence,
                actions.clone(), before, creativeItems));
    }

    private void capture(Map<ContainerSlot, ItemData> before, ItemStackRequestSlotData ref) {
        ContainerSlot slot = requestSlot(ref);
        if (slot == null || isUnresolved(slot)) return;
        ItemData item = state.backendInventories().lastKnownSlot(slot.containerId(), slot.slot());
        if (item != null) before.put(slot, item);
    }

    private boolean isUnresolved(ContainerSlot slot) {
        return pendingSlots.keySet().stream().anyMatch(key -> key.address().equals(slot));
    }

    private ContainerSlot requestSlot(ItemStackRequestSlotData ref) {
        if (ref == null) return null;
        var name = ref.getContainerName();
        var type = name != null ? name.getContainer() : ref.getContainer();
        if (!isTrackable(type, name) || isWindowScoped(type)) return null;
        int container = containerId(type), slot = physicalSlot(type, ref.getSlot());
        var cache = state.inventory().getContainer((byte) container);
        if (cache == null || slot < cache.getOffset() || slot >= cache.getContainerSize()) return null;
        return new ContainerSlot(container, slot);
    }

    private static boolean createdOutput(ItemStackRequestSlotData ref) {
        if (ref == null) return false;
        var name = ref.getContainerName();
        return (name != null ? name.getContainer() : ref.getContainer()) == ContainerSlotType.CREATED_OUTPUT
                && (name == null || name.getDynamicId() == null)
                && ref.getSlot() == 50;
    }



    private Map<ContainerSlot, ItemData> replay(Request request) {
        if (request == null) return Map.of();
        var working = new HashMap<ContainerSlot, ItemData>();
        ItemData creativeOutput = null;
        for (var action : request.actions()) {
            if (action instanceof CraftCreativeAction creative) {
                creativeOutput = request.creativeItems().get(creative.getCreativeItemNetworkId());
                if (creativeOutput == null || creativeOutput.isNull()) return Map.of();
                continue;
            }
            if (action instanceof CraftResultsDeprecatedAction && creativeOutput != null) continue;
            if (action instanceof SwapAction swap) {
                var source = requestSlot(swap.getSource());
                var destination = requestSlot(swap.getDestination());
                if (source == null || destination == null || source.equals(destination)) return Map.of();
                var from = read(request, working, source, swap.getSource().getStackNetworkId());
                var to = read(request, working, destination, swap.getDestination().getStackNetworkId());
                if (from == null || to == null) return Map.of();
                working.put(source, to);
                working.put(destination, from);
                continue;
            }
            if (action instanceof TransferItemStackRequestAction transfer
                    && (action instanceof TakeAction || action instanceof PlaceAction)) {
                int count = transfer.getCount();
                boolean creative = createdOutput(transfer.getSource());
                var source = requestSlot(transfer.getSource());
                var destination = requestSlot(transfer.getDestination());
                if (count <= 0 || destination == null || destination.equals(source)) return Map.of();
                var from = creative ? creativeOutput : read(request, working, source,
                        transfer.getSource().getStackNetworkId());
                var to = read(request, working, destination, transfer.getDestination().getStackNetworkId());
                if (from == null || from.isNull() || to == null || (!creative && count > from.getCount())) return Map.of();
                if (!to.isNull() && !sameStackKind(from, to)) return Map.of();
                if (!creative) working.put(source, withCount(from, from.getCount() - count));
                working.put(destination, withCount(to.isNull() ? from : to, to.getCount() + count));
                continue;
            }
            ItemStackRequestSlotData sourceRef;
            int count;
            if (action instanceof DropAction drop) {
                sourceRef = drop.getSource();
                count = drop.getCount();
            } else if (action instanceof DestroyAction destroy) {
                sourceRef = destroy.getSource();
                count = destroy.getCount();
            } else {
                return Map.of();
            }
            var source = requestSlot(sourceRef);
            if (source == null) return Map.of();
            var item = read(request, working, source, sourceRef.getStackNetworkId());
            if (item == null || count <= 0 || count > item.getCount()) return Map.of();
            working.put(source, withCount(item, item.getCount() - count));
        }
        return working;
    }

    private ItemData read(Request request, Map<ContainerSlot, ItemData> working,
                          ContainerSlot slot, int stackId) {
        if (slot == null) return null;
        var result = slotResults.get(slot);
        if (result != null && result.sequence() > request.sequence()) return null;
        if (working.containsKey(slot)) {
            var item = working.get(slot);
            return stackId <= 0 || item.getNetId() == stackId ? item : null;
        }
        var backend = state.backendInventories();
        var known = backend.lastKnownSlot(slot.containerId(), slot.slot());
        if (result != null && result.fullRevision() == backend.fullRevision(slot.containerId(), slot.slot())
                && result.sequence() < request.sequence()) {
            if (!isUnresolved(slot)
                    && (stackId < 0 ? stackId == result.requestId() : matchesStack(known, stackId))) {
                return known;
            }
            return null;
        }
        var before = request.before().get(slot);
        if (matchesStack(before, stackId)) return before;


        if (stackId == 0 && before == null && known == null && slot.containerId() == ContainerCache.UI
                && slot.slot() == 0 && !isUnresolved(slot)) return ItemData.AIR;
        return null;
    }

    private static boolean matchesStack(ItemData item, int stackId) {
        return item != null && (item.isNull() ? stackId == 0 : stackId > 0 && item.getNetId() == stackId);
    }

    private static boolean sameStackKind(ItemData first, ItemData second) {
        return first.toBuilder().count(1).netId(0).usingNetId(false).build().equals(
                second.toBuilder().count(1).netId(0).usingNetId(false).build());
    }

    private static ItemData withCount(ItemData item, int count) {
        return count == 0 ? ItemData.AIR : item.toBuilder().count(count).build();
    }

    private static boolean matchesResponse(ItemData item, ItemStackResponseSlot slot) {
        if (item == null || item.getCount() != slot.getCount()) return false;
        return matchesStack(item, slot.getStackNetworkId());
    }

    private static boolean confirmsIdentity(ItemData item, ItemStackResponseSlot slot) {
        if (item == null || item.isNull() || slot.getStackNetworkId() <= 0 || item.getCount() != slot.getCount()) {
            return false;
        }
        var tag = item.getTag();
        String name = tag != null && tag.get("display") instanceof org.cloudburstmc.nbt.NbtMap display
                ? display.getString("Name", "") : "";
        if (slot.getCustomName() != null && !slot.getCustomName().isEmpty() && !slot.getCustomName().equals(name)) {
            return false;
        }
        return slot.getDurabilityCorrection() == 0 || slot.getDurabilityCorrection() == item.getDamage()
                || (tag != null && slot.getDurabilityCorrection() == tag.getInt("Damage", -1));
    }

    public void backend(BedrockPacket packet) {
        if (packet instanceof StartGamePacket || packet instanceof ChangeDimensionPacket) {
            requests.clear();
            slotResults.clear();
            pendingSlots.clear();
            openWindow = -1;
            openWindowType = null;
            return;
        }
        if (packet instanceof ContainerOpenPacket open) {
            setOpenWindow(Byte.toUnsignedInt(open.getId()), UiInventorySlots.windowType(open.getType()));
            return;
        }
        if (packet instanceof ContainerClosePacket close) {
            if (close.getId() == -1 || Byte.toUnsignedInt(close.getId()) == openWindow) {
                setOpenWindow(-1, null);
            }
            return;
        }
        if (packet instanceof InventoryContentPacket content) {
            if (content.getContents() != null) clearPending(content.getContainerId(), null);
            return;
        }
        if (packet instanceof InventorySlotPacket slot) {
            clearPending(slot.getContainerId(), slot.getSlot());
            return;
        }
        if (packet instanceof ItemStackResponsePacket response) {
            processResponse(response);
        }
    }

    private void setOpenWindow(int window, InventoryType type) {
        openWindow = window;
        openWindowType = type;
        windowGeneration++;
        pendingSlots.keySet().removeIf(key -> isWindowScoped(key.container()));
        state.backendInventories().forgetWindowUi();
    }

    private void processResponse(ItemStackResponsePacket response) {
        Map<ContainerSlot, Runnable> commits = new LinkedHashMap<>();
        Set<Integer> failed = new HashSet<>();
        for (var entry : response.getEntries()) {
            if (entry != null && entry.getResult() != ItemStackResponseStatus.OK) failed.add(entry.getRequestId());
        }

        for (var entry : response.getEntries()) {
            if (entry == null) continue;

            var request = requests.remove(entry.getRequestId());
            if ((request != null
                            && request.world() != state.backendInventories().generation())
                    || failed.contains(entry.getRequestId())
                    || entry.getContainers() == null) {
                continue;
            }

            var predicted = replay(request);
            for (var container : entry.getContainers()) {
                if (container == null || container.getItems() == null) continue;
                ContainerSlotType type = resolvedType(container);
                if (!isTrackable(type, container.getContainerName())) continue;
                if (isWindowScoped(type) && request != null
                        && request.window() != windowGeneration) continue;

                boolean workstation = UiInventorySlots.workstation(type) != null;
                int containerId = workstation ? ContainerCache.UI : containerId(type);
                var target =
                        containerId < 0 ? null : state.inventory().getContainer((byte) containerId);
                for (var slot : container.getItems()) {
                    if (slot == null) continue;
                    int index = workstation ? UiInventorySlots.responseSlot(type, openWindowType, slot.getSlot())
                            : physicalSlot(type, slot.getSlot());
                    if (index < 0) continue;
                    ContainerSlot key = new ContainerSlot(containerId, index);
                    var backend = state.backendInventories();
                    long revision = backend.fullRevision(containerId, index);
                    if (request != null && revision > request.wireRevision()) {

                        if (matchesResponse(backend.lastKnownSlot(containerId, index), slot)) {
                            clearPending(containerId, index);
                            slotResults.put(key, new SlotResult(entry.getRequestId(), request.sequence(), revision));
                        }
                        continue;
                    }
                    ItemData item = predicted.get(key);
                    if (isConfirmedEmpty(slot.getCount(), slot.getStackNetworkId(), target, index)) {
                        acceptConfirmedSlot(target, key, isWindowScoped(type), workstation, ItemData.AIR, commits);
                    } else if (target != null && confirmsIdentity(item, slot)) {
                        var confirmed = item.toBuilder().netId(slot.getStackNetworkId()).usingNetId(true).build();
                        acceptConfirmedSlot(target, key, isWindowScoped(type), workstation, confirmed, commits);
                    } else {
                        commits.remove(key);
                        recordUnknownIdentity(
                                request != null,
                                entry.getRequestId(),
                                type,
                                key,
                                slot.getCount(),
                                slot.getStackNetworkId(),
                                slot.getCustomName(),
                                slot.getDurabilityCorrection(),
                                slot.getFilteredCustomName());
                    }
                    if (request != null) {
                        slotResults.put(key, new SlotResult(entry.getRequestId(), request.sequence(), revision));
                    }
                }
            }
        }

        commits
                .values()
                .forEach(commit -> state.player().queueAcknowledgment(response, commit));
    }

    private static ContainerSlotType resolvedType(ItemStackResponseContainer container) {
        var name = container.getContainerName();
        return name == null ? container.getContainer() : name.getContainer();
    }

    private static boolean isTrackable(ContainerSlotType type, FullContainerName name) {
        return type != null
                && type != ContainerSlotType.DYNAMIC_CONTAINER
                && (name == null || name.getDynamicId() == null);
    }

    private boolean isConfirmedEmpty(int count, int stackId, ContainerCache target, int slot) {
        if (count != 0 || stackId != 0 || target == null) return false;
        return slot >= target.getOffset() && slot < target.getContainerSize();
    }

    private void acceptConfirmedSlot(
            ContainerCache target,
            ContainerSlot key,
            boolean windowScoped,
            boolean workstation,
            ItemData item,
            Map<ContainerSlot, Runnable> commits) {
        clearPending(key.containerId(), key.slot());
        state.backendInventories().confirmSlot(key.containerId(), key.slot(), item);

        long worldEpoch = state.world().getBlockBreakEpoch();
        long responseWindow = windowGeneration;
        boolean persistentContainer = isPersistentContainer(target);
        commits.put(
                key,
                () -> {
                    if (worldEpoch != state.world().getBlockBreakEpoch()) return;
                    if (windowScoped && responseWindow != windowGeneration) return;
                    if (!persistentContainer && state.inventory().openContainer != target) return;
                    if (workstation) UiInventorySlots.apply(state.inventory(), key.slot(), item);
                    else if (item.isNull()) target.set(key.slot(), ItemCache.AIR);
                    else target.set(key.slot(), item);
                });
    }

    private boolean isPersistentContainer(ContainerCache container) {
        var inventory = state.inventory();
        return container == inventory.inventoryContainer
                || container == inventory.armorContainer
                || container == inventory.offhandContainer
                || container == inventory.hudContainer;
    }

    private void recordUnknownIdentity(
            boolean knownRequest,
            int requestId,
            ContainerSlotType type,
            ContainerSlot address,
            int count,
            int stackId,
            String customName,
            int durabilityCorrection,
            String filteredCustomName) {
        pendingSlots.put(
                new PendingKey(type, address),
                new PendingSlot(
                        requestId,
                        type,
                        address.slot(),
                        count,
                        stackId,
                        customName,
                        durabilityCorrection,
                        filteredCustomName));

        if (address.containerId() >= 0) state.backendInventories().markPending(address.containerId(), address.slot());

        var trace = state.player().worldDiagnostics();
        if (trace != null) {
            trace.event(
                    "inventory_response_pending_identity",
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                            "requestId", requestId,
                            "knownRequest", knownRequest,
                            "container", type.name(),
                            "slot", address.slot(),
                            "count", count,
                            "stackId", stackId));
        }
    }

    private void clearPending(int containerId, Integer slot) {
        pendingSlots
                .keySet()
                .removeIf(
                        key ->
                                key.address().containerId() == containerId
                                        && (slot == null || key.address().slot() == slot));
    }

    private static boolean isWindowScoped(ContainerSlotType type) {
        return switch (type) {
            case HOTBAR, INVENTORY, HOTBAR_AND_INVENTORY, ARMOR, OFFHAND, CURSOR -> false;
            default -> true;
        };
    }

    private static int physicalSlot(ContainerSlotType type, int networkSlot) {
        return switch (type) {
            case CURSOR, OFFHAND -> 0;
            case CREATED_OUTPUT -> 50;
            default -> networkSlot;
        };
    }

    private int containerId(ContainerSlotType type) {
        if (type == null) return -1;
        return switch (type) {
            case HOTBAR, INVENTORY, HOTBAR_AND_INVENTORY -> 0;
            case ARMOR -> 120;
            case OFFHAND -> 119;
            case CURSOR, CREATED_OUTPUT -> 124;
            default -> openWindow;
        };
    }

    public Collection<PendingSlot> pending() {
        return List.copyOf(pendingSlots.values());
    }

    public Set<Integer> requests() {
        return Set.copyOf(requests.keySet());
    }
}
