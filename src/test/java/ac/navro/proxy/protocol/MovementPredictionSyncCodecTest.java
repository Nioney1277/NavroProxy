package ac.navro.proxy.protocol;

import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket;
import ac.navro.proxy.nethernet.NetherNetBatchSession;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MovementPredictionSyncCodecTest {
    private static final String MASK = "9a80808090808080828080808080808004";
    private static final String SCALARS = "0000803fe17a143f0000203fb81e053e0ad7a33c0ad7233c3d0ad73e0000a04100009841";
    private static final String EXTRA = "0000803e000000bf0080f742";

    static ClientMovementPredictionSyncPacket decode(int protocol, String payload) {
        var codec = SupportedCodecs.forProtocol(protocol);
        var buffer = Unpooled.wrappedBuffer(HexFormat.of().parseHex(payload));
        try {
            var definition = codec.getPacketDefinition(MovementPredictionSyncPacket.class);
            assertEquals(322, definition.getId());
            var decoded = codec.tryDecode(codec.createHelper(), buffer, definition.getId());
            assertFalse(buffer.isReadable());
            return assertInstanceOf(ClientMovementPredictionSyncPacket.class, decoded);
        } finally {
            buffer.release();
        }
    }

    private static void roundTrip(int protocol, String payload, long expectedId, boolean flying) {
        var packet = decode(protocol, payload);
        assertEquals(Set.of(1, 3, 4, 32, 57, 114), ClientMovementPredictionSyncPacket.copyFlags(packet));
        assertEquals(1F, packet.getBoundingBox().getX());
        assertEquals(.58F, packet.getBoundingBox().getY());
        assertEquals(.625F, packet.getBoundingBox().getZ());
        assertEquals(.13F, packet.getSpeed());
        assertEquals(.02F, packet.getUnderwaterSpeed());
        assertEquals(.01F, packet.getLavaSpeed());
        assertEquals(.42F, packet.getJumpStrength());
        assertEquals(20F, packet.getHealth());
        assertEquals(19F, packet.getHunger());
        assertEquals(expectedId, packet.getUniqueEntityId());
        assertEquals(flying, packet.isFlying());
        assertEquals(protocol >= 975 ? .25F : 0F, packet.getUnknown1());
        assertEquals(protocol >= 975 ? -.5F : 0F, packet.getUnknown2());
        assertEquals(protocol >= 975 ? 123.75F : 0F, packet.getUnknown3());
        var buffer = Unpooled.buffer();
        try {
            var codec = SupportedCodecs.forProtocol(protocol);
            codec.tryEncode(codec.createHelper(), buffer, packet);
            var encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            assertEquals(payload, HexFormat.of().formatHex(encoded));
        } finally {
            buffer.release();
        }
    }

    @Test void independentNativeFixturesCoverEveryRegisteredSyncLayout() {
        roundTrip(776, MASK + SCALARS + "53", -42, false);
        for (int protocol : new int[]{786, 800, 819, 860, 898, 924, 944}) {
            roundTrip(protocol, MASK + SCALARS + "aab4de7501", 123456789, true);
        }
        for (int protocol : new int[]{975, 1001, 2168, 2169, 2192, 2193, 2225}) {
            roundTrip(protocol, MASK + SCALARS + EXTRA + "a9b4de7501", -123456789, true);
        }
    }

    @Test void unsignedActorMaskPreservesBitsBeyondBothMetadataWordsAndTheSdkEnum() {
        String payload = "80".repeat(31) + "40" + SCALARS + EXTRA + "aab4de7500";
        var packet = decode(2225, payload);
        assertEquals(Set.of(223), ClientMovementPredictionSyncPacket.copyFlags(packet));
        var codec = SupportedCodecs.forProtocol(2225);
        var buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            assertEquals(payload, HexFormat.of().formatHex(io.netty.buffer.ByteBufUtil.getBytes(buffer)));
            var snapshot = ClientMovementPredictionSyncPacket.copyFlags(packet);
            packet.captureFlags(Set.of());
            assertEquals(Set.of(223), snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(0));
        } finally {
            buffer.release();
        }
    }

    @Test void deserializeIntoReusedPacketClearsFlagsAndAbsentOptionalFields() {
        var packet = decode(2225, MASK + SCALARS + EXTRA + "aab4de7501");
        var buffer = Unpooled.wrappedBuffer(HexFormat.of().parseHex("00" + "00".repeat(36) + "0e"));
        try {
            var codec = SupportedCodecs.forProtocol(776);
            codec.getPacketDefinition(MovementPredictionSyncPacket.class).getSerializer()
                    .deserialize(buffer, codec.createHelper(), packet);
            assertFalse(buffer.isReadable());
            assertTrue(ClientMovementPredictionSyncPacket.copyFlags(packet).isEmpty());
            assertEquals(0, packet.getUnknown1());
            assertEquals(0, packet.getUnknown2());
            assertEquals(0, packet.getUnknown3());
            assertFalse(packet.isFlying());
            assertEquals(7, packet.getUniqueEntityId());
        } finally {
            buffer.release();
        }
    }

    @Test void signedIdentitySupportsBothLongExtremes() {
        String empty = "00" + "00".repeat(36);
        assertEquals(Long.MIN_VALUE, decode(776, empty + "ffffffffffffffffff01").getUniqueEntityId());
        assertEquals(Long.MAX_VALUE, decode(776, empty + "feffffffffffffffff01").getUniqueEntityId());
    }

    @Test void truncatedAndOverlongMasksCannotShiftTheRemainingFields() {
        assertThrows(RuntimeException.class, () -> decode(2225, "80".repeat(33) + "00".repeat(80)));
        assertThrows(RuntimeException.class, () -> decode(2225, MASK + SCALARS));
    }

    @Test void realNetherNetPacketBoundaryForwardsUnknownFlagsWithoutReencodingOrChangingTheActor() throws Exception {
        var packet = decode(2225, "80".repeat(31) + "40" + SCALARS + EXTRA + "8c0100");
        for (boolean detection : new boolean[]{false, true}) {
            try (var session = new NetherNetBatchSession(null, null, detection)) {
                session.process(false, HexFormat.of().parseHex("06c101000008b1"));
                session.process(true, HexFormat.of().parseHex("0c8f0101000000000000000000"));
                var start = new StartGamePacket();
                start.setRuntimeEntityId(7);
                start.setUniqueEntityId(70);
                start.setPlayerPosition(Vector3f.from(8, 64, 8));
                session.state().observeBackend(start);
                session.tick();
                var dimensions = session.state().player().dimensions();
                byte[] original = session.encode(packet);
                var result = session.process(false, original);
                if (detection) {
                    assertArrayEquals(original, result.backendbound().getLast());
                    assertEquals(Set.of(223), session.state().player().predictionSync().pending().flags());
                } else {
                    assertArrayEquals(original, result.forwarded());
                    assertNull(session.state().player().predictionSync().pending());
                }
                assertEquals(dimensions, session.state().player().dimensions());
                assertFalse(session.state().player().flying());
            }
        }
    }
}
