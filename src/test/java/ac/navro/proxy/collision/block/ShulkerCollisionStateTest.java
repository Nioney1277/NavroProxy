package ac.navro.proxy.collision.block;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ShulkerCollisionStateTest {
    @Test void everyFloatBitMatchesOriginalNavroForFacingChangesAnimationReversalsAndLargeCoordinates() throws Exception {
        int compared = 0;
        try (var input = new DataInputStream(getClass().getResourceAsStream("/navro-shulker-frames.bin"))) {
            assertEquals(0x4E534831, input.readInt());
            int cases = input.readInt(), ticks = input.readInt();
            assertEquals(27, cases); assertEquals(72, ticks);
            for (int c = 0; c < cases; c++) {
                Vector3i position = Vector3i.from(input.readInt(), input.readInt(), input.readInt());
                int facing = input.readInt();
                var actor = new ShulkerCollisionState(position, NbtMap.builder().putInt("facing", facing).build());
                for (int tick = 0; tick < ticks; tick++) {
                    actor.setOpenCount(tick < 26 ? 1 : tick < 50 ? 0 : tick < 57 ? 2 : -1);
                    if (tick == 12) actor.load(NbtMap.builder().putInt("facing", facing + 1).build());
                    if (tick == 41) actor.load(NbtMap.EMPTY);
                    actor.tick();
                    var frame = actor.snapshot(); var box = frame.shape();
                    assertEquals(position.getX(), frame.x()); assertEquals(position.getY(), frame.y());
                    assertEquals(position.getZ(), frame.z());
                    assertEquals(input.readInt(), frame.facing());
                    for (float actual : new float[]{frame.openness(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ})
                        assertEquals(input.readInt(), Float.floatToRawIntBits(actual), "case=" + c + " tick=" + tick);
                    compared++;
                }
            }
            assertEquals(-1, input.read());
        }
        assertEquals(1944, compared);
    }

    @Test void cachedShapePrecedesAnimationAndRepeatedQueriesDoNotAdvanceState() {
        var actor = new ShulkerCollisionState(Vector3i.ZERO, NbtMap.builder().putByte("facing", (byte) 5).build());
        actor.setOpenCount(1); actor.tick();
        var first = actor.snapshot();
        assertEquals(0.05F, first.openness());
        assertEquals(1, first.shape().maxX);
        for (int i = 0; i < 100; i++) assertSame(first.shape(), actor.shape());
        actor.tick();
        assertTrue(actor.shape().maxX > 1);
        assertEquals(1, first.shape().maxX);
        actor.setOpenCount(0); actor.tick(); actor.tick();
        assertEquals(0, actor.snapshot().openness());
        // Default is up; an out-of-range facing is down; a missing field retains the last facing.
        var defaults = new ShulkerCollisionState(Vector3i.ZERO, NbtMap.EMPTY);
        assertEquals(1, defaults.snapshot().facing());
        defaults.load(NbtMap.builder().putByte("facing", (byte) 7).build());
        assertEquals(0, defaults.snapshot().facing());
        defaults.load(NbtMap.EMPTY);
        assertEquals(0, defaults.snapshot().facing());
    }
}
