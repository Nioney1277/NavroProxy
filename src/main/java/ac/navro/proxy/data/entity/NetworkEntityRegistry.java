package ac.navro.proxy.data.entity;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.HashMap;
import java.util.Map;

public final class NetworkEntityRegistry {
    private Map<String, Integer> types = Map.of();

    public void replace(NbtMap identifiers) {
        if (identifiers == null) return;
        var replacement = new HashMap<String, Integer>();
        for (var entry : identifiers.getList("idlist", NbtType.COMPOUND)) {
            Object identifier = entry.get("id");
            Object runtimeType = entry.get("rid");
            if (!(identifier instanceof String name) || name.isBlank() || !(runtimeType instanceof Integer type)) continue;
            Integer previous = replacement.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("Conflicting entity registry entry: " + name);
            }
        }
        types = Map.copyOf(replacement);
    }

    public int type(String identifier, int fallback) {
        return types.getOrDefault(identifier, fallback);
    }
}
