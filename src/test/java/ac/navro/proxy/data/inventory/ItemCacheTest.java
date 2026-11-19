package ac.navro.proxy.data.inventory;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ItemCacheTest {
    private static ItemData item(String identifier, int runtimeId, int count) {
        return ItemData.builder().definition(new SimpleItemDefinition(identifier, runtimeId, false)).count(count).build();
    }

    @Test void clonedItemDoesNotAliasMutableNbtNetIdOrBlockLists() {
        ItemData item = item("minecraft:stone", 1, 10).toBuilder().netId(7).usingNetId(true)
                .canPlace("minecraft:dirt")
                .tag(NbtMap.builder().putCompound("nested", NbtMap.builder().putByteArray("data", new byte[]{1,2}).build())
                        .putList("list", NbtType.COMPOUND, List.of(NbtMap.builder().putIntArray("ints", new int[]{3,4}).build())).build())
                .build();
        ItemCache cache = ItemCache.build(new CompensatedInventory(new WirePlayerState()), item);
        item.setNetId(8);
        item.getCanPlace()[0] = "minecraft:air";
        assertEquals(7, cache.getData().getNetId());
        assertEquals("minecraft:dirt", cache.getData().getCanPlace()[0]);
        assertNotSame(item.getTag().getCompound("nested"), cache.getData().getTag().getCompound("nested"));
        assertNotSame(item.getTag().getList("list", NbtType.COMPOUND).get(0),
                cache.getData().getTag().getList("list", NbtType.COMPOUND).get(0));
        ItemCache cloned = cache.clone();
        cache.count(2);
        assertEquals(10, cloned.count());
    }

    @Test void bundleCapacityAndDefinitionChecksFollowNavroWithoutMergingDifferentItems() {
        CompensatedInventory inventory = new CompensatedInventory(new WirePlayerState());
        ItemData bundleItem = item("minecraft:bundle", 5, 1).toBuilder()
                .tag(NbtMap.builder().putInt("bundle_id", 8).build()).build();
        ItemCache bundle = ItemCache.build(inventory, bundleItem);
        assertSame(bundle, inventory.getBundleCache().get(8));
        assertSame(bundle.getBundle(), bundle.clone().getBundle());
        assertTrue(bundle.getBundle().add(0, ItemCache.build(inventory, item("minecraft:stone", 1, 30))));
        assertFalse(bundle.getBundle().add(0, ItemCache.build(inventory, item("minecraft:dirt", 2, 1))));
        assertTrue(bundle.getBundle().add(0, ItemCache.build(inventory, item("minecraft:stone", 1, 34))));
        assertEquals(64, bundle.getBundle().count);
        assertFalse(bundle.getBundle().add(1, ItemCache.build(inventory, item("minecraft:stone", 1, 1))));
        assertEquals(64, bundle.getBundle().getContents()[0].count());
        assertNull(ItemCache.build(inventory, bundleItem).getBundle(), "existing bundle IDs follow Navro's cache ownership branch");
    }

    @Test void enchantmentsUseStableNumericIdsAndNamespaceComparisonMatchesNavroSanitization() {
        ItemData enchanted = item("minecraft:diamond_leggings", 3, 1).toBuilder()
                .tag(NbtMap.builder().putList("ench", NbtType.COMPOUND,
                        List.of(NbtMap.builder().putShort("id", (short) 37).putShort("lvl", (short) 3).build())).build())
                .build();
        assertEquals(3, CompensatedInventory.getEnchantments(enchanted).get(37));
        assertEquals(3, CompensatedInventory.getEnchantmentLevel(enchanted, 37));
        assertEquals(0, CompensatedInventory.getEnchantmentLevel(enchanted, 7));
        assertTrue(ItemUtil.sameDefinition(item("minecraft:stone", 1, 1), item("custom:stone", 1, 5)));
        assertFalse(ItemUtil.sameDefinition(item("minecraft:stone", 1, 1), item("custom:stone", 2, 1)));
        assertTrue(ItemUtil.isBundle(item("minecraft:red_bundle", 8, 1)));
        assertFalse(ItemUtil.isBundle(item("custom:red_bundle", 9, 1)));
    }

    static CompensatedInventory nativeInventory(int protocol) {
        var player=new WirePlayerState();player.setProtocolVersion(protocol);var inventory=new CompensatedInventory(player);inventory.setNativeItemDecoding(true);return inventory;
    }
    static ItemData origin(int id,int count,NbtMap inner) {
        return item("minecraft:info_update",123,count).toBuilder().tag(NbtMap.builder().putInt("mv_origin_id",id)
                .putInt("mv_origin_meta",0).putCompound("mv_origin_nbt",inner).putInt("bundle_id",77).build()).build();
    }
    @Test void decodedBundleOwnershipAndClonesPreserveInnerDataAndTheWireStack() {
        var inventory=nativeInventory(2193);var inner=NbtMap.builder().putInt("bundle_id",12).putByteArray("bytes",new byte[]{1,2}).build();
        var raw=origin(255,1,inner).toBuilder().tag(origin(255,1,inner).getTag().toBuilder().putString("mv_origin_namespace","minecraft:bundle").build()).build();
        var cache=ItemCache.build(inventory,raw);assertSame(cache,inventory.getBundleCache().get(12));assertFalse(inventory.getBundleCache().containsKey(77));
        var clone=cache.clone();cache.count(3);assertEquals(1,clone.count());assertEquals(1,clone.getNativeData().getCount());assertSame(cache.getBundle(),clone.getBundle());
        assertEquals("minecraft:info_update",cache.getData().getDefinition().getIdentifier());assertEquals("minecraft:bundle",cache.getNativeData().getDefinition().getIdentifier());
        var view=clone.getNativeData();view.getTag().getByteArray("bytes")[0]=99;
        assertArrayEquals(new byte[]{1,2},clone.getNativeData().getTag().getByteArray("bytes"));assertArrayEquals(new byte[]{1,2},inner.getByteArray("bytes"));
        assertNull(ItemCache.build(inventory,raw).getBundle());
    }
    @Test void bundleMergesUseDecodedIdentityAndRetainOriginalNativeAirCountBehavior() {
        var inventory=nativeInventory(2193);var bundle=new BundleData(inventory);
        var stone=ItemCache.build(inventory,origin(1,3,NbtMap.EMPTY));assertTrue(bundle.add(0,stone));assertNotSame(stone,bundle.getContents()[0]);
        assertFalse(bundle.add(0,ItemCache.build(inventory,origin(3,1,NbtMap.EMPTY))));
        assertTrue(bundle.add(0,ItemCache.build(inventory,item("minecraft:stone",999,2))));assertEquals(5,bundle.count);assertEquals(5,bundle.getContents()[0].count());
        var empty=ItemCache.build(inventory,origin(0,8,NbtMap.EMPTY));assertTrue(empty.isEmpty());assertTrue(bundle.add(1,empty));assertEquals(13,bundle.count);
        assertTrue(bundle.add(1,ItemCache.build(inventory,item("minecraft:dirt",998,2))));assertEquals(15,bundle.count);assertEquals(2,bundle.getContents()[1].count());
    }
}
