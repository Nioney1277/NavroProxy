package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.util.block.PlacementContext;
import ac.navro.proxy.packets.player.PlacementPipeline;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.data.block.NativeConnectedSelection;
import ac.navro.proxy.data.block.NativeSelectionProperties;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * NativeConnectedSelection.selection is the complete connection/outline component boundary.
 * Expected bounds and sturdy values are frozen executions of the independent MOT core. Packet
 * forwarding is verified separately through SessionState.receiveClient.
 */
class ConnectedSelectionReferenceTest {
    @Test
    void selectionComponentMatchesNativeBoundsForEveryRecordedNeighbor() throws Exception {
        int rows = 0, connected = 0, disconnected = 0;
        try (var in =
                new DataInputStream(
                        new GZIPInputStream(
                                getClass()
                                        .getResourceAsStream("/native-connected-rules.bin.gz")))) {
            assertEquals(0x4e434332, in.readInt());
            int count = in.readInt();
            assertTrue(count > 0);
            for (int i = 0; i < count; i++) {
                int id = in.readInt(),
                        meta = in.readInt(),
                        neighborId = in.readInt(),
                        neighborMeta = in.readInt();
                boolean known = in.readBoolean(), expected = in.readBoolean();
                var neighbor = NativeConnectedSelection.neighbor(neighborId, neighborMeta);
                if (known) {
                    var actual =
                            NativeConnectedSelection.selection(
                                    id,
                                    meta,
                                    0,
                                    0,
                                    0,
                                    side ->
                                            side == 0
                                                    ? neighbor
                                                    : NativeConnectedSelection.neighbor(0, 0));
                    var box = actual.outline();
                    float[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
                    for (int axis = 0; axis < 6; axis++) {
                        int nativeBits = Float.floatToRawIntBits((float) in.readDouble());
                        if (nativeBits != Float.floatToRawIntBits(bounds[axis]))
                            fail(
                                    "id="
                                            + id
                                            + ":"
                                            + meta
                                            + " neighbor="
                                            + neighborId
                                            + ":"
                                            + neighborMeta
                                            + " axis="
                                            + axis
                                            + " expectedBits="
                                            + nativeBits
                                            + " actual="
                                            + bounds[axis]);
                    }
                    boolean sturdy = in.readBoolean();
                    if (sturdy != actual.sturdy())
                        fail(
                                "sturdy id="
                                        + id
                                        + ":"
                                        + meta
                                        + " neighbor="
                                        + neighborId
                                        + ":"
                                        + neighborMeta);
                    if (expected) connected++;
                    else disconnected++;
                } else
                    assertThrows(
                            NativeConnectedSelection.UnknownProperty.class,
                            () ->
                                    NativeConnectedSelection.selection(
                                            id,
                                            meta,
                                            0,
                                            0,
                                            0,
                                            side ->
                                                    side == 0
                                                            ? neighbor
                                                            : NativeConnectedSelection.neighbor(
                                                                    0, 0)));
                rows++;
            }
            assertEquals(count, rows);
            assertEquals(-1, in.read());
        }
        assertTrue(connected > 0);
        assertTrue(disconnected > 0);
    }

    @Test
    void selectionComponentMatchesNativeBoundsForEveryConnectionPattern() throws Exception {
        int rows = 0;
        try (var in =
                new DataInputStream(
                        new GZIPInputStream(
                                getClass()
                                        .getResourceAsStream(
                                                "/native-connected-geometry.bin.gz")))) {
            assertEquals(0x4e434731, in.readInt());
            int count = in.readInt();
            assertTrue(count > 0);
            for (int i = 0; i < count; i++) {
                int id = in.readInt(), meta = in.readInt(), mask = in.readInt();
                var facts = NativeSelectionProperties.get(id, meta);
                var actual =
                        NativeConnectedSelection.selection(
                                id,
                                meta,
                                17,
                                65,
                                -9,
                                side ->
                                        NativeConnectedSelection.neighbor(
                                                (mask & (1 << side)) != 0 ? 1 : 0, 0));
                assertEquals(in.readInt(), facts.id());
                assertEquals(in.readInt(), facts.damage());
                assertEquals(
                        in.readBoolean(),
                        actual.sturdy(),
                        "id=" + id + " meta=" + meta + " mask=" + mask);
                var b = actual.outline();
                float[] bounds = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
                for (int axis = 0; axis < 6; axis++)
                    assertEquals(
                            Float.floatToRawIntBits((float) in.readDouble()),
                            Float.floatToRawIntBits(bounds[axis]),
                            "id=" + id + " meta=" + meta + " mask=" + mask + " axis=" + axis);
                rows++;
            }
            assertEquals(count, rows);
            assertEquals(-1, in.read());
        }
        assertFalse(NativeConnectedSelection.supported(139));
    }

    @Test
    void
            actualNPlusOnePlacementUsesTheNarrowConnectedOutlineWithoutUnknownDataOrDoubleConsumption() {
        for (int id : new int[] {85, 101, 113, 160})
            for (boolean hit : new boolean[] {false, true}) {
                var s = PlacementPipelineLifecycleTest.session();
                s.world().updateLegacyBlock(8, 65, 10, 0, id << 13);
                var sent =
                        new ArrayList<
                                org.cloudburstmc.protocol.bedrock.packet
                                        .InventoryTransactionPacket>();
                s.placementPipeline()
                        .setTransports(
                                p -> {
                                    sent.add(p);
                                    return true;
                                },
                                p -> {});
                PlacementPipelineLifecycleTest.history(s);
                var transaction = PlacementPipelineLifecycleTest.transaction();
                assertFalse(s.receiveClient(transaction, 8500));
                assertEquals(3, s.inventory().inventoryContainer.get(0).count());
                assertTrue(sent.isEmpty());
                assertTrue(
                        s.receiveClient(
                                PlacementPipelineLifecycleTest.input(3, hit ? -11.3F : 0), 8500),
                        s.anomalies().toString());
                assertEquals(
                        hit ? List.of(transaction) : List.of(), sent, "id=" + id + " hit=" + hit);
                assertEquals(3, s.inventory().inventoryContainer.get(0).count());
                assertEquals(hit ? 0 : 1, s.placementPipeline().pendingSlotCorrectionCount());
            }
    }

    @Test
    void worldSelectionSkipsPredictionForMissingNeighborSection() {
        var s = PlacementPipelineLifecycleTest.session();
        s.world().updateLegacyBlock(15, 65, 8, 0, 85 << 13);
        s.world().getChunk(1, 0).invalidateSection(8);
        var forwarded =
                new ArrayList<
                        org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket>();
        var alerts = new ArrayList<String>();
        s.player().setAlertSink(alerts::add);
        s.placementPipeline()
                .setTransports(
                        packet -> {
                            forwarded.add(packet);
                            return true;
                        },
                        packet -> {});
        PlacementPipelineLifecycleTest.history(s);
        var transaction = PlacementPipelineLifecycleTest.transaction();
        transaction.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.from(15, 65, 8));
        assertTrue(s.receiveClient(transaction, 8500));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertTrue(forwarded.isEmpty());
        assertTrue(alerts.isEmpty());
        var missing =
                assertThrows(
                        PlacementPipeline.MissingFact.class,
                        () -> s.placementPipeline().block(new PlacementContext.Cell(15, 65, 8)));
        assertEquals("unknown_connection_neighbor_section", missing.getMessage());
        var layers = new int[2][4096];
        for (var layer : layers) Arrays.fill(layer, s.world().airNetworkId());
        s.world().getChunk(1, 0).setNetworkSection(8, layers);
        s.world().updateLegacyBlock(16, 65, 8, 0, 1 << 13);
        var actual = s.placementPipeline().block(new PlacementContext.Cell(15, 65, 8));
        assertEquals(16F, actual.outline().maxX);
    }
}
