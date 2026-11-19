package ac.navro.proxy.check.impl.place;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.place.PlacementContext.Cell;

import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;




class WallVinePlacementLifecycleTest {
    @Test
    void wallPlacementWaitsForNextRayAndUsesItsNarrowOutlineWithoutDoubleConsumption() {
        for (boolean hit : new boolean[] {false, true}) {
            var state = PlacementPipelineLifecycleTest.session();
            state.world().updateLegacyBlock(8, 65, 10, 0, 139 << 13);
            var forwarded = new ArrayList<InventoryTransactionPacket>();
            var alerts = new ArrayList<String>();
            state.player().setAlertSink(alerts::add);
            state.placementPipeline()
                    .setTransports(
                            p -> {
                                forwarded.add(p);
                                return true;
                            },
                            p -> {});
            PlacementPipelineLifecycleTest.history(state);
            var transaction = PlacementPipelineLifecycleTest.transaction();
            assertFalse(state.receiveClient(transaction, 8500));
            assertEquals(0, state.placementPipeline().awaitingDataCount());
            assertEquals(3, state.inventory().inventoryContainer.get(0).count());
            assertEquals(
                    state.world().networkIdForLegacy(1 << 13),
                    state.world().getRawBlockAt(8, 65, 9, 0));
            assertTrue(forwarded.isEmpty());
            assertTrue(
                    state.receiveClient(
                            PlacementPipelineLifecycleTest.input(3, hit ? -11.3F : 0), 8500));
            assertEquals(hit ? List.of(transaction) : List.of(), forwarded, "wall hit=" + hit);
            assertEquals(3, state.inventory().inventoryContainer.get(0).count());
            assertEquals(hit ? 0 : 1, state.placementPipeline().pendingSlotCorrectionCount());
            assertEquals(
                    !hit,
                    alerts.stream().anyMatch(a -> a.contains("RotationPlace")),
                    alerts.toString());
        }
    }

    @Test
    void wallAtChunkBoundaryWaitsWithoutPredictionUntilItsNeighborSectionIsKnown() {
        var state = PlacementPipelineLifecycleTest.session();
        state.world().updateLegacyBlock(15, 65, 10, 0, 139 << 13);
        var forwarded = new ArrayList<InventoryTransactionPacket>();
        var alerts = new ArrayList<String>();
        state.player().setAlertSink(alerts::add);
        state.placementPipeline()
                .setTransports(
                        p -> {
                            forwarded.add(p);
                            return true;
                        },
                        p -> {});
        PlacementPipelineLifecycleTest.history(state);
        state.world().getChunk(1, 0).invalidateSection(8);
        var request = PlacementPipelineLifecycleTest.transaction();
        request.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.from(15, 65, 10));
        assertFalse(state.receiveClient(request, 8500));
        assertEquals(1, state.placementPipeline().awaitingDataCount());
        assertEquals(4, state.inventory().inventoryContainer.get(0).count());
        assertTrue(forwarded.isEmpty());
        assertTrue(alerts.isEmpty());
        var missing =
                assertThrows(
                        PlacementPipeline.MissingFact.class,
                        () -> state.placementPipeline().block(new Cell(15, 65, 10)));
        assertEquals("unknown_wall_neighbor_section", missing.getMessage());
        knownSection(state, 1, 0, 8);
        state.world().updateLegacyBlock(16, 65, 10, 0, 1 << 13);
        var actual = state.placementPipeline().block(new Cell(15, 65, 10));


        assertEquals(16F, actual.outline().maxX);

        for (int tick = 0; tick < 20; tick++) state.placementTick();
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void zeroMetadataVineNeedsUpperSectionButSideAttachedVineDoesNot() {
        var state = PlacementPipelineLifecycleTest.session();
        state.world().updateLegacyBlock(8, 79, 10, 0, 106 << 13);
        state.world().getChunk(0, 0).invalidateSection(9);
        var missing =
                assertThrows(
                        PlacementPipeline.MissingFact.class,
                        () -> state.placementPipeline().block(new Cell(8, 79, 10)));
        assertEquals("unknown_vine_upper_section", missing.getMessage());
        state.world().updateLegacyBlock(8, 79, 10, 0, (106 << 13) | 2);
        var side = state.placementPipeline().block(new Cell(8, 79, 10));
        assertEquals(8F, side.outline().minX);
        assertEquals(8.0625F, side.outline().maxX);
        state.world().updateLegacyBlock(8, 79, 10, 0, 106 << 13);
        knownSection(state, 0, 0, 9);
        state.world().updateLegacyBlock(8, 80, 10, 0, 1 << 13);
        var upper = state.placementPipeline().block(new Cell(8, 79, 10));
        assertEquals(79.9375F, upper.outline().minY);
        assertEquals(80F, upper.outline().maxY);
        assertFalse(upper.sturdy());
    }

    @Test
    void ceilingVinePlacementUsesOriginalSelectionAndNextInputForwarding() {
        var state = PlacementPipelineLifecycleTest.session();
        state.world().updateLegacyBlock(8, 65, 10, 0, 106 << 13);
        state.world().updateLegacyBlock(8, 66, 10, 0, 1 << 13);
        var forwarded = new ArrayList<InventoryTransactionPacket>();
        state.placementPipeline()
                .setTransports(
                        p -> {
                            forwarded.add(p);
                            return true;
                        },
                        p -> {});
        PlacementPipelineLifecycleTest.history(state);
        var request = PlacementPipelineLifecycleTest.transaction();
        request.setBlockFace(0);
        assertFalse(state.receiveClient(request, 8500));
        assertEquals(0, state.placementPipeline().awaitingDataCount());
        assertEquals(3, state.inventory().inventoryContainer.get(0).count());
        assertTrue(forwarded.isEmpty());
        var next = PlacementPipelineLifecycleTest.input(3, 0);


        double dx = 0.5, dz = 2.5, dy = 65.9375 - (64 + 1.62001F);
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        next.setInteractRotation(Vector2f.from(pitch, yaw));
        assertTrue(state.receiveClient(next, 8500));
        assertEquals(List.of(request), forwarded);
        assertEquals(3, state.inventory().inventoryContainer.get(0).count());
    }

    private static void knownSection(SessionState state, int chunkX, int chunkZ, int section) {
        int[][] layers = new int[2][4096];
        for (int[] layer : layers) Arrays.fill(layer, state.world().airNetworkId());
        state.world().getChunk(chunkX, chunkZ).setNetworkSection(section, layers);
    }
}
