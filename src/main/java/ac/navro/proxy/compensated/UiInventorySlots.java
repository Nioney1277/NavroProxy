package ac.navro.proxy.compensated;

import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.inventory.InventoryType;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

public final class UiInventorySlots {
    private UiInventorySlots() { }

    public static InventoryType windowType(ContainerType type) {
        if (type == null) return null;
        return type.getId() == InventoryType.WORKBENCH.getNetworkType()
                ? InventoryType.WORKBENCH : InventoryType.from(type.getId());
    }

    public static InventoryType workstation(ContainerSlotType type) {
        return switch (type) {
            case ANVIL_INPUT, ANVIL_MATERIAL, ANVIL_RESULT -> InventoryType.ANVIL;
            case ENCHANTING_INPUT, ENCHANTING_MATERIAL -> InventoryType.ENCHANT_TABLE;
            case BEACON_PAYMENT -> InventoryType.BEACON;
            case LOOM_INPUT, LOOM_DYE, LOOM_MATERIAL, LOOM_RESULT -> InventoryType.LOOM;
            case CARTOGRAPHY_INPUT, CARTOGRAPHY_ADDITIONAL, CARTOGRAPHY_RESULT -> InventoryType.CARTOGRAPHY;
            case GRINDSTONE_INPUT, GRINDSTONE_ADDITIONAL, GRINDSTONE_RESULT -> InventoryType.GRINDSTONE;
            case STONECUTTER_INPUT, STONECUTTER_RESULT -> InventoryType.STONECUTTER;
            case SMITHING_TABLE_INPUT, SMITHING_TABLE_MATERIAL, SMITHING_TABLE_TEMPLATE,
                    SMITHING_TABLE_RESULT -> InventoryType.SMITHING_TABLE;
            case CRAFTING_INPUT, CRAFTING_OUTPUT -> InventoryType.WORKBENCH;
            default -> null;
        };
    }

    public static int responseSlot(ContainerSlotType type, InventoryType window, int slot) {
        InventoryType workstation = workstation(type);
        if (workstation == InventoryType.WORKBENCH) {
            int offset = window == InventoryType.WORKBENCH ? 32 : 28;
            int index = slot >= 32 && slot <= 40 ? slot - 32 : slot >= 28 && slot <= 31 ? slot - 28 : slot;
            return index >= 0 && index < (offset == 32 ? 9 : 4) ? offset + index : -1;
        }
        if (workstation == null || workstation != window) return -1;
        if (type == ContainerSlotType.BEACON_PAYMENT) return 27;
        if (slot == 50 && isWorkstationResult(type)) return 50;
        int offset = ContainerCache.offsetFor(workstation);
        int lastAlias = switch (workstation) {
            case ANVIL, CARTOGRAPHY, GRINDSTONE -> offset + 2;
            case STONECUTTER -> offset + 1;
            case LOOM, SMITHING_TABLE -> offset + 3;
            default -> offset + 1;
        };
        int index = slot >= offset && slot <= lastAlias ? slot - offset : slot;
        return index >= 0 && index < ContainerCache.sizeFor(workstation) ? offset + index : -1;
    }

    private static boolean isWorkstationResult(ContainerSlotType type) {
        return switch (type) {
            case ANVIL_RESULT, STONECUTTER_RESULT, LOOM_RESULT, CARTOGRAPHY_RESULT,
                    GRINDSTONE_RESULT, SMITHING_TABLE_RESULT -> true;
            default -> false;
        };
    }

    public static void apply(CompensatedInventory inventory, int slot, ItemData item) {
        inventory.hudContainer.set(slot, item);
        ContainerCache open = inventory.openContainer;
        if (open == null || open.getOffset() == 0 || open.getType() == InventoryType.TRADING) return;
        if (slot >= open.getOffset() && slot < open.getContainerSize()) open.set(slot, item);
    }
}
