package ac.navro.proxy.check.impl.place;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;

import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.util.*;

class PlacementPipelineLifecycleTest {
    static ItemData stone(int count) {
        return ItemData.builder()
                .definition(new SimpleItemDefinition("minecraft:stone", 1, false))
                .count(count)
                .build();
    }

    static SessionState session() {
        var s = AuthInputPacketsTest.session();
        s.player().sinceLoadingScreen = 0;
        s.inventory().inventoryContainer.set(0, stone(4));
        s.world().updateLegacyBlock(8, 65, 10, 0, 1 << 13);
        return s;
    }

    static PlayerAuthInputPacket input(long tick, float angle) {
        var p = AuthInputPacketsTest.input(tick, 8, 64, 8, new Vec3(0, -.0784F, 0));
        p.setInteractRotation(Vector2f.from(0, angle));
        p.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        return p;
    }

    static InventoryTransactionPacket transaction() {
        var p = new InventoryTransactionPacket();
        p.setTransactionType(InventoryTransactionType.ITEM_USE);
        p.setActionType(0);
        p.setBlockPosition(Vector3i.from(8, 65, 10));
        p.setBlockFace(2);
        p.setHotbarSlot(0);
        p.setItemInHand(stone(4));
        p.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        p.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        return p;
    }

    static void history(SessionState s) {
        history(s, 0);
    }

    static void history(SessionState s, float cameraYaw) {
        s.player().commitInputTick(0);
        s.player().sinceAuthInput = System.currentTimeMillis();
        for (long tick = 1; tick <= 2; tick++) {
            var p = input(tick, 0);
            p.setRotation(Vector3f.from(0, cameraYaw, 0));
            assertTrue(s.receiveClient(p, 8500), s.anomalies().toString());
        }
    }

    @Test
    void
            actualBlockEntryPredictsOnceHoldsUntilNextInputThenForwardsWithoutRevalidatingOrConsumingAgain() {
        var s = session();
        var sent = new ArrayList<InventoryTransactionPacket>();
        s.placementPipeline()
                .setTransports(
                        p -> {
                            sent.add(p);
                            return true;
                        },
                        p -> {});
        history(s);
        var p = transaction();
        assertFalse(s.receiveClient(p, 8500));
        assertTrue(sent.isEmpty());
        assertEquals(3, s.inventory().inventoryContainer.get(0).count());
        assertEquals(s.world().networkIdForLegacy(1 << 13), s.world().getRawBlockAt(8, 65, 9, 0));
        assertTrue(s.receiveClient(input(3, 0), 8500), s.anomalies().toString());
        assertEquals(List.of(p), sent);
        assertEquals(3, s.inventory().inventoryContainer.get(0).count());
        assertTrue(s.backendInventories().pending(0, 0));
    }

    @Test
    void
            oppositeNextRayRejectsHeldPlacementWithoutForwardingAndWaitsForFullSlotIdentityToCorrect() {
        var s = session();
        var sent = new ArrayList<InventoryTransactionPacket>();
        s.placementPipeline()
                .setTransports(
                        p -> {
                            sent.add(p);
                            return true;
                        },
                        p -> {});
        var alerts = new ArrayList<String>();
        s.player().setAlertSink(alerts::add);
        history(s, 180);

        var next = input(3, 180);
        next.setRotation(Vector3f.from(0, 180, 0));
        assertFalse(s.receiveClient(transaction(), 8500));
        assertTrue(s.receiveClient(next, 8500), s.anomalies().toString());
        assertTrue(sent.isEmpty());
        assertTrue(
                alerts.stream().anyMatch(a -> a.contains("RotationPlace") && a.contains("§7x§a1")),
                alerts.toString());
        assertEquals(1, s.placementPipeline().pendingSlotCorrectionCount());
        assertEquals(3, s.inventory().inventoryContainer.get(0).count());
        var full = new InventoryContentPacket();
        full.setContainerId(0);
        full.setContents(new ArrayList<>(Collections.nCopies(36, ItemData.AIR)));
        full.getContents().set(0, stone(4));
        s.observeBackend(full);
        assertEquals(0, s.placementPipeline().pendingSlotCorrectionCount());
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
    }

