package ac.navro.proxy.data.block;

import ac.navro.proxy.util.Hash;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class NetworkBlockStateRegistry {
    private static final Map<Integer, Tables> REGISTRIES = new ConcurrentHashMap<>();
    private final Tables tables;
    private final boolean hashed;
    private final NetworkBlockComponents components;
    private ac.navro.anticheat.util.math.Box declaredBounds;
    private final Map<Integer, NetworkBlockState> declaredStates = new HashMap<>();

    private NetworkBlockStateRegistry(Tables tables, boolean hashed) {
        this(tables, hashed, new NetworkBlockComponents(java.util.List.of()));
    }

    private NetworkBlockStateRegistry(Tables tables, boolean hashed, NetworkBlockComponents components) {
        this.tables = tables;
        this.hashed = hashed;
        this.components = components;
    }


    public static int paletteProtocol(int protocol) {
        if (NetworkBlockStateRegistry.class.getResource("/navro/palette/runtime_block_states_" + protocol + ".dat") == null)
            throw new IllegalArgumentException("No built-in block palette for protocol " + protocol);
        return protocol;
    }

    public static NetworkBlockStateRegistry forProtocol(int protocol, boolean hashed) {
        int palette = paletteProtocol(protocol);
        return new NetworkBlockStateRegistry(REGISTRIES.computeIfAbsent(palette, NetworkBlockStateRegistry::load), hashed);
    }

    public static NetworkBlockStateRegistry fromStartGame(int protocol, StartGamePacket packet) {
        return fromStartGame(protocol, packet, null);
    }

    public static NetworkBlockStateRegistry fromStartGame(int protocol, StartGamePacket packet, RuntimePalette supplied) {
        boolean hashed = packet.isBlockNetworkIdsHashed();
        var components = new NetworkBlockComponents(packet.getBlockProperties(), protocol);
        NbtList<NbtMap> definitions = packet.getBlockPalette();
        Tables tables;
        if (definitions != null && !definitions.isEmpty()) tables = orderedTables(protocol, definitions, knownLegacy(protocol));
        else if (supplied != null && supplied.protocol() == protocol) tables = supplied.tables;
        else {
            tables = forProtocol(protocol, hashed).tables;
        }
        if (hashed && !packet.getBlockProperties().isEmpty()) {
            var hashes = new HashMap<>(tables.hashStates);
            for (var state : components.states()) {
                int hash = Hash.hashBlock(state);
                var previous = hashes.putIfAbsent(hash, new NetworkBlockState(hash, -1, state));
                if (previous != null && !StateKey.of(previous.stateTag()).equals(StateKey.of(state))) {
                    throw new IllegalArgumentException("Ambiguous custom block state hash: " + hash);
                }
            }
            tables = new Tables(tables.runtimeStates, Map.copyOf(hashes), tables.airRuntime, tables.airHash,
                    tables.legacyRuntime, tables.legacyHashes);
        }
        return new NetworkBlockStateRegistry(tables, hashed, components);
    }

    public boolean hasComponents(NetworkBlockState state) { return state != null && components.contains(state.identifier()); }
    public java.util.List<ac.navro.anticheat.util.math.Box> declaredCollision(NetworkBlockState state) { return components.collision(state); }


    public ac.navro.anticheat.util.math.Box declaredCollisionQuery(ac.navro.anticheat.util.math.Box query) {
        if (components.isEmpty()) return query;
        if (declaredBounds == null) {
            var bounds = new ac.navro.anticheat.util.math.Box(0, 0, 0, 1, 1, 1);
            for (var state : states().values()) {
                if (!hasComponents(state)) continue;
                var boxes = components.collision(state);
                if (boxes != null) for (var box : boxes) bounds = bounds.union(box);
            }
            declaredBounds = bounds;
        }
        return new ac.navro.anticheat.util.math.Box(
                query.minX - Math.max(0F, declaredBounds.maxX - 1F),
                query.minY - Math.max(0F, declaredBounds.maxY - 1F),
                query.minZ - Math.max(0F, declaredBounds.maxZ - 1F),
                query.maxX + Math.max(0F, -declaredBounds.minX),
                query.maxY + Math.max(0F, -declaredBounds.minY),
                query.maxZ + Math.max(0F, -declaredBounds.minZ));
    }

    private static Map<StateKey, Integer> knownLegacy(int protocol) {
        Map<StateKey, Integer> legacy = new HashMap<>();
        forProtocol(protocol, false).tables.runtimeStates.values().stream()
                .filter(state -> state.legacyFullId() >= 0)
                .sorted(java.util.Comparator.comparingInt(NetworkBlockState::networkId))
                .forEach(state -> legacy.putIfAbsent(StateKey.of(state.stateTag()), state.legacyFullId()));
        return legacy;
    }


    public static RuntimePalette readRuntimePalette(Path path) throws IOException {
        try (var input = new BufferedInputStream(Files.newInputStream(path))) {
            return readRuntimePalette(input);
        }
    }

    public static RuntimePalette readRuntimePalette(InputStream compressed) throws IOException {
        try (var input = NbtUtils.createGZIPReader(compressed, 24L * 1024 * 1024)) {
            if (!(input.readTag() instanceof NbtMap root))
                throw new IOException("Expected a palette compound");
            int protocol = root.getInt("protocol", -1);
            var states = root.getList("states", NbtType.COMPOUND);
            if (protocol <= 0 || states.isEmpty() || states.size() > 131072)
                throw new IOException("Invalid palette protocol or state count");
            if (states.stream().anyMatch(state -> state.getString("name").isBlank()))
                throw new IOException("Unnamed block state");
            Map<StateKey, Integer> legacy = knownLegacy(protocol);
            var tables = orderedTables(protocol, states, legacy);
            if (tables.airRuntime == Integer.MIN_VALUE)
                throw new IOException("Missing air entry in palette");
            return new RuntimePalette(protocol, tables);
        }
    }

    public static final class RuntimePalette {
        private final int protocol;
        private final Tables tables;

        private RuntimePalette(int protocol, Tables tables) {
            this.protocol = protocol;
            this.tables = tables;
        }

        public int protocol() { return protocol; }
        public int size() { return tables.runtimeStates.size(); }
    }

    private record StateKey(String name, NbtMap properties) {
        static StateKey of(NbtMap tag) {
            return new StateKey(tag.getString("name"), tag.getCompound("states"));
        }
    }

    private static Tables orderedTables(int protocol, Iterable<NbtMap> definitions, Map<StateKey, Integer> knownLegacy) {
        Map<Integer, NetworkBlockState> runtime = new HashMap<>(), hashes = new HashMap<>();
        Map<Integer, Integer> legacyRuntime = new HashMap<>(), legacyHashes = new HashMap<>();
        var overloads = new java.util.ArrayList<ForwardState>();
        int airRuntime = Integer.MIN_VALUE, airHash = Integer.MIN_VALUE;
        int runtimeId = 0;
        for (NbtMap source : definitions) {
            NbtMap state = networkTag(source);
            int hash = Hash.hashBlock(state);
            int legacy = source.containsKey("id") ? legacyId(source) : knownLegacy.getOrDefault(StateKey.of(state), -1);
            runtime.putIfAbsent(runtimeId, physicalState(protocol, runtimeId, legacy, state));
            hashes.putIfAbsent(hash, physicalState(protocol, hash, legacy, state));
            if (legacy >= 0) registerForward(source, legacy, runtimeId, hash, legacyRuntime, legacyHashes, overloads);
            if ("minecraft:air".equals(state.getString("name"))) { airRuntime = runtimeId; airHash = hash; }
            runtimeId++;
        }
        finishOverloads(overloads, legacyRuntime, legacyHashes);
        return new Tables(Map.copyOf(runtime), Map.copyOf(hashes), airRuntime, airHash,
                Map.copyOf(legacyRuntime), Map.copyOf(legacyHashes));
    }

    public NetworkBlockState resolve(int networkId) {
        NetworkBlockState state = (hashed ? tables.hashStates : tables.runtimeStates).get(networkId);
        if (state == null) state = (hashed ? tables.runtimeStates : tables.hashStates).get(networkId);
        if (hasComponents(state)) {
            var observed = state;
            return declaredStates.computeIfAbsent(state.networkId(), ignored -> new NetworkBlockState(observed.networkId(),
                    observed.legacyFullId(), observed.stateTag(), components.slipperiness(observed)));
        }
        return state;
    }
    public int airNetworkId() { return hashed ? tables.airHash : tables.airRuntime; }
    public boolean hashed() { return hashed; }
    public Map<Integer, NetworkBlockState> states() { return hashed ? tables.hashStates : tables.runtimeStates; }


    public int networkIdForLegacy(int legacyFullId) {
        Map<Integer, Integer> values = hashed ? tables.legacyHashes : tables.legacyRuntime;
        int blockId = legacyFullId >> BlockStateEncoding.DATA_BITS;
        int meta = legacyFullId & BlockStateEncoding.DATA_MASK;
        int networkId = values.getOrDefault(legacyFullId, -1);
        if (networkId == -1 && meta > 0x07)
            networkId = values.getOrDefault(blockId << BlockStateEncoding.DATA_BITS | meta & 0x0f, -1);
        if (networkId == -1 && meta > 0x03)
            networkId = values.getOrDefault(blockId << BlockStateEncoding.DATA_BITS | meta & 0x07, -1);
        if (networkId == -1) networkId = values.getOrDefault(blockId << BlockStateEncoding.DATA_BITS, -1);
        if (networkId == -1) networkId = values.getOrDefault(248 << BlockStateEncoding.DATA_BITS, -1);
        return networkId;
    }

    private static Tables load(int protocol) {
        Map<Integer, NetworkBlockState> runtime = new HashMap<>(), hashes = new HashMap<>();
        Map<Integer, Integer> legacyRuntime = new HashMap<>(), legacyHashes = new HashMap<>();
        var overloads = new java.util.ArrayList<ForwardState>();
        int airRuntime = Integer.MIN_VALUE, airHash = Integer.MIN_VALUE;
        String resource = "/navro/palette/runtime_block_states_" + protocol + ".dat";
        try (var raw = NetworkBlockStateRegistry.class.getResourceAsStream(resource)) {
            if (raw == null) throw new IllegalStateException("Missing protocol block states: " + resource);
            try (var reader = NbtUtils.createGZIPReader(new BufferedInputStream(raw))) {
                Object root = reader.readTag();
                if (!(root instanceof NbtList<?> states)) throw new IllegalStateException("Invalid protocol block states: " + resource);
                for (Object value : states) {
                    if (!(value instanceof NbtMap source)) continue;
                    int legacy = source.containsKey("id") ? legacyId(source) : -1;
                    int runtimeId = source.getInt("runtimeId");
                    NbtMap state = networkTag(source);
                    int hash = Hash.hashBlock(state);
                    runtime.putIfAbsent(runtimeId, physicalState(protocol, runtimeId, legacy, state));
                    hashes.putIfAbsent(hash, physicalState(protocol, hash, legacy, state));
                    if (legacy >= 0) registerForward(source, legacy, runtimeId, hash, legacyRuntime, legacyHashes, overloads);
                    if ("minecraft:air".equals(state.getString("name"))) { airRuntime = runtimeId; airHash = hash; }
                }
            }
        } catch (IOException error) { throw new IllegalStateException("Unable to load protocol block states: " + resource, error); }
        finishOverloads(overloads, legacyRuntime, legacyHashes);
        return new Tables(Map.copyOf(runtime), Map.copyOf(hashes), airRuntime, airHash,
                Map.copyOf(legacyRuntime), Map.copyOf(legacyHashes));
    }
    private static int legacyId(NbtMap source) {

        int id = ac.navro.proxy.util.NbtNumber.getInt(source, "id");
        int data = ac.navro.proxy.util.NbtNumber.getShort(source, "data");
        return BlockStateEncoding.fullId(id, data);
    }

    private static NetworkBlockState physicalState(int protocol, int networkId, int legacy, NbtMap state) {
        return new NetworkBlockState(networkId, legacy, state, null, BedrockBlockProperties.forState(protocol, state));
    }

    private record ForwardState(int legacy, int runtime, int hash) { }
    private static void registerForward(NbtMap source, int legacy, int runtime, int hash,
                                        Map<Integer, Integer> runtimes, Map<Integer, Integer> hashes,
                                        java.util.List<ForwardState> overloads) {
        var entry = new ForwardState(legacy, runtime, hash);
        if (ac.navro.proxy.util.NbtNumber.getInt(source, "stateOverload") != 0) overloads.add(entry);
        else registerForward(entry, runtimes, hashes);
    }
    private static void registerForward(ForwardState entry, Map<Integer, Integer> runtimes, Map<Integer, Integer> hashes) {

        runtimes.put(entry.legacy(), entry.runtime());
        hashes.putIfAbsent(entry.legacy(), entry.hash());
    }
    private static void finishOverloads(java.util.List<ForwardState> overloads,
                                        Map<Integer, Integer> runtimes, Map<Integer, Integer> hashes) {
        for (var entry : overloads) registerForward(entry, runtimes, hashes);
    }
    private static NbtMap networkTag(NbtMap source) {
        var tag = ItemUtil.copyTag(source).toBuilder();
        tag.remove("id"); tag.remove("data"); tag.remove("runtimeId"); tag.remove("stateOverload");
        return tag.build();
    }
    private record Tables(Map<Integer, NetworkBlockState> runtimeStates, Map<Integer, NetworkBlockState> hashStates,
                          int airRuntime, int airHash, Map<Integer, Integer> legacyRuntime,
                          Map<Integer, Integer> legacyHashes) { }
}
