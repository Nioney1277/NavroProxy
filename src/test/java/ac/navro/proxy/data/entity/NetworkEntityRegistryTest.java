package ac.navro.proxy.data.entity;

import ac.navro.proxy.SupportedCodecs;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.packet.AvailableEntityIdentifiersPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkEntityRegistryTest {
    private static NbtMap entry(String name, int type) {
        return NbtMap.builder().putString("id", name).putInt("rid", type).build();
    }

    private static NbtMap registry(NbtMap... entries) {
        return NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(entries)).build();
    }

    @Test void actualEntityRegistryPacketPreservesNamesAndTypesAcrossSupportedCodecs() {
        for (int protocol : new int[]{729, 776, 786, 800, 819, 860, 898, 924, 944, 975, 1001, 2168, 2169, 2193, 2225}) {
            var codec = SupportedCodecs.forProtocol(protocol);
            var packet = new AvailableEntityIdentifiersPacket();
            packet.setIdentifiers(registry(entry("minecraft:minecart", 84), entry("custom:actor", 65536)));
            var buffer = Unpooled.buffer();
            try {
                codec.tryEncode(codec.createHelper(), buffer, packet);
                var decoded = assertInstanceOf(AvailableEntityIdentifiersPacket.class, codec.tryDecode(codec.createHelper(), buffer,
                        codec.getPacketDefinition(AvailableEntityIdentifiersPacket.class).getId()));
                assertFalse(buffer.isReadable());
                var registry = new NetworkEntityRegistry();
                registry.replace(decoded.getIdentifiers());
                assertEquals(84, registry.type("minecraft:minecart", 0));
                assertEquals(65536, registry.type("custom:actor", 0));
                assertEquals(99, registry.type("custom:missing", 99));
            } finally {
                buffer.release();
            }
        }
    }

    @Test void registryReplacementsAreConnectionScopedAndCannotRetainOmittedDefinitions() {
        var registry = new NetworkEntityRegistry();
        var other = new NetworkEntityRegistry();
        registry.replace(registry(entry("minecraft:minecart", 84)));
        assertEquals(0, other.type("minecraft:minecart", 0));
        registry.replace(registry(entry("custom:other", 97)));
        assertEquals(0, registry.type("minecraft:minecart", 0));
        assertEquals(97, registry.type("custom:other", 0));
    }

    @Test void malformedAndConflictingEntriesCannotAssignAnUnobservedType() {
        var registry = new NetworkEntityRegistry();
        registry.replace(registry(entry("", 2), NbtMap.builder().putString("id", "missing:type").build(),
                NbtMap.builder().putString("id", "wrong:type").putString("rid", "84").build(), entry("minecraft:minecart", 84)));
        assertEquals(-1, registry.type("missing:type", -1));
        assertEquals(-1, registry.type("wrong:type", -1));
        assertThrows(IllegalArgumentException.class, () -> registry.replace(registry(entry("custom:actor", 1), entry("custom:actor", 2))));
        assertEquals(84, registry.type("minecraft:minecart", 0));
        assertEquals(0, registry.type("custom:actor", 0));
    }
}
