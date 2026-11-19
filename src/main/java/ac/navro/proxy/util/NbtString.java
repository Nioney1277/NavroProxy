package ac.navro.proxy.util;

import org.cloudburstmc.nbt.NbtMap;


public final class NbtString {
    private NbtString() { }
    public static String getString(NbtMap tag,String key) {
        if(!tag.containsKey(key))return "";
        Object value=tag.get(key);
        return value instanceof Number?String.valueOf(value):(String)value;
    }
}
