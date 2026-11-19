package ac.navro.proxy.compensated;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.Collection;
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

    private record RequestGeneration(long world, long window) {}

    private final SessionState state;
    private final Map<Integer, RequestGeneration> requestGenerations = new LinkedHashMap<>();
    private final Map<PendingKey, PendingSlot> pendingSlots = new LinkedHashMap<>();
    private long windowGeneration;
    private int openWindow = -1;
    private InventoryType openWindowType;

    public InventoryResponseSync(SessionState state) {
        this.state = state;
    }

    public void receive(ItemStackRequestPacket packet) {
        var generation = currentGeneration();
        packet.getRequests()
                .forEach(request -> requestGenerations.put(request.getRequestId(), generation));
    }

    public void receive(PlayerAuthInputPacket packet) {
        var request = packet.getItemStackRequest();
        if (request != null) {
            requestGenerations.put(request.getRequestId(), currentGeneration());
        }
    }

    private RequestGeneration currentGeneration() {
        return new RequestGeneration(state.backendInventories().generation(), windowGeneration);
    }

    public void backend(BedrockPacket packet) {
        if (packet instanceof StartGamePacket || packet instanceof ChangeDimensionPacket) {
            requestGenerations.clear();
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
        Map<ContainerSlot, Runnable> emptySlotCommits = new LinkedHashMap<>();

        for (var entry : response.getEntries()) {
            if (entry == null) continue;

            var requestGeneration = requestGenerations.remove(entry.getRequestId());
            if ((requestGeneration != null
                            && requestGeneration.world() != state.backendInventories().generation())
                    || entry.getResult() != ItemStackResponseStatus.OK
                    || entry.getContainers() == null) {
                continue;
            }

            for (var container : entry.getContainers()) {
                if (container == null || container.getItems() == null) continue;
                ContainerSlotType type = resolvedType(container);
                if (!isTrackable(type, container.getContainerName())) continue;
                if (isWindowScoped(type) && requestGeneration != null
                        && requestGeneration.window() != windowGeneration) continue;

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
                    if (isConfirmedEmpty(
                            slot.getCount(), slot.getStackNetworkId(), target, index)) {
                        acceptConfirmedEmpty(target, key, isWindowScoped(type), workstation, emptySlotCommits);
                    } else {
                        emptySlotCommits.remove(key);
                        recordUnknownIdentity(
                                requestGeneration != null,
                                entry.getRequestId(),
                                type,
                                key,
                                slot.getCount(),
                                slot.getStackNetworkId(),
                                slot.getCustomName(),
                                slot.getDurabilityCorrection(),
                                slot.getFilteredCustomName());
                    }
                }
            }
        }

        emptySlotCommits
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

    private void acceptConfirmedEmpty(
            ContainerCache target,
            ContainerSlot key,
            boolean windowScoped,
            boolean workstation,
            Map<ContainerSlot, Runnable> commits) {
        clearPending(key.containerId(), key.slot());
        state.backendInventories().confirmEmptySlot(key.containerId(), key.slot());

        long worldEpoch = state.world().getBlockBreakEpoch();
        long responseWindow = windowGeneration;
        boolean persistentContainer = isPersistentContainer(target);
        commits.put(
                key,
                () -> {
                    if (worldEpoch != state.world().getBlockBreakEpoch()) return;
                    if (windowScoped && responseWindow != windowGeneration) return;
                    if (!persistentContainer && state.inventory().openContainer != target) return;
                    if (workstation) UiInventorySlots.apply(state.inventory(), key.slot(), ItemData.AIR);
                    else target.set(key.slot(), ItemCache.AIR);
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
        return Set.copyOf(requestGenerations.keySet());
    }
}
