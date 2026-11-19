package ac.navro.proxy.compensated.cache.container.impl;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;
import java.util.Map;

public class ArmorContainerCache extends ContainerCache {
    public static final byte ARMOR_ID = 120;
    private static final int SWIFT_SNEAK = 37;

    public ArmorContainerCache(CompensatedInventory inventory) {
        super(inventory, ARMOR_ID, InventoryType.PLAYER, null, -1L);
    }

    @Override public void set(int slot, ItemCache cache, boolean offset) {
        if (slot == 2) {
            ItemCache old = get(2);
            Map<Integer, Integer> oldEnchantments = CompensatedInventory.getEnchantments(old.getData());
            Map<Integer, Integer> newEnchantments = CompensatedInventory.getEnchantments(cache.getData());
            if (!newEnchantments.containsKey(SWIFT_SNEAK)) inventory.getPlayer().sneakingAttributeModifier = 0;
            else if (!oldEnchantments.containsKey(SWIFT_SNEAK)) {
                int level = newEnchantments.get(SWIFT_SNEAK);
                inventory.getPlayer().sneakingAttributeModifier = 0.15F + 0.15F * (float) (level - 1);
            }
        }
        super.set(slot, cache, offset);
    }
}
