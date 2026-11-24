package ac.navro.proxy.protocol;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BackendAdvertisementTest {
    private static byte[] ping() {
        return ByteBuffer.allocate(33).put((byte)1).putLong(123)
                .put(java.util.HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678"))
                .putLong(456).array();
    }

    @Test void actualRaknetDiscoveryReturnsTheBackendsNameDescriptionAndCountsUnchanged() throws Exception {
        verifyAdvertisement("MCPE;原服务器 §a测试;2225;1.26.63;3;27;456;原简介;Creative;1;19133;19133;0;");
    }

    @Test void emptyBackendNameAndDescriptionStayEmpty() throws Exception {
        verifyAdvertisement("MCPE;;2225;1.26.63;3;27;456;;Creative;1;19133;19133;0;");
    }

    private void verifyAdvertisement(String value) throws Exception {
        byte[] advertised = value.getBytes(StandardCharsets.UTF_8);
        var group = new NioEventLoopGroup(1);
        var backend = new ServerBootstrap().group(group)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer(advertised))
                .childHandler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0).sync().channel();
        try (var mirror = new BackendAdvertisement(789)) {
            var frontend = new ServerBootstrap().group(group)
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                    .option(RakChannelOption.RAK_GUID, 789L)
                    .option(RakChannelOption.RAK_HANDLE_PING, true)
                    .childHandler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0).sync().channel();
            try (var socket = new DatagramSocket()) {
                mirror.attach(frontend);
                mirror.start(group, (InetSocketAddress)backend.localAddress());
                socket.setSoTimeout(200);
                byte[] request = ping();
                DatagramPacket response = new DatagramPacket(new byte[4096], 4096);
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (true) {
                    socket.send(new DatagramPacket(request, request.length, frontend.localAddress()));
                    try { socket.receive(response); break; }
                    catch (SocketTimeoutException e) { if (System.nanoTime() >= deadline) throw e; }
                }
                var data = ByteBuffer.wrap(response.getData(), 0, response.getLength());
                assertEquals(0x1c, data.get() & 255);
                assertEquals(123, data.getLong());
                assertEquals(789, data.getLong());
                data.position(33);
                int length = Short.toUnsignedInt(data.getShort());
                byte[] actual = new byte[length]; data.get(actual);
                assertArrayEquals(advertised, actual);
            } finally { frontend.close().sync(); }
        } finally {
            backend.close().sync();
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
        }
    }
}
