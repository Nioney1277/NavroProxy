package ac.navro.proxy.data.block;

import ac.navro.proxy.util.Hash;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ForwardPaletteTest {
    @Test void everyNativeForwardRegistrationAndFallbackMatchesBundledMot() throws Exception {
        int compared = 0;
        try (var in = new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/nukkit-forward-palette.bin.gz")))) {
            assertEquals(0x4E465031, in.readInt()); assertEquals(BlockStateEncoding.DATA_BITS, in.readInt());
            assertEquals(6, in.readInt());
            for (int v = 0; v < 6; v++) {
                int protocol = in.readInt(), count = in.readInt();
                var runtime = NetworkBlockStateRegistry.forProtocol(protocol, false);
                var hashed = NetworkBlockStateRegistry.forProtocol(protocol, true);
                for (int i = 0; i < count; i++) {
                    int legacy = in.readInt(); int expectedRuntime = in.readInt(), expectedHash = in.readInt();
                    assertEquals(expectedRuntime, runtime.networkIdForLegacy(legacy), "runtime protocol="+protocol+" legacy="+legacy);
                    assertEquals(expectedHash, hashed.networkIdForLegacy(legacy), "hash protocol="+protocol+" legacy="+legacy);
                    compared++;
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(compared > 190000);
    }
    private static NbtMap entry(int data, String variant, boolean overload) {
        return NbtMap.builder().putInt("id", 1).putShort("data", (short) data)
                .putByte("stateOverload", (byte) (overload ? 1 : 0)).putString("name", "minecraft:test")
                .putCompound("states", NbtMap.builder().putString("variant", variant).build()).build();
    }
    @Test void nativeForwardRuntimeLastAndHashFirstRulesIncludeDeferredOverloads() {
        var deferred = entry(1025, "deferred", true);
        var regular = entry(1025, "regular", false);
        var last = entry(1025, "last", false);
        var packet = new StartGamePacket(); packet.setBlockPalette(new NbtList<>(NbtType.COMPOUND, deferred, regular, last));
        var runtime = NetworkBlockStateRegistry.fromStartGame(2193, packet);
        packet.setBlockNetworkIdsHashed(true);
        var hashed = NetworkBlockStateRegistry.fromStartGame(2193, packet);
        int legacy = BlockStateEncoding.fullId(1, 1025);
        assertEquals(0, runtime.networkIdForLegacy(legacy));
        assertEquals(Hash.hashBlock(regular), hashed.networkIdForLegacy(legacy));
        assertEquals(1025, runtime.resolve(0).blockData()); assertEquals(1, runtime.resolve(0).blockId());
        assertEquals(legacy, runtime.resolve(0).legacyFullId());
    }
    @Test void highMetadataStaysDistinctFromSixBitCollisionSnapshotKeys() {
        var state = NetworkBlockState.legacy(BlockStateEncoding.fullId(139, 8191));
        assertEquals(139, state.blockId()); assertEquals(8191, state.blockData());
        for (int protocol : new int[]{2193, 2192, 2225}) {
            var registry = NetworkBlockStateRegistry.forProtocol(protocol, false);
            assertTrue(registry.states().values().stream().anyMatch(value -> value.blockData() > 63));
        }
    }
    @Test void explicitPaletteWithoutNativeIdsCannotInventLegacyMappingsFromRuntimeOrder() {
        var packet = new StartGamePacket();
        packet.setBlockPalette(new NbtList<>(NbtType.COMPOUND,
                NbtMap.builder().putString("name", "test:unmapped").putCompound("states", NbtMap.EMPTY).build()));
        var registry = NetworkBlockStateRegistry.fromStartGame(2193, packet);
        assertEquals(-1, registry.networkIdForLegacy(BlockStateEncoding.fullId(1, 0)));
        assertEquals(-1, registry.resolve(0).legacyFullId());
    }
}
