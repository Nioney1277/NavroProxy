package ac.navro.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeRakNetForwarderTest {
    @Test void realRakNetConnectionsPreserveLegacyLoginAndOpaqueTrafficBothWays() throws Exception {
        var group = new NioEventLoopGroup(2);
        Channel backendServer = null, proxyServer = null;
        var backendPeers = new LinkedBlockingQueue<Channel>();
        var detectedProtocols = new LinkedBlockingQueue<Integer>();
        try {
            backendServer = server(group, new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    backendPeers.add(channel);
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<RakMessage>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) {
                            ctx.writeAndFlush(msg.retain());
                        }
                    });
                }
            });
            int port = ((InetSocketAddress) backendServer.localAddress()).getPort();
            var config = ProxyConfig.parse(new String[]{"--backend", "127.0.0.1:" + port});
            proxyServer = server(group, new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    int rak = channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
                    channel.pipeline().addLast(new ConnectionRoutingHandler(rak,
                            target -> target.pipeline().addLast(new BedrockServerInitializer() {
                                @Override protected void preInitChannel(Channel selected) throws Exception {
                                    selected.config().setOption(RakChannelOption.RAK_PROTOCOL_VERSION, 11);
                                    super.preInitChannel(selected);
                                }
                                @Override protected void initSession(BedrockServerSession session) {
                                    session.setCodec(SupportedCodecs.fallback(2225));
                                    session.setPacketHandler(new BedrockPacketHandler() {
                                        @Override public PacketSignal handle(RequestNetworkSettingsPacket packet) {
                                            detectedProtocols.add(packet.getProtocolVersion());
                                            session.sendPacketImmediately(packet);
                                            return PacketSignal.HANDLED;
                                        }
                                    });
                                }
                            }),
                            (target, declaration) -> NativeRakNetForwarder.install(
                                    target, config, declaration.raknetVersion(), declaration.protocol())));
                }
            });
            for (int protocol : new int[]{291, 419, 554, 712, 748, 729, 2225}) {
                var received = new LinkedBlockingQueue<byte[]>();
                Channel client = new Bootstrap().group(group)
                        .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                        .option(RakChannelOption.RAK_PROTOCOL_VERSION, protocol < 554 ? 10 : 11)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel channel) {
                                channel.pipeline().addLast(new SimpleChannelInboundHandler<RakMessage>() {
                                    @Override protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) {
                                        received.add(ByteBufUtil.getBytes(msg.content()));
                                    }
                                });
                            }
                        }).connect(proxyServer.localAddress()).sync().channel();
                try {
                    byte[] login = ConnectionRoutingTest.declaration(protocol, protocol == 291);
                    client.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(login))).sync();
                    byte[] reply = received.poll(10, TimeUnit.SECONDS);
                    assertArrayEquals(login, reply, "Initial login/settings for " + protocol);
                    if (SupportedCodecs.forProtocol(protocol) != null) {
                        assertEquals(protocol, detectedProtocols.poll(10, TimeUnit.SECONDS));
                        assertTrue(backendPeers.isEmpty(), "Detection must not enter native forwarding");
                        continue;
                    }
                    byte[] encrypted = {(byte) 0xfe, (byte) 0xff, 1, 2, 3, 0, 0, (byte) 0x80};
                    client.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(encrypted))).sync();
                    assertArrayEquals(encrypted, received.poll(10, TimeUnit.SECONDS), "Opaque encrypted payload for " + protocol);
                    Channel peer = backendPeers.poll(10, TimeUnit.SECONDS);
                    assertNotNull(peer);
                    ((RakChannel)peer).rakPipeline().get(
                            org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec.class).disconnect();
                    assertTrue(client.closeFuture().await(10, TimeUnit.SECONDS), "Backend close must close frontend");
                } finally { client.close().syncUninterruptibly(); }
            }
        } finally {
            if (proxyServer != null) proxyServer.close().syncUninterruptibly();
            if (backendServer != null) backendServer.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static Channel server(EventLoopGroup group, ChannelHandler initializer) throws InterruptedException {
        return new ServerBootstrap().group(group).channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{9, 10, 11})
                .option(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.INVALID)
                .childHandler(initializer).bind("127.0.0.1", 0).sync().channel();
    }
}
