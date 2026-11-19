package ac.navro.proxy.data.block;

import ac.navro.proxy.util.Hash;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class NetworkBlockStateRegistryTest {
    @Test void everyRuntimeLegacyAndHashMatchesNativeNukkitRegistryData() throws Exception {
        int compared = 0;
        try (var input = new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/nukkit-block-registry.bin.gz")))) {
            assertEquals(0x4E425332, input.readInt());
            assertEquals(BlockStateEncoding.DATA_BITS, input.readInt());
            int versions = input.readInt(); assertEquals(9, versions);
            for (int i = 0; i < versions; i++) {
                int protocol = input.readInt(), palette = input.readInt(), count = input.readInt();
                assertEquals(palette, NetworkBlockStateRegistry.paletteProtocol(protocol));
                var runtime = NetworkBlockStateRegistry.forProtocol(protocol, false);
                var hashes = NetworkBlockStateRegistry.forProtocol(protocol, true);
                Map<Integer, Integer> firstHashLegacy = new HashMap<>();
                Map<Integer, Integer> firstRuntimeLegacy = new HashMap<>(), firstRuntimeHash = new HashMap<>();
                for (int s = 0; s < count; s++) {
                    int id = input.readInt(), legacy = input.readInt(), hash = input.readInt();
                    NetworkBlockState state = runtime.resolve(id);
                    assertNotNull(state, "protocol=" + protocol + " id=" + id);
                    assertEquals(id, state.networkId());
                    firstRuntimeLegacy.putIfAbsent(id, legacy);
                    firstRuntimeHash.putIfAbsent(id, hash);
                    assertEquals(firstRuntimeLegacy.get(id).intValue(), state.legacyFullId(), "protocol=" + protocol + " id=" + id);
                    assertEquals(firstRuntimeHash.get(id).intValue(), Hash.hashBlock(state.stateTag()), "protocol=" + protocol + " id=" + id);
                    firstHashLegacy.putIfAbsent(hash, legacy);
                    assertEquals(firstHashLegacy.get(hash).intValue(), hashes.resolve(hash).legacyFullId());
                    assertEquals(hash, hashes.resolve(hash).networkId());
                    compared++;
                }
                assertEquals("minecraft:air", runtime.resolve(runtime.airNetworkId()).identifier());
                assertEquals("minecraft:air", hashes.resolve(hashes.airNetworkId()).identifier());
                assertTrue(runtime.airNetworkId() > 0);
                assertEquals(Hash.hashBlock(runtime.resolve(runtime.airNetworkId()).stateTag()), hashes.airNetworkId());
            }
            assertEquals(-1, input.read());
        }
        assertEquals(178150, compared);
    }

    @Test void hashCanonicalizationIgnoresMetadataAndPropertyInsertionOrder() {
        var a = NbtMap.builder().putString("name", "minecraft:test").putInt("version", 123)
                .putCompound("states", NbtMap.builder().putInt("z", 7).putByte("a", (byte) 1).build()).build();
        var b = NbtMap.builder().putCompound("states", NbtMap.builder().putByte("a", (byte) 1).putInt("z", 7).build())
                .putInt("version", 999).putString("name", "minecraft:test").build();
        assertEquals(Hash.hashBlock(a), Hash.hashBlock(b));
        assertEquals(-2, Hash.hashBlock(NbtMap.builder().putString("name", "minecraft:unknown").build()));
    }

    @Test void explicitPaletteUsesPacketOrderAndConnectionHashModeWithoutLeakingBetweenSessions() {
        NbtMap stone = NbtMap.builder().putString("name", "minecraft:stone").putCompound("states", NbtMap.EMPTY).build();
        NbtMap air = NbtMap.builder().putString("name", "minecraft:air").putCompound("states", NbtMap.EMPTY).build();
        StartGamePacket packet = new StartGamePacket(); packet.setBlockPalette(new NbtList<>(NbtType.COMPOUND, stone, air));
        var runtime = NetworkBlockStateRegistry.fromStartGame(2193, packet);
        packet.setBlockNetworkIdsHashed(true);
        var hashed = NetworkBlockStateRegistry.fromStartGame(2193, packet);
        assertEquals(1, runtime.airNetworkId());
        assertEquals(Hash.hashBlock(air), hashed.airNetworkId());
        assertEquals("minecraft:stone", runtime.resolve(0).identifier());
        assertEquals("minecraft:stone", hashed.resolve(Hash.hashBlock(stone)).identifier());
        assertNull(runtime.resolve(123456789));
        assertNotEquals(1, NetworkBlockStateRegistry.forProtocol(2193, false).airNetworkId());
    }

    @Test void propertyConversionsAndStateOwnershipFollowNavro() {
        byte[] bytes = {1, 2};
        var tag = NbtMap.builder().putString("name", "minecraft:test").putByteArray("payload", bytes)
                .putCompound("states", NbtMap.builder().putByte("numeric", (byte) 2).putString("number", "123")
                        .putString("yes", "TrUe").putString("no", "FALSE").putString("bad", "bad").build()).build();
        var state = new NetworkBlockState(7, BlockStateEncoding.fullId(1, 1), tag);
        bytes[0] = 9;
        assertEquals(2, state.intProperty("numeric", -1));
        assertEquals(123, state.intProperty("number", -1));
        assertEquals(-1, state.intProperty("bad", -1));
        assertTrue(state.booleanProperty("numeric", false));
        assertTrue(state.booleanProperty("yes", false));
        assertFalse(state.booleanProperty("no", true));
        assertTrue(state.booleanProperty("bad", true));
        assertEquals("2", state.stringProperty("numeric", ""));
        assertEquals(1, state.blockId()); assertEquals(1, state.blockData());
        assertEquals(1, state.stateTag().getByteArray("payload")[0]);
        state.stateTag().getByteArray("payload")[0] = 8;
        assertEquals(1, state.stateTag().getByteArray("payload")[0]);
    }
}
