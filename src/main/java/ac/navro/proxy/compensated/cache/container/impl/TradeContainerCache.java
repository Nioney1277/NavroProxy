package ac.navro.proxy.compensated.cache.container.impl;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.inventory.InventoryType;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;

public class TradeContainerCache extends ContainerCache {
    private final NbtMap offers;

    public TradeContainerCache(CompensatedInventory inventory, NbtMap offers, byte id,
                               InventoryType type, Vector3i blockPosition, long uniqueEntityId) {
        super(inventory, id, type, blockPosition, uniqueEntityId);
        this.offers = offers;
    }

    public NbtMap getOffers() { return offers; }
}
