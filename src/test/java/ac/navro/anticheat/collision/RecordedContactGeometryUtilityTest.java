package ac.navro.anticheat.collision;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WirePlayerState;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;






class RecordedContactGeometryUtilityTest {
    private static JsonObject fixture(String name) throws Exception {
        try (var reader = new InputStreamReader(RecordedContactGeometryUtilityTest.class
                .getResourceAsStream(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Vec3 vector(JsonElement value) {
        var a = value.getAsJsonArray();
        return new Vec3(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static Box box(JsonElement value) {
        var a = value.getAsJsonArray();
        return new Box(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(),
                a.get(3).getAsFloat(), a.get(4).getAsFloat(), a.get(5).getAsFloat());
    }

    private static List<Box> solve(WirePlayerState player, JsonObject move, Vec3 minimum) {
        var shapes = move.getAsJsonArray("colliders").asList().stream()
                .map(RecordedContactGeometryUtilityTest::box).toList();
        var result = ProtocolCollisionSolver.solveResolved(vector(move.get("requested")), player.boundingBox(),
                shapes, minimum, move.get("stepHeight").getAsFloat(), move.get("canStep").getAsBoolean(),
                player.collisionPhysics.resolveAcceptedOverlaps());
        player.collisionPhysics.finishOverlapResolution();
        player.setResolvedMovement(result.aabb, false);
        return shapes;
    }

    @Test void fourRecordedChestSequencesKeepSupportWithoutAFixedAcceptanceAllowance() throws Exception {
        var sequences = fixture("/chest-accepted-position-sequences.json").getAsJsonArray("sequences");
        assertEquals(4, sequences.size());
        int frames = 0;
        for (var sequence : sequences) {
            var rows = sequence.getAsJsonObject().getAsJsonArray("frames");
            var player = new WirePlayerState();
            player.applyDimensions(EntityDimensions.fixed(.58F, 1.8F));
            player.setResolvedMovement(box(rows.get(0).getAsJsonObject().get("preBox")), false);
            long revision = player.aabbResizeRevision();
            for (var element : rows) {
                var row = element.getAsJsonObject();
                var shapes = solve(player, row, row.get("oneWay").getAsBoolean() ? Vec3.ZERO : new Vec3(1, 1, 1));
                assertTrue(player.position().distanceTo(vector(row.get("clientFoot")))
                        < row.get("offsetThreshold").getAsFloat(), "Captured client position tick=" + row.get("tick"));
                assertTrue(shapes.stream().noneMatch(player.boundingBox()::intersects));
                Box physical = player.boundingBox().clone();
                player.acceptInputPosition(vector(row.get("clientFoot")), shapes);
                assertEquals(physical.minY, player.boundingBox().minY, "Acceptance retains solved support");
                assertEquals(revision, player.aabbResizeRevision());
                frames++;
            }
        }
        assertEquals(12, frames);
    }

    @Test void continuousPoolSequenceKeepsEveryObservedWirePositionWithoutUnnecessaryYRebasing() throws Exception {
        var rows = fixture("/client-wire-pool-61-inputs.json").getAsJsonArray("frames");
        var first = rows.get(0).getAsJsonObject();
        var player = new WirePlayerState();
        player.applyDimensions(EntityDimensions.fixed(first.get("width").getAsFloat(), first.get("height").getAsFloat()));
        player.setResolvedMovement(box(first.getAsJsonArray("solverMoves").get(0).getAsJsonObject().get("preBox")), false);
        long revision = first.get("aabbResizeRevisionAfter").getAsLong();
        assertEquals(61, rows.size());
        for (var element : rows) {
            var row = element.getAsJsonObject();
            long nextRevision = row.get("aabbResizeRevisionAfter").getAsLong();
            if (nextRevision != revision) {
                var previous = player.dimensions();
                var dimensions = EntityDimensions.fixed(row.get("width").getAsFloat(), row.get("height").getAsFloat());
                player.applyDimensions(dimensions);
                if (previous.width() == dimensions.width() && previous.height() == dimensions.height())
                    player.refreshPoseBoundingBox();
                revision = nextRevision;
            }
            var shapes = new ArrayList<Box>();
            for (var move : row.getAsJsonArray("solverMoves")) {
                var m = move.getAsJsonObject();
                shapes.addAll(solve(player, m, vector(m.get("maxDepenetration"))));
            }
            var wire = vector(row.get("wirePosition"));
            var observation = new InputPosition(wire.x, wire.y, wire.z, row.get("movementYOffset").getAsFloat());
            assertEquals(0F, observation.distance(player.position()), "Before acceptance tick=" + row.get("tick"));
            player.restoreInputObservation(observation, Vec3.ZERO);
            Box before = player.boundingBox().clone();
            player.acceptObservedInputPosition(shapes);
            assertTrue(before.equals(player.boundingBox()), "Matching wire coordinates preserve all six physical bounds");
        }
    }
}
