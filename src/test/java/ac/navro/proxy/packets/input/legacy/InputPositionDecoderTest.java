package ac.navro.proxy.packets.input.legacy;

import org.cloudburstmc.math.vector.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputPositionDecoderTest {
    private static final float OFFSET = 1.62001F;

    @Test void capturedPreSpawnEchoKeepsItsFootY() {

        final boolean movement = InputPositionDecoder.usesMovementCoordinates(false, false);
        final var foot = InputPositionDecoder.decode(Vector3f.from(143.5F, 65.0F, 105.5F), OFFSET, movement);
        assertFalse(movement);
        assertEquals(65.0F, foot.y);
        assertEquals(143.5F, foot.x);
        assertEquals(105.5F, foot.z);
    }

    @Test void normalMovementAfterSpawnSubtractsThePacketOffset() {
        final boolean movement = InputPositionDecoder.usesMovementCoordinates(true, false);
        final var foot = InputPositionDecoder.decode(Vector3f.from(143.5F, 66.62001F, 105.5F), OFFSET, movement);
        assertTrue(movement);
        assertEquals(65.0F, foot.y, 1.0E-5F);
    }

    @Test void earlyExplicitTeleportResponseStillUsesPacketCoordinates() {
        final boolean movement = InputPositionDecoder.usesMovementCoordinates(false, true);
        assertTrue(movement);
        final var foot = InputPositionDecoder.decode(Vector3f.from(143.5F, 66.62001F, 105.5F), OFFSET, movement);
        assertEquals(65.0F, foot.y, 1.0E-5F);
    }

    @Test void vehicleMovementKeepsItsOwnOffsetAfterSpawn() {
        final var foot = InputPositionDecoder.decode(Vector3f.from(8, 64.375F, 8), 0.375F,
                InputPositionDecoder.usesMovementCoordinates(true, false));
        assertEquals(64.0F, foot.y);
    }
}
