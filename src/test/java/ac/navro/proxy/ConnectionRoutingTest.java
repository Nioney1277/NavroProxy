package ac.navro.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionRoutingTest {
    static byte[] declaration(int protocol, boolean wrapped) {
        boolean legacy = protocol < 554;
        ByteBuf packet = Unpooled.buffer(), batch = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(packet, legacy ? 1 : 193);
            packet.writeInt(protocol);
            if (legacy) packet.writeZero(32);
            VarInts.writeUnsignedInt(batch, packet.readableBytes()); batch.writeBytes(packet);
            byte[] data = ByteBufUtil.getBytes(batch);
            if (!legacy) {
                byte[] frame = new byte[data.length + 1]; frame[0] = (byte) 0xfe;
                System.arraycopy(data, 0, frame, 1, data.length); return frame;
            }
            var deflater = new Deflater(6, !wrapped);
            try (var output = new ByteArrayOutputStream()) {
                output.write(0xfe); deflater.setInput(data); deflater.finish(); byte[] chunk = new byte[128];
                while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk));
                return output.toByteArray();
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            finally { deflater.end(); }
        } finally { packet.release(); batch.release(); }
    }

    @Test void acceptsEveryLibraryProtocolWithoutChangingTheInitialFrame() throws Exception {
        for (int protocol : ConnectionProtocols.all()) {
            if (protocol != 2192) {
                var codec = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v" + protocol +
                        ".Bedrock_v" + protocol).getField("CODEC").get(null);
                assertNotNull(codec, "Library codec for " + protocol);
            }
            for (boolean wrapped : new boolean[]{false, true}) {
                byte[] bytes = declaration(protocol, wrapped);
                ByteBuf frame = Unpooled.wrappedBuffer(bytes);
                try {
                    int index = frame.readerIndex();
                    assertEquals(protocol, InitialBedrockHandshake.protocol(frame, protocol < 554 ? 10 : 11));
                    assertEquals(index, frame.readerIndex());
                    assertArrayEquals(bytes, ByteBufUtil.getBytes(frame));
                    var undeclaredTransport = InitialBedrockHandshake.inspect(frame, 0);
                    assertEquals(protocol, undeclaredTransport.protocol());
                    assertEquals(protocol >= 554 ? 11 : wrapped ? 9 : 10, undeclaredTransport.raknetVersion());
                } finally { frame.release(); }
            }
        }
    }

    @Test void keepsDetectionForRegisteredVersionsAndRoutesOthersOnlyOnce() {
        for (int protocol : new int[]{291, 419, 554, 712, 729, 748, 766, 818, 827, 844, 859, 2225}) {
            var selections = new ArrayList<String>(); var frames = new ArrayList<byte[]>();
            var sink = new SimpleChannelInboundHandler<RakMessage>() {
                @Override protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) {
                    frames.add(ByteBufUtil.getBytes(msg.content()));
                }
            };
            var channel = new EmbeddedChannel(new ConnectionRoutingHandler(protocol < 554 ? 10 : 11,
                    target -> { selections.add("detect"); target.pipeline().addLast(sink); },
                    (target, declaration) -> { assertEquals(protocol, declaration.protocol()); selections.add("native"); target.pipeline().addLast(sink); }));
            try {
                byte[] initial = declaration(protocol, false);
                channel.writeInbound(new RakMessage(Unpooled.wrappedBuffer(initial)));
                byte[] opaque = {(byte) 0xfe, 0, 12, 34, 56};
                channel.writeInbound(new RakMessage(Unpooled.wrappedBuffer(opaque)));
                assertEquals(List.of(SupportedCodecs.forProtocol(protocol) == null ? "native" : "detect"), selections);
                assertArrayEquals(initial, frames.get(0)); assertArrayEquals(opaque, frames.get(1));
                channel.advanceTimeBy(11, TimeUnit.SECONDS); channel.runScheduledPendingTasks();
                assertTrue(channel.isOpen(), "Routing timeout must be cancelled after selection");
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test void rejectsUnsupportedTruncatedAndOversizedDeclarationsAndClosesIdleConnections() {
        for (byte[] bytes : new byte[][]{
                declaration(290, false), declaration(2226, false), {(byte) 0xfe, 6, (byte) 0xc1, 1},
                {(byte) 0xfe, 0}, new byte[InitialBedrockHandshake.MAX_BYTES + 1]}) {
            var channel = new EmbeddedChannel(new ConnectionRoutingHandler(11,
                    target -> fail("Invalid initial packet reached detection"),
                    (target, version) -> fail("Invalid initial packet reached forwarding")));
            RakMessage frame = new RakMessage(Unpooled.wrappedBuffer(bytes));
            channel.writeInbound(frame);
            assertFalse(channel.isOpen()); assertEquals(0, frame.refCnt());
            channel.finishAndReleaseAll();
        }
        var idle = new EmbeddedChannel(new ConnectionRoutingHandler(11, target -> fail(), (target, version) -> fail()));
        idle.advanceTimeBy(11, TimeUnit.SECONDS); idle.runScheduledPendingTasks();
        assertFalse(idle.isOpen()); idle.finishAndReleaseAll();
    }

    @Test void forwardsOnlyWithNativeBackendAuthentication() {
        var privateConfig = ProxyConfig.parse(new String[]{"--backend-auth", "pmmp-ng", "--backend-signing-key", "key.pem"});
        var encrypted = ProxyConfig.parse(new String[]{"--encrypt-backend"});
        var channel = new EmbeddedChannel();
        try {
            assertThrows(IllegalArgumentException.class, () -> NativeRakNetForwarder.install(channel, privateConfig, 10, 291));
            assertThrows(IllegalArgumentException.class, () -> NativeRakNetForwarder.install(channel, encrypted, 10, 291));
        } finally { channel.finishAndReleaseAll(); }
    }
}
