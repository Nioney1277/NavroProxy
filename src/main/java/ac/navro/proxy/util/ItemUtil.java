package ac.navro.proxy.util;

import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public final class ItemUtil {
    private static final Map<String, Set<String>> LOCAL_ITEM_TAGS = Map.of(
            "minecraft:bundles", Set.of(
                    "minecraft:bundle",
                    "minecraft:white_bundle", "minecraft:orange_bundle", "minecraft:magenta_bundle",
                    "minecraft:light_blue_bundle", "minecraft:yellow_bundle", "minecraft:lime_bundle",
                    "minecraft:pink_bundle", "minecraft:gray_bundle", "minecraft:light_gray_bundle",
                    "minecraft:cyan_bundle", "minecraft:purple_bundle", "minecraft:blue_bundle",
                    "minecraft:brown_bundle", "minecraft:green_bundle", "minecraft:red_bundle",
                    "minecraft:black_bundle"
            ),
            "minecraft:candles", Set.of(
                    "minecraft:candle",
                    "minecraft:white_candle", "minecraft:orange_candle", "minecraft:magenta_candle",
                    "minecraft:light_blue_candle", "minecraft:yellow_candle", "minecraft:lime_candle",
                    "minecraft:pink_candle", "minecraft:gray_candle", "minecraft:light_gray_candle",
                    "minecraft:cyan_candle", "minecraft:purple_candle", "minecraft:blue_candle",
                    "minecraft:brown_candle", "minecraft:green_candle", "minecraft:red_candle",
                    "minecraft:black_candle"
            ),
            "minecraft:noteblock_top_instruments", Set.of(
                    "minecraft:skeleton_skull", "minecraft:wither_skeleton_skull",
                    "minecraft:zombie_head", "minecraft:player_head", "minecraft:creeper_head",
                    "minecraft:dragon_head", "minecraft:piglin_head"
            )
    );

    private static final Map<String, Set<String>> NATIVE_ITEM_TAGS = loadTags();
    private ItemUtil() { }

    private static Map<String, Set<String>> loadTags() {
        var stream = ItemUtil.class.getResourceAsStream("/navro/item_tags.json");
        if (stream == null) throw new AssertionError("Unable to load Navro item_tags.json");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, Set<String>> result = new HashMap<>();
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                Set<String> items = new HashSet<>();
                for (var item : entry.getValue().getAsJsonArray()) items.add(item.getAsString());
                result.put(entry.getKey(), Set.copyOf(items));
            }
            return Map.copyOf(result);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    public static String identifier(ItemData item) {
        return item == null || item.getDefinition() == null ? null : item.getDefinition().getIdentifier();
    }

    public static int runtimeId(ItemData item) {
        return item == null || item.getDefinition() == null ? Integer.MIN_VALUE : item.getDefinition().getRuntimeId();
    }

    public static boolean sameDefinition(ItemData predicted, ItemData claimed) {
        if (predicted == null) return true;
        if (claimed == null) return false;
        String predictedId = identifier(predicted), claimedId = identifier(claimed);
        if (predictedId == null || claimedId == null) return true;
        if (!StringUtil.sanitizePrefix(predictedId).equalsIgnoreCase(StringUtil.sanitizePrefix(claimedId))) return false;
        int predictedRuntimeId = runtimeId(predicted), claimedRuntimeId = runtimeId(claimed);
        if (predictedRuntimeId == Integer.MIN_VALUE || claimedRuntimeId == Integer.MIN_VALUE) return true;
        return predictedRuntimeId == claimedRuntimeId;
    }

    public static boolean hasTag(ItemData item, String tag) {
        String identifier = identifier(item);
        if (identifier == null) return false;
        String normalizedIdentifier = identifier.toLowerCase(Locale.ROOT);
        Set<String> items = NATIVE_ITEM_TAGS.get(tag);
        if (items != null && items.stream().anyMatch(value -> value.equalsIgnoreCase(normalizedIdentifier))) return true;
        return LOCAL_ITEM_TAGS.getOrDefault(tag.toLowerCase(Locale.ROOT), Set.of()).contains(normalizedIdentifier);
    }

    public static boolean isBundle(ItemData item) { return hasTag(item, "minecraft:bundles"); }

    public static ItemData copy(ItemData item) {
        if (item == null) return ItemData.AIR;
        return item.toBuilder().tag(copyTag(item.getTag()))
                .canPlace(item.getCanPlace() == null ? null : item.getCanPlace().clone())
                .canBreak(item.getCanBreak() == null ? null : item.getCanBreak().clone()).build();
    }

    public static org.cloudburstmc.nbt.NbtMap copyTag(org.cloudburstmc.nbt.NbtMap tag) {
        if (tag == null) return null;
        var copy = org.cloudburstmc.nbt.NbtMap.builder();
        for (String name : tag.keySet()) copy.put(name, copyNbt(tag.get(name)));
        return copy.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object copyNbt(Object value) {
        if (value instanceof org.cloudburstmc.nbt.NbtMap map) return copyTag(map);
        if (value instanceof org.cloudburstmc.nbt.NbtList<?> list) {
            var copy = new java.util.ArrayList<>();
            for (Object element : list) copy.add(copyNbt(element));
            return new org.cloudburstmc.nbt.NbtList(list.getType(), copy);
        }
        return org.cloudburstmc.nbt.NbtUtils.copy(value);
    }
}
