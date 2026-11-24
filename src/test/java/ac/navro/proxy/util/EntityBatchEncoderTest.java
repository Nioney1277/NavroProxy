package ac.navro.proxy.util;

import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.state.WirePlayerState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHealthPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EntityBatchEncoderTest {
    private static final class Fixture implements AutoCloseable {
        final WirePlayerState player;
        final EmbeddedChannel channel = new EmbeddedChannel();
        final EntityBatchEncoder encoder;
        Fixture(NoopCompression compression) {
            this(compression, new WirePlayerState());
        }
        Fixture(NoopCompression compression, WirePlayerState player) {
            this(compression, player, 2193);
        }
        Fixture(NoopCompression compression, WirePlayerState player, int protocol) {
            this.player = player;
            channel.pipeline().addLast(CompressionCodec.NAME,
                    new CompressionCodec(new SimpleCompressionStrategy(compression), false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
            BedrockPacketCodec codec = new BedrockPacketCodec_v3();
            codec.setCodec(ac.navro.proxy.SupportedCodecs.forProtocol(protocol));
            channel.pipeline().addLast(BedrockPacketCodec.NAME, codec);
            BedrockServerSession session = new BedrockServerSession(new BedrockPeer(channel, BedrockServerSession::new), 0);
            encoder = EntityBatchEncoder.install(session, player.getLatencyUtil());
            player.setEntityBatchEncoder(encoder);
            player.setProtocolVersion(protocol);
        }
        void write(BedrockPacket packet) {
            channel.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        }
        @Override public void close() { encoder.close(); channel.finishAndReleaseAll(); }
    }

    private static byte[] encode(BedrockPacket packet) {
        ByteBuf frame = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(frame, Bedrock_v2193.CODEC.getPacketDefinition(packet.getClass()).getId());
            Bedrock_v2193.CODEC.tryEncode(Bedrock_v2193.CODEC.createHelper(), frame, packet);
            byte[] result = new byte[frame.readableBytes()];
            frame.readBytes(result);
            return result;
        } finally { frame.release(); }
    }

    @Test void movementMetadataAppliesOnlyAfterItsPublishedClosingMarker() {
        var state = new ac.navro.proxy.SessionState();
        state.setProtocolVersion(2193); state.player().setProtocolVersion(2193);
        state.setDetectionEnabled(true); state.authInputPackets();
        var start = new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket();
        start.setRuntimeEntityId(7); start.setUniqueEntityId(70); state.player().apply(start);
        try (Fixture f = new Fixture(new NoopCompression(), state.player())) {
            var nativeMarker = new NetworkStackLatencyPacket();
            nativeMarker.setTimestamp(800_000_001L); nativeMarker.setFromServer(true);
            f.write(nativeMarker);
            var metadata = new org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket();
            metadata.setRuntimeEntityId(7);
            metadata.getMetadata().setFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SNEAKING, true);
            metadata.getMetadata().setFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SWIMMING, true);
            state.observeBackend(metadata);
            assertFalse(state.player().sneaking()); assertFalse(state.player().clientSwimming());
            assertTrue(f.player.getLatencyUtil().sentQueue().isEmpty());
            f.write(metadata); f.channel.flush();
            BedrockBatchWrapper batch = f.channel.readOutbound();
            try {
                var markers = List.copyOf(f.player.getLatencyUtil().sentQueue());
                assertEquals(2, markers.size()); assertFalse(markers.get(0).ours()); assertTrue(markers.get(1).ours());
                assertFalse(state.player().sneaking());
                assertFalse(f.player.getLatencyUtil().onResponse(markers.get(0).id()));
                assertFalse(state.player().sneaking()); assertFalse(state.player().clientSwimming());
                assertTrue(f.player.getLatencyUtil().onResponse(markers.get(1).id()));
                assertTrue(state.player().sneaking()); assertTrue(state.player().clientSwimming());
                assertFalse(state.player().predictionSwimming());
            } finally { batch.release(); }
        }
    }

    @Test void sprintAttributesWaitForTheirOwnClosingBatchAndReplaceLocalModifiers() {
        var state=new ac.navro.proxy.SessionState();state.setDetectionEnabled(true);state.authInputPackets();
        var start=new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket();start.setRuntimeEntityId(7);state.player().apply(start);
        try(Fixture f=new Fixture(new NoopCompression(),state.player())) {
            var speed=state.player().attributes().get("minecraft:movement");state.player().setPredictionSprintingAction(true);
            var foreign=new NetworkStackLatencyPacket();foreign.setTimestamp(800_000_002L);foreign.setFromServer(true);f.write(foreign);
            var sprint=new org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket();sprint.setRuntimeEntityId(7);
            sprint.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:movement",0,1024,.13F,.1F));
            state.observeBackend(sprint);assertTrue(f.player.getLatencyUtil().sentQueue().isEmpty());
            f.write(sprint);f.channel.flush();var first=(BedrockBatchWrapper)f.channel.readOutbound();
            try {
                var markers=List.copyOf(f.player.getLatencyUtil().sentQueue());assertEquals(2,markers.size());
                assertEquals(1,speed.getModifiers().size());assertFalse(f.player.getLatencyUtil().onResponse(markers.get(0).id()));
                assertEquals(1,speed.getModifiers().size());
                assertTrue(f.player.getLatencyUtil().onResponse(markers.get(1).id()));assertTrue(speed.getModifiers().isEmpty());
                state.player().setPredictionSprintingAction(false);assertEquals(.13F,speed.getValue());
            } finally {first.release();}
            var stop=new org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket();stop.setRuntimeEntityId(7);
            stop.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:movement",0,1024,.1F,.1F));
            state.observeBackend(stop);f.write(stop);f.channel.flush();var second=(BedrockBatchWrapper)f.channel.readOutbound();
            try {
                assertEquals(.13F,speed.getValue());
                var response=new NetworkStackLatencyPacket();response.setTimestamp(f.player.getLatencyUtil().sentQueue().getLast().id()*1_000_000L);
                assertTrue(NetworkLatencyPackets.onPacket(f.player,response));assertEquals(.1F,speed.getValue());
            } finally {second.release();}
        }
    }

    private static List<byte[]> frames(BedrockBatchWrapper batch) {
        List<byte[]> result = new ArrayList<>();
        ByteBuf raw = batch.getUncompressed().duplicate();
        while (raw.isReadable()) {
            int size = VarInts.readUnsignedInt(raw);
            byte[] frame = new byte[size];
            raw.readBytes(frame);
            result.add(frame);
        }
        return result;
    }

    @Test void effectPacketsWaitForTheirEncodedClosingMarkerAndLeaveMovementSpeedUntouched() {
        for (int protocol : List.of(2168, 2193)) {
            var state = new ac.navro.proxy.SessionState(); state.setProtocolVersion(protocol);
            state.setDetectionEnabled(true); state.authInputPackets();
            var start = new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket(); start.setRuntimeEntityId(7); state.player().apply(start);
            var player = state.player(); player.setPredictionSprintingAction(true);
            try (Fixture f = new Fixture(new NoopCompression(), player, protocol)) {
                var nativeMarker = new NetworkStackLatencyPacket();nativeMarker.setTimestamp(800_000_003L);nativeMarker.setFromServer(true);
                f.write(nativeMarker);
                var effect = new org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket();effect.setRuntimeEntityId(7);
                effect.setEvent(org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket.Event.ADD);
                effect.setEffectId(1);effect.setAmplifier(3);effect.setDuration(10);effect.setTick(50);
                state.observeBackend(effect);assertTrue(player.getLatencyUtil().sentQueue().isEmpty());assertFalse(player.hasEffect(1));
                f.write(effect);
                effect.setEffectId(999);effect.setAmplifier(99);effect.setDuration(999);effect.setRuntimeEntityId(99);
                f.channel.flush();f.channel.checkException();BedrockBatchWrapper batch=f.channel.readOutbound();
                try {
                    var markers=List.copyOf(player.getLatencyUtil().sentQueue());assertEquals(2,markers.size());
                    assertFalse(player.getLatencyUtil().onResponse(markers.get(0).id()));assertFalse(player.hasEffect(1));
                    assertFalse(player.getLatencyUtil().onResponse(999_999_998L));assertFalse(player.hasEffect(1));
                    var response=new NetworkStackLatencyPacket();response.setTimestamp(markers.get(1).id()*1_000_000L);
                    assertTrue(NetworkLatencyPackets.onPacket(player,response));
                    assertEquals(3,player.effect(1).amplifier());assertEquals(11,player.effect(1).duration());assertFalse(player.hasEffect(999));
                    assertEquals(.13F,player.attributes().get("minecraft:movement").getValue());
                    assertEquals(1,player.attributes().get("minecraft:movement").getModifiers().size());
                    assertEquals(3,frames(batch).size());
                } finally {batch.release();}
            }
        }
    }

    @Test void unchangedGameBytesAndNativeMarkerOrderSurviveClosingAckInjection() {
        try (Fixture f = new Fixture(new NoopCompression())) {
            SetHealthPacket update = new SetHealthPacket();
            update.setHealth(0);
            NetworkStackLatencyPacket nativeMarker = new NetworkStackLatencyPacket();
            nativeMarker.setTimestamp(900_000_001L);
            nativeMarker.setFromServer(true);
            AtomicInteger applied = new AtomicInteger();
            f.write(nativeMarker);
            f.encoder.track(update, applied::incrementAndGet);
            f.write(update);
            assertTrue(f.player.getLatencyUtil().sentQueue().isEmpty(), "unsent packets cannot be acknowledged");
            f.channel.flush();
            BedrockBatchWrapper batch = f.channel.readOutbound();
            try {
                List<byte[]> frames = frames(batch);
                assertEquals(3, frames.size());
                assertArrayEquals(encode(nativeMarker), frames.get(0));
                assertArrayEquals(encode(update), frames.get(1));
                ByteBuf closing = Unpooled.wrappedBuffer(frames.get(2));
                try {
                    int id = VarInts.readUnsignedInt(closing);
                    NetworkStackLatencyPacket decoded = (NetworkStackLatencyPacket) Bedrock_v2193.CODEC.tryDecode(
                            Bedrock_v2193.CODEC.createHelper(), closing, id);
                    List<LatencyUtil.Latency> markers = List.copyOf(f.player.getLatencyUtil().sentQueue());
                    assertEquals(2, markers.size());
                    assertFalse(markers.get(0).ours());
                    assertTrue(markers.get(1).ours());
                    assertEquals(decoded.getTimestamp(), markers.get(1).id());
                    assertEquals(0, applied.get());
                    assertFalse(f.player.getLatencyUtil().onResponse(123456789L));
                    assertEquals(0, applied.get());
                    assertFalse(f.player.getLatencyUtil().onResponse(nativeMarker.getTimestamp()));
                    assertEquals(0, applied.get(), "the earlier native ACK cannot commit the later update");
                    NetworkStackLatencyPacket reply = new NetworkStackLatencyPacket();
                    reply.setTimestamp(decoded.getTimestamp() * 1_000_000L);
                    assertTrue(NetworkLatencyPackets.onPacket(f.player, reply));
                    assertEquals(1, applied.get());
                    assertFalse(NetworkLatencyPackets.onPacket(f.player, reply));
                    assertEquals(1, applied.get());
                } finally { closing.release(); }
            } finally { batch.release(); }
        }
    }

    @Test void failedCompressionDoesNotPublishMarkerOrRunItsTask() {
        AtomicInteger compressionCalls = new AtomicInteger();
        try (Fixture f = new Fixture(new NoopCompression() {
            @Override public ByteBuf encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                compressionCalls.incrementAndGet();
                throw new IOException("compression failure");
            }
        })) {
            SetHealthPacket update = new SetHealthPacket();
            AtomicInteger applied = new AtomicInteger();
            f.encoder.track(update, applied::incrementAndGet);
            f.write(update);
            f.channel.flush();
            assertEquals(1, compressionCalls.get());
            assertTrue(f.encoder.hasPending(), "failed compression retains the acknowledgment work");
            assertTrue(f.player.getLatencyUtil().sentQueue().isEmpty());
            assertEquals(0, applied.get());
            assertNull(f.channel.readOutbound());
        }
    }

    @Test void aNativeAckOnlyBatchDoesNotGenerateAnotherClosingMarker() {
        try (Fixture f = new Fixture(new NoopCompression())) {
            NetworkStackLatencyPacket marker = new NetworkStackLatencyPacket();
            marker.setTimestamp(900_000_002L);
            marker.setFromServer(true);
            f.write(marker);
            f.channel.flush();
            BedrockBatchWrapper batch = f.channel.readOutbound();
            try {
                assertEquals(1, frames(batch).size());
                assertEquals(1, f.player.getLatencyUtil().sentQueue().size());
            } finally { batch.release(); }
        }
    }
}
