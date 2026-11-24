package ac.navro.proxy.check.impl.place;

import static ac.navro.proxy.util.block.PlacementContext.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.packets.input.PacketEvent;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.block.PlacementContext;
import com.google.gson.*;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

class RotationPlaceCameraTest {
    private static final class Context implements PlacementContext {
        Cell target = new Cell(0, 4, 2);
        Box outline = new Box(0, 4, 2, 1, 4.5625, 3);
        Direction face = Direction.NORTH;
        int applied;
        final List<String> logs = new ArrayList<>();
        public long epoch() { return 0; }
        public Object levelIdentity() { return this; }
        public boolean available() { return true; }
        public long runtimeId() { return 0; }
        public Vec3 acceptedMotion() { return Vec3.ZERO; }
        public GameType gameType() { return GameType.SURVIVAL; }
        public int minY() { return -64; }
        public int maxY() { return 320; }
        public boolean chunkLoaded(int x, int z) { return true; }
        public BlockView block(Cell p) {
            boolean bed = target.equals(p);
            return new BlockView(NetworkBlockState.legacy(bed ? 26 << 13 : 0),
                    bed ? 26 : 0, 0, !bed, !bed, false, false, bed ? outline : null);
        }
        public UseItemData placementData(InventoryTransactionPacket p) {
            // Deliberately no clickPos or reported playerPos: those client claims
            // cannot rescue a geometrically invalid ray.
            return new UseItemData(target, face, new ItemView(1, 0, true, true, 1, false),
                    0, 1, 1, 0, null, null);
        }
        public InventoryTransactionPacket embeddedTransaction(PlayerAuthInputPacket p) {
            if (p.getItemUseTransaction() == null) return null;
            var packet = new InventoryTransactionPacket(); packet.setTransactionType(InventoryTransactionType.ITEM_USE);
            return packet;
        }
        public void scheduleExpiry(Runnable r, int ticks) { }
        public void applyPlacement(InventoryTransactionPacket p) { applied++; }
        public void resyncPlacement(Cell target, Cell placed) { }
        public void resyncSlot(int slot) { }
        public boolean debugEnabled() { return true; }
        public void debug(String s) { logs.add(s); }
    }
    private static final class Probe extends RotationPlace {
        int flags;
        Probe(WirePlayerState player, Context context) { super(player, context); }
        @Override public void fail(String message) { flags++; }
    }
    private static PlayerAuthInputPacket input(long tick, double[] wire, double pitch, double yaw, boolean sneak) {
        var p = new PlayerAuthInputPacket();
        p.setTick(tick); p.setPosition(Vector3f.from(wire[0], wire[1], wire[2]));
        p.setRotation(Vector3f.from(pitch, yaw, yaw));
        p.setInteractRotation(Vector2f.from(pitch, yaw));
        p.setCameraOrientation(Vector3f.from(-Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
                -Math.sin(Math.toRadians(pitch)), Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))));
        p.setInputMode(InputMode.MOUSE); p.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        if (sneak) p.getInputData().add(PlayerAuthInputData.SNEAKING);
        return p;
    }
    private static void deliver(Probe check, PlayerAuthInputPacket p) {
        var original = p.getPosition(); var event = new PacketEvent(p);
        check.onPacketReceive(event); check.finishPacket(event);
        assertEquals(original, p.getPosition(), "View conversion must not rewrite movement coordinates");
    }
    private static void click(Probe check) {
        var p = new InventoryTransactionPacket(); p.setTransactionType(InventoryTransactionType.ITEM_USE);
        var event = new PacketEvent(p); check.onPacketReceive(event); check.finishPacket(event);
        assertTrue(event.isCancelled(), "Standalone placement waits for the next interaction ray");
    }
    private static void pose(WirePlayerState p, EntityFlag flag, boolean value) {
        p.setPredictionFlag(flag, value); p.updateInputPose();
    }
    private static double[] values(JsonArray a) {
        double[] v = new double[a.size()]; for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble(); return v;
    }

    @Test void recordedSneakingBedClicksPassThroughTheRealPendingPlacementBoundary() throws Exception {
        JsonArray rows;
        try (var r = new InputStreamReader(getClass().getResourceAsStream("/rotation-place-sneak-camera.json"), StandardCharsets.UTF_8)) {
            rows = JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("frames");
        }
        assertFalse(rows.isEmpty());
        for (var element : rows) {
            var row = element.getAsJsonObject(); var c = new Context(); var player = new WirePlayerState(); var check = new Probe(player, c);
            double[] box = values(row.getAsJsonArray("bounds")), a = values(row.getAsJsonArray("originA")), b = values(row.getAsJsonArray("originB"));
            var target = row.getAsJsonArray("target"); c.target = new Cell(target.get(0).getAsInt(), target.get(1).getAsInt(), target.get(2).getAsInt());
            c.outline = new Box(box[0], box[1], box[2], box[3], box[4], box[5]); c.face = Direction.valueOf(row.get("face").getAsString());
            double[] direction = values(row.getAsJsonArray("direction"));
            double pitch = Math.toDegrees(-Math.asin(direction[1])), yaw = Math.toDegrees(Math.atan2(-direction[0], direction[2]));
            // Reproduce the old miss independently of queue/camera snapshot state.
            assertFalse(RotationPlace.canHit(new RotationPlace.Point(a[0], a[1], a[2]), new RotationPlace.Point(b[0], b[1], b[2]),
                    new RotationPlace.Point(direction[0], direction[1], direction[2]), c.outline, c.face, List.of()));
            deliver(check, input(1, a, pitch, yaw, true)); deliver(check, input(2, b, pitch, yaw, true));
            click(check); deliver(check, input(3, b, pitch, yaw, true));
            assertEquals(0, check.flags, "Recorded tick=" + row.get("tick") + " " + c.logs);
            assertEquals(1, c.applied);
            assertTrue(c.logs.stream().anyMatch(s -> s.contains("cameraYOffsetA=-0.35 cameraYOffsetB=-0.35")));
        }
    }

    @Test void crawlingSwimmingAndGlidingKeepTheirCapturedViewWhenLaterPoseMetadataChanges() {
        for (var flag : List.of(EntityFlag.CRAWLING, EntityFlag.SWIMMING, EntityFlag.GLIDING)) {
            var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
            pose(p, flag, true); assertTrue(p.horizontalPoseApplied());
            double[] wire = {.5, 5.62001, .5};
            deliver(check, input(1, wire, 0, 0, false)); deliver(check, input(2, wire, 0, 0, false));
            click(check); pose(p, flag, false);
            deliver(check, input(3, wire, 0, 0, false));
            assertEquals(0, check.flags, flag + " " + c.logs); assertEquals(1, c.applied);
            // New standing frames must not keep the old low view origin.
            deliver(check, input(4, wire, 0, 0, false)); deliver(check, input(5, wire, 0, 0, false));
            click(check); deliver(check, input(6, wire, 0, 0, false));
            assertEquals(1, check.flags, "Standing ray should miss the low bed: " + flag);
            assertEquals(1, c.applied);
        }
    }

    @Test void horizontalPoseTakesPrecedenceOverSneakAndDoesNotDoubleSubtractTheViewDrop() {
        var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
        pose(p, EntityFlag.CRAWLING, true); double[] wire = {.5, 5.62001, .5};
        deliver(check, input(1, wire, 0, 0, true)); deliver(check, input(2, wire, 0, 0, true));
        click(check); deliver(check, input(3, wire, 0, 0, true));
        assertEquals(0, check.flags, c.logs.toString()); assertEquals(1, c.applied);
    }

    @Test void poseChangeDuringTheInputIsCapturedAfterValidationAndInterpolatedAcrossHistory() {
        var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
        c.outline = new Box(0, 4.5, 2, 1, 5, 3); double[] wire = {.5, 5.62001, .5};
        deliver(check, input(1, wire, 0, 0, false));
        var next = input(2, wire, 0, 0, false); var event = new PacketEvent(next);
        check.onPacketReceive(event); pose(p, EntityFlag.CRAWLING, true); check.finishPacket(event);
        click(check); deliver(check, input(3, wire, 0, 0, false));
        assertEquals(0, check.flags, c.logs.toString()); assertEquals(1, c.applied);
        assertTrue(c.logs.stream().anyMatch(s -> s.contains("cameraYOffsetA=0.0 cameraYOffsetB=-1.2")), c.logs.toString());
    }

    @Test void wrongDirectionStillFlagsInEveryPose() {
        for (var flag : Arrays.asList(null, EntityFlag.SNEAKING, EntityFlag.CRAWLING, EntityFlag.SWIMMING, EntityFlag.GLIDING)) {
            var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
            if (flag != null) pose(p, flag, true);
            boolean sneak = flag == EntityFlag.SNEAKING; double[] wire = {.5, 5.62001, .5};
            deliver(check, input(1, wire, 0, 180, sneak)); deliver(check, input(2, wire, 0, 180, sneak));
            click(check); deliver(check, input(3, wire, 0, 180, sneak));
            assertEquals(1, check.flags, "Wrong-facing placement must still fail: " + flag); assertEquals(0, c.applied);
        }
    }

    @Test void embeddedPlacementKeepsItsTransactionForSneakAndEachHorizontalPose() {
        for (var flag : List.of(EntityFlag.SNEAKING, EntityFlag.CRAWLING, EntityFlag.SWIMMING, EntityFlag.GLIDING)) {
            var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
            pose(p, flag, true); boolean sneak = flag == EntityFlag.SNEAKING;
            double[] wire = {.5, 5.62001, .5};
            if (sneak) c.outline = new Box(0, 5, 2, 1, 5.5625, 3);
            deliver(check, input(1, wire, 0, 0, sneak)); deliver(check, input(2, wire, 0, 0, sneak));
            var next = input(3, wire, 0, 0, sneak);
            next.setItemUseTransaction(new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction());
            next.getInputData().add(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
            deliver(check, next);
            assertEquals(0, check.flags, flag + " " + c.logs);
            assertNotNull(next.getItemUseTransaction());
            assertTrue(next.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_INTERACTION));
            assertTrue(c.logs.stream().anyMatch(s -> s.contains("result=HIT") && s.contains("source=embedded")), c.logs.toString());
        }
    }

    @Test void touchCameraCandidatesUseTheSameCorrectedOrigin() {
        var p = new WirePlayerState(); var c = new Context(); var check = new Probe(p, c);
        pose(p, EntityFlag.SWIMMING, true); double[] wire = {.5, 5.62001, .5};
        for (int tick = 1; tick <= 2; tick++) {
            var in = input(tick, wire, 0, 0, false); in.setInputMode(InputMode.TOUCH);
            in.setInputInteractionModel(InputInteractionModel.CLASSIC); deliver(check, in);
        }
        click(check); var next = input(3, wire, 0, 0, false); next.setInputMode(InputMode.TOUCH);
        next.setInputInteractionModel(InputInteractionModel.CLASSIC); deliver(check, next);
        assertEquals(0, check.flags, c.logs.toString()); assertEquals(1, c.applied);
        assertTrue(c.logs.stream().anyMatch(s -> s.contains("selection=camera_candidates") && s.contains("y=4.420")), c.logs.toString());
    }
}
