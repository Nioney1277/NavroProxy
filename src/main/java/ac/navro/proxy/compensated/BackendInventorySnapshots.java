package ac.navro.proxy.compensated;

import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.util.ItemUtil;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class BackendInventorySnapshots {
    private record SlotKey(int containerId, int slot) {}

    private final Map<Integer, Map<Integer, ItemData>> containers = new HashMap<>();
    private final Set<Integer> completeContainers = new HashSet<>();
    private final Set<SlotKey> invalidatedSlots = new HashSet<>();
    private final Map<Integer, InventoryContentPacket> templates = new HashMap<>();
    private long generation;

    public long generation() {
        return generation;
    }

    public void observe(BedrockPacket packet) {
        if (packet instanceof StartGamePacket || packet instanceof ChangeDimensionPacket) {
            resetForNewWorld();
        } else if (packet instanceof ContainerOpenPacket open) {
            forgetDynamicContainer(Byte.toUnsignedInt(open.getId()));
        } else if (packet instanceof ContainerClosePacket close && close.getId() != -1) {
            forgetDynamicContainer(Byte.toUnsignedInt(close.getId()));
        } else if (packet instanceof InventoryContentPacket content) {
            observeContent(content);
        } else if (packet instanceof InventorySlotPacket slot) {
            observeSlot(slot);
        }
    }

    private void resetForNewWorld() {
        generation++;
        containers.clear();
        completeContainers.clear();
        invalidatedSlots.clear();
        templates.clear();
    }

    private void forgetDynamicContainer(int containerId) {
        if (isPlayerContainer(containerId)) return;
        containers.remove(containerId);
        completeContainers.remove(containerId);
        templates.remove(containerId);
        invalidatedSlots.removeIf(key -> key.containerId() == containerId);
    }

    private static boolean isPlayerContainer(int containerId) {
        return containerId == 0 || containerId == 119 || containerId == 120 || containerId == 124;
    }

    void forgetWindowUi() {
        int containerId = ContainerCache.UI;
        var slots = containers.get(containerId);
        if (slots != null) slots.keySet().removeIf(slot -> slot != 0);
        invalidatedSlots.removeIf(key -> key.containerId() == containerId && key.slot() != 0);
        completeContainers.remove(containerId);
        templates.remove(containerId);
    }

    private void observeContent(InventoryContentPacket content) {
        if (content.getContents() == null) return;

        int containerId = content.getContainerId();
        Map<Integer, ItemData> snapshot = new HashMap<>();
        for (int slot = 0; slot < content.getContents().size(); slot++) {
            snapshot.put(slot, ItemUtil.copy(content.getContents().get(slot)));
        }

        containers.put(containerId, snapshot);
        completeContainers.add(containerId);
        invalidatedSlots.removeIf(key -> key.containerId() == containerId);

        InventoryContentPacket template = content.clone();
        template.setContents(new ArrayList<>(contents(containerId)));
        template.setStorageItem(ItemUtil.copy(content.getStorageItem()));
        templates.put(containerId, template);
    }

    private void observeSlot(InventorySlotPacket packet) {
        int containerId = packet.getContainerId();
        int slot = packet.getSlot();
        containers
                .computeIfAbsent(containerId, ignored -> new HashMap<>())
                .put(slot, ItemUtil.copy(packet.getItem()));
        invalidatedSlots.remove(new SlotKey(containerId, slot));
    }


    void confirmEmptySlot(int containerId, int slot) {
        containers.computeIfAbsent(containerId, ignored -> new HashMap<>()).put(slot, ItemData.AIR);
        invalidatedSlots.remove(new SlotKey(containerId, slot));
    }

    public ItemData slot(int containerId, int slot) {
        if (invalidatedSlots.contains(new SlotKey(containerId, slot))) return null;
        Map<Integer, ItemData> contents = containers.get(containerId);
        if (contents == null || !contents.containsKey(slot)) return null;
        return ItemUtil.copy(contents.get(slot));
    }

    public List<ItemData> contents(int containerId) {
        if (!completeContainers.contains(containerId) || hasInvalidatedSlots(containerId))
            return null;
        Map<Integer, ItemData> contents = containers.get(containerId);
        if (contents == null) return null;

        List<ItemData> ordered = new ArrayList<>(contents.size());
        for (int slot = 0; slot < contents.size(); slot++) {
            ItemData item = contents.get(slot);
            if (item == null) return null;
            ordered.add(ItemUtil.copy(item));
        }
        return List.copyOf(ordered);
    }

    private boolean hasInvalidatedSlots(int containerId) {
        return invalidatedSlots.stream().anyMatch(key -> key.containerId() == containerId);
    }

    public InventoryContentPacket fullPacket(int containerId) {
        List<ItemData> contents = contents(containerId);
        InventoryContentPacket template = templates.get(containerId);
        if (contents == null || template == null) return null;

        InventoryContentPacket packet = template.clone();
        packet.setContents(contents);
        packet.setStorageItem(ItemUtil.copy(template.getStorageItem()));
        return packet;
    }

    public void markPending(int containerId, int slot) {
        invalidatedSlots.add(new SlotKey(containerId, slot));
    }

    public boolean pending(int containerId, int slot) {
        return invalidatedSlots.contains(new SlotKey(containerId, slot));
    }


    public void markAllPending() {
        containers.forEach(
                (containerId, slots) ->
                        slots.keySet().forEach(slot -> markPending(containerId, slot)));
    }
}
