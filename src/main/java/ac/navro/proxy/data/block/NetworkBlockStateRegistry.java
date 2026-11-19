package ac.navro.proxy.data.block;

import ac.navro.proxy.util.Hash;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class NetworkBlockStateRegistry {
    private static final Map<Integer, Tables> REGISTRIES = new ConcurrentHashMap<>();
    private final Tables tables;
    private final boolean hashed;

    private NetworkBlockStateRegistry(Tables tables, boolean hashed) { this.tables = tables; this.hashed = hashed; }


    public static int paletteProtocol(int protocol) {
        if (protocol >= 2192) return 2193;
        if (protocol >= 2168) return 2168;
        if (protocol >= 1001) return 1001;
        if (protocol >= 974) return 975;
        if (protocol >= 944) return 944;
        if (protocol >= 843) return 844;
        return -1;
    }

    public static NetworkBlockStateRegistry forProtocol(int protocol, boolean hashed) {
        int palette = paletteProtocol(protocol);
        return new NetworkBlockStateRegistry(REGISTRIES.computeIfAbsent(palette, NetworkBlockStateRegistry::load), hashed);
    }

    public static NetworkBlockStateRegistry fromStartGame(int protocol, StartGamePacket packet) {
        boolean hashed = packet.isBlockNetworkIdsHashed();
        NbtList<NbtMap> definitions = packet.getBlockPalette();
        if (definitions == null || definitions.isEmpty()) return forProtocol(protocol, hashed);

        Map<Integer, NetworkBlockState> runtime = new HashMap<>(), hashes = new HashMap<>();
        Map<Integer, Integer> legacyRuntime = new HashMap<>(), legacyHashes = new HashMap<>();
        var overloads = new java.util.ArrayList<ForwardState>();
        int airRuntime = Integer.MIN_VALUE, airHash = Integer.MIN_VALUE;
        int runtimeId = 0;
        for (NbtMap source : definitions) {
            NbtMap state = networkTag(source);
            int hash = Hash.hashBlock(state);
            int legacy = source.containsKey("id") ? legacyId(source) : -1;
            runtime.putIfAbsent(runtimeId, new NetworkBlockState(runtimeId, legacy, state));
            hashes.putIfAbsent(hash, new NetworkBlockState(hash, legacy, state));
            if (legacy >= 0) registerForward(source, legacy, runtimeId, hash, legacyRuntime, legacyHashes, overloads);
            if ("minecraft:air".equals(state.getString("name"))) { airRuntime = runtimeId; airHash = hash; }
            runtimeId++;
        }
        finishOverloads(overloads, legacyRuntime, legacyHashes);
        return new NetworkBlockStateRegistry(new Tables(Map.copyOf(runtime), Map.copyOf(hashes), airRuntime, airHash,
                Map.copyOf(legacyRuntime), Map.copyOf(legacyHashes)), hashed);
    }

    public NetworkBlockState resolve(int networkId) {
        NetworkBlockState state = (hashed ? tables.hashStates : tables.runtimeStates).get(networkId);
        if (state == null) state = (hashed ? tables.runtimeStates : tables.hashStates).get(networkId);
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
        if (protocol < 0) return new Tables(Map.of(), Map.of(), Integer.MIN_VALUE, Integer.MIN_VALUE, Map.of(), Map.of());
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
                    int legacy = legacyId(source);
                    int runtimeId = source.getInt("runtimeId");
                    NbtMap state = networkTag(source);
                    int hash = Hash.hashBlock(state);
                    runtime.putIfAbsent(runtimeId, new NetworkBlockState(runtimeId, legacy, state));
                    hashes.putIfAbsent(hash, new NetworkBlockState(hash, legacy, state));
                    registerForward(source, legacy, runtimeId, hash, legacyRuntime, legacyHashes, overloads);
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
