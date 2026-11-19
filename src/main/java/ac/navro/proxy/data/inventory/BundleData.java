package ac.navro.proxy.data.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import java.util.Arrays;

public final class BundleData {
    private final CompensatedInventory inventory;
    private final ItemCache[] contents = new ItemCache[64];
    private int bundleId = -1;
    public int count;

    public BundleData(CompensatedInventory inventory) {
        this.inventory = inventory;
        Arrays.fill(contents, ItemCache.AIR);
    }
    public ItemCache[] getContents() { return contents; }
    public int getBundleId() { return bundleId; }
    public void setBundleId(int value) { bundleId = value; }

    public boolean add(int slot, ItemCache cache) {
        ItemCache current = contents[slot];

        if (!inventory.nativeIsNull(current.getData()) && !inventory.sameDefinition(cache.getData(), current.getData())) return false;
        if (count + cache.count() > 64) return false;
        if (inventory.nativeIsNull(current.getData())) contents[slot] = cache.clone();
        else {
            current.count(current.count() + cache.count());
            contents[slot] = current;
        }
        count += cache.count();
        return true;
    }
}
