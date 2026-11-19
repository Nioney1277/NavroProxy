package ac.navro.proxy.compensated.cache.container.impl;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

public class PlayerContainerCache extends ContainerCache {
    public static final byte INVENTORY_ID = 0;

    public PlayerContainerCache(final CompensatedInventory inventory) {
        super(inventory, INVENTORY_ID, InventoryType.PLAYER, null, -1L);
    }

    public ItemCache getHeldItemCache() {
        return this.getItemFromSlot(this.inventory.heldItemSlot);
    }

    public ItemData getHeldItemData() {
        return this.getItemFromSlot(this.inventory.heldItemSlot).getData();
    }

    public ItemData getHeldItem() {
        return getHeldItemData();
    }

    public ItemCache getItemFromSlot(final int slot) {
        if (slot < 0 || slot > 8 || slot >= this.getContainerSize()) {
            return ItemCache.AIR;
        }

        return this.get(slot);
    }
}
