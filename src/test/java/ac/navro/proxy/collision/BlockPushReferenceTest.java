package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.anticheat.util.math.Vec3;
import java.io.DataInputStream;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockPushReferenceTest {
    record SuffixCase(int facing, Box actor, Box shulker, Vec3 request, Vec3 movement, Box resultBox,
                      float ordinaryOverlap, boolean horizontal, boolean vertical, boolean onGround) { }
    static java.util.List<SuffixCase> suffixCases() throws Exception {
        var cases = new ArrayList<SuffixCase>();
        try (var in = new DataInputStream(BlockPushReferenceTest.class.getResourceAsStream("/navro-block-push-suffix.bin"))) {
            assertEquals(0x4E425332, in.readInt()); int count = in.readInt(); assertEquals(6, count);
            for (int i = 0; i < count; i++) cases.add(new SuffixCase(in.readInt(), box(in), box(in),
                    vector(in), vector(in), box(in), in.readFloat(), in.readBoolean(), in.readBoolean(), in.readBoolean()));
            assertEquals(-1, in.read());
        }
        return cases;
    }
    private static Vec3 vector(DataInputStream in) throws Exception {
        return new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
    }
    private static Box box(DataInputStream in) throws Exception {
        return new Box(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
    }
    private static void assertBits(DataInputStream in, Vec3 actual, String message) throws Exception {
        for (float value : new float[]{actual.x, actual.y, actual.z})
            assertEquals(in.readInt(), Float.floatToRawIntBits(value), message);
    }

    @Test void closestSpaceAndAllSixShulkerDirectionsMatchOriginalNavroFloatBits() throws Exception {
        try (var in = new DataInputStream(getClass().getResourceAsStream("/navro-block-push.bin"))) {
            assertEquals(0x4E425031, in.readInt()); int count = in.readInt(); assertEquals(3000, count);
            for (int i = 0; i < count; i++) {
                Box actor = box(in), shulker = box(in); Direction facing = Direction.values()[in.readInt()];
                Vec3 velocity = vector(in); int size = in.readInt(); var collisions = new ArrayList<Box>();
                for (int j = 0; j < size; j++) collisions.add(box(in));
                assertBits(in, MoveTowardsClosestSpace.apply(actor, collisions, velocity), "closest case=" + i);
                assertBits(in, ShulkerPushPhysics.calculateMovement(actor, shulker, facing, collisions), "shulker case=" + i);
            }
            assertEquals(-1, in.read());
        }
    }
}
