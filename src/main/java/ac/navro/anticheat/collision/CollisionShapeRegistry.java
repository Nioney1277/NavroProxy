package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;





public final class CollisionShapeRegistry {
    private static final int MAGIC = 0x424F4152;
    private static final int FORMAT_VERSION = 2;
    private static final Map<Integer, CollisionShapeRegistry> REGISTRIES = new HashMap<>();

    private final Map<Integer, List<Box>> runtimeShapes;
    private final Map<StateKey, List<Box>> stateShapes;

    private CollisionShapeRegistry(int protocol) {
        Loaded loaded = load(protocol);
        this.runtimeShapes = loaded.runtimeShapes();
        this.stateShapes = loaded.stateShapes();
    }

    public static synchronized CollisionShapeRegistry forProtocol(int protocol) {
        return REGISTRIES.computeIfAbsent(protocol, CollisionShapeRegistry::new);
    }

    public List<Box> shape(int runtimeId) {
        return runtimeShapes.get(runtimeId);
    }

    public List<Box> shapeForState(NbtMap state) {
        return stateShapes.get(stateKey(state));
    }

    public boolean known(int runtimeId) {
        return runtimeShapes.containsKey(runtimeId);
    }

    public int mappedRuntimeIds() {
        return runtimeShapes.size();
    }

    private static Loaded load(int protocol) {
        Map<Integer, List<Box>> result = new HashMap<>();
        Map<StateKey, List<Box>> statesByShape = new HashMap<>();
        try (InputStream rawPalette = resource("navro/palette/runtime_block_states_" + NetworkBlockStateRegistry.paletteProtocol(protocol) + ".dat")) {
            if (rawPalette == null) return new Loaded(Map.of(), Map.of());
            Object root = NbtUtils.createGZIPReader(new BufferedInputStream(rawPalette)).readTag();
            if (!(root instanceof NbtList<?> states)) return new Loaded(Map.of(), Map.of());

            Map<Integer, List<Box>> shapeByLegacy = readCollisionMappings();
            for (Object value : states) {
                if (!(value instanceof NbtMap state)) continue;
                int runtimeId = state.getInt("runtimeId", Integer.MIN_VALUE);
                int blockId = state.getInt("id", -1);
                int blockData = (state.get("data") instanceof Number data ? data.shortValue() : 0) & 0x3F;
                if (runtimeId == Integer.MIN_VALUE || blockId < 0) continue;
                List<Box> shape = shapeByLegacy.get((blockId << 6) | blockData);
                if (shape != null) {
                    result.put(runtimeId, shape);
                    statesByShape.putIfAbsent(stateKey(state), shape);
                }
            }
        } catch (IOException | RuntimeException error) {


            return new Loaded(Map.of(), Map.of());
        }
        return new Loaded(Map.copyOf(result), Map.copyOf(statesByShape));
    }

    private static Map<Integer, List<Box>> readCollisionMappings() throws IOException {
        try (InputStream resource = resource("navro/geyser-collisions.bin")) {
            if (resource == null) throw new IOException("Missing navro/geyser-collisions.bin");
            DataInputStream input = new DataInputStream(new BufferedInputStream(resource));
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("Invalid Navro collision table");
            }
            int shapeCount = input.readUnsignedShort();
            List<List<Box>> shapes = new ArrayList<>(shapeCount);
            for (int shapeIndex = 0; shapeIndex < shapeCount; shapeIndex++) {
                int boxCount = input.readUnsignedShort();
                List<Box> boxes = new ArrayList<>(boxCount);
                for (int boxIndex = 0; boxIndex < boxCount; boxIndex++) {
                    boxes.add(new Box(input.readFloat(), input.readFloat(), input.readFloat(),
                            input.readFloat(), input.readFloat(), input.readFloat()));
                }
                shapes.add(List.copyOf(boxes));
            }
            Map<Integer, List<Box>> mapped = new HashMap<>();
            readMappings(input, mapped, shapes);
            return mapped;
        }
    }

    private static void readMappings(DataInputStream input, Map<Integer, List<Box>> result,
                                     List<List<Box>> shapes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 2_000_000) throw new IOException("Invalid collision mapping count");
        for (int index = 0; index < count; index++) {
            int key = input.readInt();
            int shapeIndex = input.readUnsignedShort();
            if (shapeIndex >= shapes.size()) throw new IOException("Invalid collision shape index");
            result.put(key, shapes.get(shapeIndex));
        }


        int dynamicCount = input.readInt();
        if (dynamicCount < 0 || dynamicCount > 2_000_000) throw new IOException("Invalid dynamic mapping count");
        for (int index = 0; index < dynamicCount; index++) {
            input.readInt();
            int shapeIndex = input.readUnsignedShort();
            if (shapeIndex >= shapes.size()) throw new IOException("Invalid dynamic shape index");
        }
    }

    private static InputStream resource(String name) {
        return CollisionShapeRegistry.class.getClassLoader().getResourceAsStream(name);
    }

    private static StateKey stateKey(NbtMap state) {
        return state == null ? null : new StateKey(state.getString("name", ""), state.getCompound("states"));
    }

    private record StateKey(String identifier, NbtMap properties) { }

    private record Loaded(Map<Integer, List<Box>> runtimeShapes,
                          Map<StateKey, List<Box>> stateShapes) {}
}
