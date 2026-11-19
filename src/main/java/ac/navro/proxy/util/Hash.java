package ac.navro.proxy.util;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.TreeMap;


public final class Hash {
    private Hash() { }

    public static int hashBlock(NbtMap state) {
        String name = state.getString("name");
        if ("minecraft:unknown".equals(name)) return -2;
        NbtMap properties = state.getCompound("states");
        var sortedProperties = NbtMap.builder();
        sortedProperties.putAll(new TreeMap<>(properties));
        NbtMap canonical = NbtMap.builder().putString("name", name)
                .putCompound("states", sortedProperties.build()).build();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var writer = NbtUtils.createWriterLE(bytes)) { writer.writeTag(canonical); }
            return fnv1a32(bytes.toByteArray());
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public static int fnv1a32(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte value : bytes) { hash ^= value & 0xff; hash *= 0x01000193; }
        return hash;
    }
}
