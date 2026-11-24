package ac.navro.proxy.check.impl.place;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.data.block.NativeDoorSelection;
import ac.navro.proxy.data.block.NativeSelectionProperties;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * Exercises the complete native door outline component against independent core executions. The
 * lifecycle scenario below separately exercises click dispatch and N+1 forwarding.
 */
class DoorSelectionReferenceTest {
    @Test
    void doorSelectionComponentMatchesNativeBoundsAndMetadata() throws Exception {
        int rows = 0;
        try (var in =
                new DataInputStream(
                        new GZIPInputStream(
                                getClass().getResourceAsStream("/native-door-selection.bin.gz")))) {
            assertEquals(0x4e445231, in.readInt());
            int count = in.readInt();
            assertTrue(count > 0);
            for (int i = 0; i < count; i++) {
                int id = in.readInt(),
                        meta = in.readInt(),
                        partner = in.readInt(),
                        partnerMeta = in.readInt();
                var actual = NativeSelectionProperties.get(id, meta);
                var outline =
                        NativeDoorSelection.outline(
                                17,
                                65,
                                -9,
                                actual.damage(),
                                dy -> NativeSelectionProperties.get(partner, partnerMeta));
                assertEquals(in.readInt(), actual.id(), "native ID " + id + ":" + meta);
                assertEquals(in.readInt(), actual.damage());
                assertEquals(in.readBoolean(), NativeDoorSelection.sturdy(id, meta));
                var b = outline;
                float[] bounds = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
                for (int axis = 0; axis < 6; axis++)
                    assertEquals(
                            Float.floatToRawIntBits((float) in.readDouble()),
                            Float.floatToRawIntBits(bounds[axis]),
                            "id="
                                    + id
                                    + " meta="
                                    + meta
                                    + " partner="
                                    + partner
                                    + ":"
                                    + partnerMeta
                                    + " axis="
                                    + axis);
                rows++;
            }
            assertEquals(count, rows);
            assertEquals(-1, in.read());
        }
        assertFalse(NativeDoorSelection.supported(1));
    }

    @Test
    void actualDoorInteractionDoesNotWaitForUnavailableDetachedSelectionFacts() {
        var s = PlacementPipelineLifecycleTest.session();
        s.world().updateLegacyBlock(8, 65, 10, 0, 64 << 13);
        s.world().updateLegacyBlock(8, 66, 10, 0, (64 << 13) | 8);
        PlacementPipelineLifecycleTest.history(s);
        var sent =
                new java.util.ArrayList<
                        org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket>();
        s.placementPipeline()
                .setTransports(
                        p -> {
                            sent.add(p);
                            return true;
                        },
                        p -> {});
        assertFalse(s.receiveClient(PlacementPipelineLifecycleTest.transaction(), 8500));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertEquals(
                s.world().networkIdForLegacy((64 << 13) | 4),
                s.world().getRawBlockAt(8, 65, 10, 0));
        assertTrue(sent.isEmpty());
        assertTrue(s.receiveClient(PlacementPipelineLifecycleTest.input(3, 0), 8500));
        assertEquals(1, sent.size());
        assertEquals(
                s.world().networkIdForLegacy((64 << 13) | 4),
                s.world().getRawBlockAt(8, 65, 10, 0));
    }
}
