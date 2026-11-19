package ac.navro.proxy.util;

import org.cloudburstmc.nbt.NbtMap;


public final class NbtNumber {
    private NbtNumber() { }

    public static int getInt(NbtMap tag, String key) {
        Object value = tag == null ? null : tag.get(key);
        return value == null ? 0 : ((Number) value).intValue();
    }
    public static int getShort(NbtMap tag, String key) {
        Object value = tag == null ? null : tag.get(key);

        return value == null ? 0 : ((Number) value).intValue();
    }
    public static int getByte(NbtMap tag, String key) {
        Object value = tag == null ? null : tag.get(key);
        return value == null ? 0 : ((Number) value).intValue();
    }
    public static float getFloat(NbtMap tag, String key) {
        Object value = tag == null ? null : tag.get(key);
        return value == null ? 0 : ((Number) value).floatValue();
    }
}
