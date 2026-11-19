package ac.navro.proxy.compensated;

import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.compensated.cache.container.impl.ArmorContainerCache;
import ac.navro.proxy.compensated.cache.container.impl.PlayerContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.proxy.data.inventory.NativePredictionItems;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.inventory.InventoryType;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.PotionMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CompensatedInventory {
    private final WirePlayerState player;
    private boolean nativeItemDecoding;
    private Map<Integer, RecipeData> craftingData = new HashMap<>();
    private Map<Integer, ItemData> creativeData = new HashMap<>();
    private List<PotionMixData> potionMixData = new ArrayList<>();
    public int heldItemSlot;
    public final PlayerContainerCache inventoryContainer = new PlayerContainerCache(this);
    public final ContainerCache offhandContainer = new ContainerCache(this, ContainerCache.OFFHAND, InventoryType.PLAYER, null, -1L);
    public final ContainerCache armorContainer = new ArmorContainerCache(this);
    public final ContainerCache hudContainer = new ContainerCache(this, ContainerCache.UI, InventoryType.PLAYER, null, -1L);
    public ContainerCache openContainer;
    private final Map<Integer, ItemCache> bundleCache = new HashMap<>();

    public CompensatedInventory(WirePlayerState player) { this.player = player; }
    public WirePlayerState getPlayer() { return player; }

    public void setNativeItemDecoding(boolean enabled) { nativeItemDecoding = enabled; }
    public ItemData nativeItem(ItemData item) { return nativeItem(item, NativeItemDecoder.Format.INVENTORY); }
    public ItemData nativeItem(ItemData item, NativeItemDecoder.Format format) {
        return nativeItemDecoding ? NativeItemDecoder.decode(player.protocolVersion(), item, format) : item;
    }
    public boolean nativeIsNull(ItemData item) { return nativeIsNull(item, NativeItemDecoder.Format.INVENTORY); }
    public boolean nativeIsNull(ItemData item, NativeItemDecoder.Format format) {
        if (item == null || item.getCount() <= 0) return true;
        return nativeItemDecoding
                ? NativePredictionItems.resolve(player.protocolVersion(), nativeItem(item, format)).positiveNull()
                : item.isNull();
    }
    public boolean sameDefinition(ItemData predicted, ItemData claimed) {
        return sameDefinition(predicted, claimed, NativeItemDecoder.Format.INVENTORY);
    }
    public boolean sameDefinition(ItemData predicted, ItemData claimed, NativeItemDecoder.Format claimedFormat) {
        return ItemUtil.sameDefinition(nativeItem(predicted), nativeItem(claimed, claimedFormat));
    }
    public Map<Integer, RecipeData> getCraftingData() { return craftingData; }
    public void setCraftingData(Map<Integer, RecipeData> value) { craftingData = value; }
    public Map<Integer, ItemData> getCreativeData() { return creativeData; }
    public void setCreativeData(Map<Integer, ItemData> value) { creativeData = value; }
    public List<PotionMixData> getPotionMixData() { return potionMixData; }
    public void setPotionMixData(List<PotionMixData> value) { potionMixData = value; }
    public Map<Integer, ItemCache> getBundleCache() { return bundleCache; }

    public ContainerCache getContainer(byte id) {
        if (id == inventoryContainer.getId()) return inventoryContainer;
        if (id == offhandContainer.getId()) return offhandContainer;
        if (id == armorContainer.getId()) return armorContainer;
        if (id == hudContainer.getId()) return hudContainer;
        if (openContainer != null && id == openContainer.getId()) return openContainer;
        return null;
    }


    public static Map<Integer, Integer> getEnchantments(ItemData data) {
        if (data == null || data.getTag() == null || !data.getTag().containsKey("ench")) return Map.of();
        Map<Integer, Integer> result = new HashMap<>();
        for (NbtMap enchantment : data.getTag().getList("ench", NbtType.COMPOUND)) {
            if (!enchantment.containsKey("id") || !enchantment.containsKey("lvl")) continue;
            result.put((int) ac.navro.proxy.util.NbtNumber.getShort(enchantment, "id"),
                    (int) ac.navro.proxy.util.NbtNumber.getShort(enchantment, "lvl"));
        }
        return result;
    }

    public static int getEnchantmentLevel(ItemData data, int enchantmentId) {
        if (data == null || data.isNull()) return 0;

        if (data.getTag() != null) {
            for (NbtMap enchantment : data.getTag().getList("ench", NbtType.COMPOUND)) {
                if (ac.navro.proxy.util.NbtNumber.getShort(enchantment, "id") == (short) (enchantmentId & 0xffff)) {
                    int nativeLevel = ac.navro.proxy.util.NbtNumber.getShort(enchantment, "lvl");
                    if (nativeLevel > 0) return nativeLevel;
                    break;
                }
            }
        }
        return getEnchantments(data).getOrDefault(enchantmentId, 0);
    }
}
