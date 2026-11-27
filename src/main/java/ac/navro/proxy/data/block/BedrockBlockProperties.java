package ac.navro.proxy.data.block;

import org.cloudburstmc.nbt.NbtMap;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;


public final class BedrockBlockProperties {
    public static final long PREVENT_JUMPING = 0x40000000L;
    public static final long CHECK_BELOW = 0x80L;
    private static final String BDS_SHA256 = "dc9b3c566a014cf53930eb1b54c6b599a99a81cc32c68aba06a5d73a0fd63179";

    private BedrockBlockProperties() { }

    public record Properties(long propertyMask, float friction, int materialType, int materialFlags, float hardness) {
        public boolean has(long property) { return (propertyMask & property) != 0; }
        public boolean blocksMotion() { return (materialFlags & 4) != 0; }

        public boolean solid() { return (materialFlags & 16) != 0; }
        public boolean liquid() { return (materialFlags & 2) != 0; }
    }

    private record Key(String name, NbtMap states) { }
    private static final class Protocol2225 {
        private static final Map<Key, Properties> TABLE = load();
    }

    static Properties forState(int protocol, NbtMap state) {
        if (protocol != 2225 || state == null) return null;
        return Protocol2225.TABLE.get(new Key(state.getString("name"), state.getCompound("states")));
    }

    private static Map<Key, Properties> load() {
        var resource = BedrockBlockProperties.class.getResourceAsStream("/navro/bedrock-properties-2225.bin.gz");
        if (resource == null) throw new IllegalStateException("Missing Bedrock physical properties for 2225");
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            if (in.readInt() != 0x4e504832 || in.readInt() != 2225
                    || !"1.26.63.1".equals(text(in)) || !BDS_SHA256.equals(text(in))) {
                throw new IOException("Incompatible Bedrock physical properties");
            }
            int count = in.readInt();
            if (count != 23960) throw new IOException("Incomplete Bedrock physical properties");
            var table = new HashMap<Key, Properties>(count * 2);
            for (int i = 0; i < count; i++) {
                String name = text(in);
                if (!name.startsWith("minecraft:")) throw new IOException("Non-vanilla physical property entry");
                int propertyCount = in.readUnsignedShort();
                if (propertyCount > 128) throw new IOException("Invalid state property count");
                var states = NbtMap.builder();
                for (int j = 0; j < propertyCount; j++) {
                    String key = text(in);
                    if (states.containsKey(key)) throw new IOException("Duplicate state property");
                    Object value = switch (in.readUnsignedByte()) {
                        case 1 -> in.readByte();
                        case 2 -> in.readShort();
                        case 3 -> in.readInt();
                        case 4 -> in.readLong();
                        case 5 -> in.readFloat();
                        case 6 -> in.readDouble();
                        case 8 -> text(in);
                        default -> throw new IOException("Unsupported state property type");
                    };
                    states.put(key, value);
                }
                long mask = in.readLong();
                float friction = in.readFloat();
                int material = in.readUnsignedByte(), flags = in.readUnsignedByte();
                float hardness = in.readFloat();
                if (!Float.isFinite(friction) || friction < 0 || friction > 1 || (flags & ~63) != 0
                        || !Float.isFinite(hardness) || hardness < -1)
                    throw new IOException("Invalid physical property value");
                var previous = table.put(new Key(name, states.build()), new Properties(mask, friction, material, flags, hardness));
                if (previous != null) throw new IOException("Duplicate physical state");
            }
            if (in.read() != -1) throw new IOException("Trailing Bedrock physical property data");
            return Map.copyOf(table);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load Bedrock physical properties", error);
        }
    }

    private static String text(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] data = in.readNBytes(length);
        if (data.length != length) throw new IOException("Truncated physical property string");
        return new String(data, StandardCharsets.UTF_8);
    }
}
