package ac.navro.proxy.data.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;


public class ItemCache {
    public static final ItemCache AIR = new ItemCache(ItemData.AIR, null);
    private final CompensatedInventory inventory;
    private ItemData data;
    private BundleData bundle;

    private ItemCache(ItemData data, CompensatedInventory inventory) { this.data = data; this.inventory = inventory; }
    public ItemData getData() { return data; }
    public ItemData getNativeData() { return inventory == null ? data : inventory.nativeItem(data); }
    public void setData(ItemData value) { data = value; }
    public BundleData getBundle() { return bundle; }
    public void setBundle(BundleData value) { bundle = value; }
    public ItemCache count(int count) { data = data.toBuilder().count(count).build(); return this; }
    public int count() { return data.getCount(); }
    public boolean isEmpty() { return inventory == null ? data == null || data.getCount() <= 0 || data.isNull() : inventory.nativeIsNull(data); }

    @Override public ItemCache clone() {
        ItemCache cache = new ItemCache(ItemUtil.copy(data), inventory);
        cache.setBundle(bundle);
        return cache;
    }

    public static ItemCache build(CompensatedInventory inventory, ItemData data) {
        ItemData item = ItemUtil.copy(data);
        ItemCache cache = new ItemCache(item, inventory);
        ItemData nativeItem = cache.getNativeData();
        if (!ItemUtil.isBundle(nativeItem)) return cache;
        int id = -1;
        try {
            if (nativeItem.getTag() != null) id = ac.navro.proxy.util.NbtNumber.getInt(nativeItem.getTag(), "bundle_id");
        } catch (Exception ignored) { }
        if (id == -1 || inventory.getBundleCache().containsKey(id)) return cache;
        BundleData bundle = new BundleData(inventory);
        bundle.setBundleId(id);
        cache.setBundle(bundle);
        inventory.getBundleCache().put(id, cache);
        return cache;
    }
}
