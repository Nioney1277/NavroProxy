package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PredictionSyncLifecycleTest {
    private static ClientMovementPredictionSyncPacket packet(long entityId) {
        var packet = new ClientMovementPredictionSyncPacket();
        packet.setUniqueEntityId(entityId);
        packet.captureFlags(Set.of(1, 3, 4, 32, 57, 114, 223));
        packet.setBoundingBox(Vector3f.from(1, .58F, .625F));
        packet.setSpeed(.13F);
        packet.setUnderwaterSpeed(.02F);
        packet.setLavaSpeed(.01F);
        packet.setJumpStrength(.42F);
        packet.setHealth(20F);
        packet.setHunger(19F);
        packet.setUnknown1(.25F);
        packet.setUnknown2(-.5F);
        packet.setUnknown3(123.75F);
        packet.setFlying(true);
        return packet;
    }

    @Test void identityUsesSignedUniqueIdAndRejectsTheInvalidSentinel() {
        var state = new PredictionSyncState();
        assertFalse(state.receive(packet(84), 42, 100));
        assertFalse(state.receive(packet(-1), -1, 100));
        assertNull(state.pending());
        assertTrue(state.receive(packet(-42), -42, 101));
        assertEquals(-42, state.pending().uniqueEntityId());
    }

    @Test void firstReportOwnsThePendingSlotAndSurvivesPacketMutation() {
        var state = new PredictionSyncState();
        var packet = packet(42);
        assertTrue(state.receive(packet, 42, 100));
        var first = state.pending();
        packet.setBoundingBox(Vector3f.ZERO);
        packet.setSpeed(4);
        packet.setUnknown3(0);
        packet.setFlying(false);
        packet.captureFlags(Set.of());
        assertFalse(state.receive(packet, 42, 101));
        assertSame(first, state.pending());
        assertEquals(new PredictionSyncState.Report(1, 100, 42, Set.of(1, 3, 4, 32, 57, 114, 223),
                1, .58F, .625F, .13F, .02F, .01F, .42F, 20, 19, .25F, -.5F, 123.75F, true), state.consume());
        assertNull(state.consume());
        assertThrows(UnsupportedOperationException.class, () -> first.flags().add(0));
        assertTrue(state.receive(packet, 42, 102));
        assertEquals(2, state.consume().sequence());
    }

    @Test void untrustedNonFiniteScalarsRemainDeclarations() {
        var state = new PredictionSyncState();
        var packet = packet(42);
        packet.setBoundingBox(Vector3f.from(-0F, Float.NaN, Float.POSITIVE_INFINITY));
        packet.setSpeed(Float.NEGATIVE_INFINITY);
        assertTrue(state.receive(packet, 42, 100));
        var report = state.consume();
        assertEquals(Float.floatToRawIntBits(-0F), Float.floatToRawIntBits(report.scale()));
        assertTrue(Float.isNaN(report.width()));
        assertEquals(Float.POSITIVE_INFINITY, report.height());
        assertEquals(Float.NEGATIVE_INFINITY, report.speed());
    }

    @Test void disconnectedInputPreservesTheDeclarationAndAcceptedInputConsumesBeforeActions() {
        var session = AuthInputPacketsTest.session();
        var events = diagnostics(session);
        assertTrue(session.receiveClient(packet(70), 200));
        var report = session.player().predictionSync().pending();
        assertNotNull(report);
        assertFalse(session.receiveClient(AuthInputPacketsTest.input(-1, 8, 64, 8, Vec3.ZERO), 200));
        assertSame(report, session.player().predictionSync().pending());
        assertTrue(session.player().disconnectRequested());
        session = AuthInputPacketsTest.session();
        events = diagnostics(session);
        assertTrue(session.receiveClient(packet(70), 200));
        report = session.player().predictionSync().pending();
        var input = AuthInputPacketsTest.input(0, 8, 64, 8, new Vec3(0, -.0784F, 0));
        input.getInputData().add(PlayerAuthInputData.START_CRAWLING);
        session.receiveClient(input, 200);
        assertNull(session.player().predictionSync().pending());
        var consumed = events.stream().filter(event -> "clientPredictionSyncAtInput".equals(event.get("event"))).toList();
        assertEquals(1, consumed.size());
        var loggedReport = (Map<?, ?>) consumed.getFirst().get("report");
        assertEquals(report.sequence(), loggedReport.get("sequence"));
        assertEquals(report.flags(), loggedReport.get("flags"));
        assertEquals(0L, consumed.getFirst().get("sourceTick"));
        var canonical = (Map<?, ?>) consumed.getFirst().get("canonicalState");
        assertFalse((Boolean) canonical.get("crawling"));
    }

    @Test void declarationNeverAssignsCanonicalDimensionsFlagsSpeedOrFlight() {
        var session = AuthInputPacketsTest.session();
        var control = AuthInputPacketsTest.session();
        var player = session.player();
        var dimensions = player.dimensions();
        var flags = player.trackedFlags().cloneFlags();
        assertTrue(session.receiveClient(packet(70), 200));
        assertEquals(dimensions, player.dimensions());
        assertEquals(flags, player.trackedFlags().cloneFlags());
        assertFalse(player.flying());
        assertFalse(player.predictionSprinting());
        for (var current : List.of(session, control)) {
            current.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        }
        AuthInputPacketsTest.vector(control.player().position(), player.position());
        AuthInputPacketsTest.vector(control.player().velocity(), player.velocity());
        assertEquals(control.player().dimensions(), player.dimensions());
        assertEquals(control.player().trackedFlags().cloneFlags(), player.trackedFlags().cloneFlags());
        assertNull(player.predictionSync().pending());
    }

    @Test void startGameClearsThePendingDeclarationWithoutResettingTheSequence() {
        var session = AuthInputPacketsTest.session();
        assertTrue(session.receiveClient(packet(70), 200));
        var start = new StartGamePacket();
        start.setRuntimeEntityId(8);
        start.setUniqueEntityId(-42);
        start.setPlayerPosition(Vector3f.from(8, 64, 8));
        session.observeBackend(start);
        assertNull(session.player().predictionSync().pending());
        assertTrue(session.receiveClient(packet(70), 200));
        assertNull(session.player().predictionSync().pending());
        assertTrue(session.receiveClient(packet(-42), 200));
        assertEquals(2, session.player().predictionSync().pending().sequence());
    }

    @Test void inspectionAndExemptPlayersDoNotCaptureReports() {
        var session = AuthInputPacketsTest.session();
        session.player().setExempted(true);
        assertTrue(session.receiveClient(packet(70), 200));
        assertNull(session.player().predictionSync().pending());
        session.player().setExempted(false);
        session.setDetectionEnabled(false);
        session.observeClient(packet(70));
        assertNull(session.player().predictionSync().pending());
    }

    @Test void failingDiagnosticsCannotPreventCaptureOrConsumption() {
        var session = AuthInputPacketsTest.session();
        session.player().setWorldDiagnostics(new WorldCollisionDiagnostics(session.player(), session.world(), "broken",
                ignored -> { throw new IllegalStateException("unavailable output"); }));
        assertTrue(session.receiveClient(packet(70), 200));
        assertNotNull(session.player().predictionSync().pending());
        session.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        assertNull(session.player().predictionSync().pending());
    }

    @Test void nonFiniteDeclarationsRemainSerializableInAsynchronousDiagnostics() {
        var session = AuthInputPacketsTest.session();
        var events = diagnostics(session);
        var packet = packet(70);
        packet.setBoundingBox(Vector3f.from(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
        packet.setSpeed(Float.NaN);
        packet.setUnknown1(Float.NEGATIVE_INFINITY);
        assertTrue(session.receiveClient(packet, 200));
        session.observeClient(AuthInputPacketsTest.input(10, 8, 64, 8, new Vec3(0, -.0784F, 0)));
        var consumed = events.stream().filter(event -> "clientPredictionSyncAtInput".equals(event.get("event"))).findFirst().orElseThrow();
        var report = (Map<?, ?>) consumed.get("report");
        assertEquals("NaN", report.get("scale"));
        assertEquals("Infinity", report.get("width"));
        assertEquals("-Infinity", report.get("height"));
        assertDoesNotThrow(() -> new com.google.gson.Gson().toJson(events));
    }

    private static List<Map<String, Object>> diagnostics(SessionState session) {
        var events = new ArrayList<Map<String, Object>>();
        session.player().setWorldDiagnostics(new WorldCollisionDiagnostics(session.player(), session.world(), "sync", events::add));
        return events;
    }
}