    @Test
    void unknownTargetSectionIsPendingAndDoesNotConsumeOrFlagAndExpiresThroughCorrectionBoundary() {
        var s = session();
        var alerts = new ArrayList<String>();
        s.player().setAlertSink(alerts::add);
        history(s);
        s.world().getChunk(0, 0).invalidateSection(8);
        assertFalse(s.receiveClient(transaction(), 8500));
        assertEquals(1, s.placementPipeline().awaitingDataCount());
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertTrue(alerts.isEmpty());
        for (int i = 0; i < 20; i++) s.placementTick();
        assertEquals(0, s.placementPipeline().awaitingDataCount());
        assertEquals(1, s.placementPipeline().pendingSlotCorrectionCount());
    }

    @Test
    void heldPlacementTimeoutAndBackendCorrectionNeverExecuteThePendingTransaction() {
        for (boolean timeout : new boolean[] {false, true}) {
            var s = session();
            var sent = new ArrayList<InventoryTransactionPacket>();
            s.placementPipeline()
                    .setTransports(
                            p -> {
                                sent.add(p);
                                return true;
                            },
                            p -> {});
            history(s);
            assertFalse(s.receiveClient(transaction(), 8500));
            if (timeout) for (int i = 0; i < 20; i++) s.placementTick();
            else {
                var correction=new CorrectPlayerMovePredictionPacket();correction.setTick(2);
                correction.setPosition(Vector3f.from(8,65.62001F,8));correction.setDelta(Vector3f.from(0,-.0784F,0));
                correction.setOnGround(true);correction.setPredictionType(org.cloudburstmc.protocol.bedrock.data.PredictionType.PLAYER);
                correction.setVehicleRotation(Vector2f.ZERO);s.observeBackend(correction);
            }
            assertTrue(sent.isEmpty());
            assertEquals(1, s.placementPipeline().pendingSlotCorrectionCount());
            assertTrue(s.receiveClient(input(3, 0), 8500), s.anomalies().toString());
            assertTrue(sent.isEmpty());
        }
    }

    @Test
    void ordinaryActivationUsesTheCompleteDispatcherAndDoesNotConsumeHeldBlocks() {
        var s = session();
        s.world().updateLegacyBlock(8, 65, 10, 0, 96 << 13);
        var p = transaction();
        assertTrue(s.receiveClient(p, 8500));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertEquals(
                s.world().networkIdForLegacy((96 << 13) | 8),
                s.world().getRawBlockAt(8, 65, 10, 0));
    }

    @Test
    void embeddedItemUseRunsTheInventoryValidatorBeforeAuthInputObservation() {
        var s = session();
        var alerts = new ArrayList<String>();
        s.player().setAlertSink(alerts::add);
        history(s);

        var input = input(3, 0);
        input.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
        var embedded = new ItemUseTransaction();
        embedded.setActionType(0);
        embedded.setBlockPosition(Vector3i.from(8, 65, 10));
        embedded.setBlockFace(2);
        embedded.setHotbarSlot(0);


        embedded.setItemInHand(
                ItemData.builder()
                        .definition(new SimpleItemDefinition("minecraft:apple", 260, false))
                        .count(1)
                        .build());
        embedded.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        embedded.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        input.setItemUseTransaction(embedded);

        assertFalse(s.receiveClient(input, 8500));
        assertEquals(4, s.inventory().inventoryContainer.get(0).count());
        assertTrue(alerts.isEmpty(), alerts.toString());
        assertEquals(2, s.tickSnapshots().size(), "Rejected embedded input must not enter movement observation");
    }

    @Test
    void acceptedEmbeddedPlacementPredictsOnceAndRemainsInTheForwardedInput() {
        var s = session();
        history(s);
        var input = input(3, -11.3F);
        input.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
        var embedded = new ItemUseTransaction();
        embedded.setActionType(0);
        embedded.setBlockPosition(Vector3i.from(8, 65, 10));
        embedded.setBlockFace(2);
        embedded.setHotbarSlot(0);
        embedded.setItemInHand(stone(4));
        embedded.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        embedded.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        input.setItemUseTransaction(embedded);

        assertTrue(s.receiveClient(input, 8500), s.anomalies().toString());
        assertNotNull(input.getItemUseTransaction(), "A valid embedded click remains in the input for forwarding");
        assertTrue(input.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION));
        s.onClientPacketForwarded(input);
        assertEquals(3, s.inventory().inventoryContainer.get(0).count());
        assertEquals(s.world().networkIdForLegacy(1 << 13), s.world().getRawBlockAt(8, 65, 9, 0));
        assertTrue(s.backendInventories().pending(0, 0));
    }
}
