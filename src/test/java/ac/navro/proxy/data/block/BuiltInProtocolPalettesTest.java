package ac.navro.proxy.data.block;

import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.util.Hash;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class BuiltInProtocolPalettesTest {
    private static final Set<Integer> PROTOCOLS = Set.of(
            729, 776, 786, 800, 819, 844, 860, 898, 924, 944, 975, 1001, 2168, 2169, 2192, 2193, 2225);

    @Test void completePalettesCoverEveryAcceptedProtocolAndHaveVerifiedSourceDigests() throws Exception {
        var manifest = JsonParser.parseReader(new InputStreamReader(
                getClass().getResourceAsStream("/navro/palette/manifest.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        var observed = new HashSet<Integer>();
        for (var value : manifest) {
            var row = value.getAsJsonObject();
            int protocol = row.get("protocol").getAsInt();
            assertTrue(observed.add(protocol));
            assertEquals(protocol, NetworkBlockStateRegistry.paletteProtocol(protocol));
            if (protocol != 844) assertNotNull(SupportedCodecs.forProtocol(protocol));
            try (var in = getClass().getResourceAsStream("/navro/palette/" + row.get("file").getAsString())) {
                assertNotNull(in);
                assertEquals(row.get("sha256").getAsString(),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
            var runtime = NetworkBlockStateRegistry.forProtocol(protocol, false);
            assertEquals(row.get("states").getAsInt(), runtime.states().size(), "protocol=" + protocol);
            for (int id = 0; id < runtime.states().size(); id++)
                assertNotNull(runtime.resolve(id), "protocol=" + protocol + " missing ID " + id);
            assertEquals("minecraft:air", runtime.resolve(runtime.airNetworkId()).identifier());
        }
        assertEquals(PROTOCOLS, observed);
        assertNotEquals(NetworkBlockStateRegistry.forProtocol(2193, false).states().size(),
                NetworkBlockStateRegistry.forProtocol(2225, false).states().size());
    }

    @Test void everyRuntimeAndHashMatchesTheIndependentCanonicalVersionReference() throws Exception {
        try (var in = new DataInputStream(new GZIPInputStream(
                getClass().getResourceAsStream("/standard-block-palettes-reference.bin.gz")))) {
            assertEquals(0x50414C31, in.readInt());
            assertEquals(PROTOCOLS.size(), in.readInt());
            long compared = 0;
            for (int version = 0; version < PROTOCOLS.size(); version++) {
                int protocol = in.readInt(), count = in.readInt();
                var runtime = NetworkBlockStateRegistry.forProtocol(protocol, false);
                var hashes = NetworkBlockStateRegistry.forProtocol(protocol, true);
                assertEquals(count, runtime.states().size());
                for (int id = 0; id < count; id++) {
                    String name = in.readUTF();
                    int hash = in.readInt();
                    var state = runtime.resolve(id);
                    assertEquals(name, state.identifier(), "protocol=" + protocol + " ID=" + id);
                    assertEquals(hash, Hash.hashBlock(state.stateTag()), "protocol=" + protocol + " ID=" + id);
                    assertNotNull(hashes.resolve(hash), "protocol=" + protocol + " hash=" + hash);
                    compared++;
                }
            }
            assertTrue(compared > 290000);
            assertEquals(-1, in.read());
        }
    }

    @Test void undeclaredProtocolsCannotSelectAnOlderTable() {
        for (int protocol : new int[]{0, 728, 843, 861, 974, 2194, 2226, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> NetworkBlockStateRegistry.forProtocol(protocol, false));
    }

    @Test void standardDataDrivenDefinitionsUseTheExactBuiltInTableWithoutAnExternalExport() {
        for (int protocol : PROTOCOLS) {
            var start = new StartGamePacket();
            start.getBlockProperties().add(new BlockPropertyData("minecraft:stone",
                    NbtMap.builder().putCompound("components", NbtMap.EMPTY).build()));
            var registry = NetworkBlockStateRegistry.fromStartGame(protocol, start);
            assertEquals(NetworkBlockStateRegistry.forProtocol(protocol, false).states().size(), registry.states().size());
            var stone = registry.states().values().stream().filter(state -> state.is("minecraft:stone")).findFirst().orElseThrow();
            assertTrue(registry.hasComponents(stone));
            assertEquals("minecraft:air", registry.resolve(registry.airNetworkId()).identifier());
        }
    }
}
