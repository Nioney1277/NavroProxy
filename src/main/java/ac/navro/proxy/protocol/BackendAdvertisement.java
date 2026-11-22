package ac.navro.proxy.protocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


public final class BackendAdvertisement extends SimpleChannelInboundHandler<RakPing> implements AutoCloseable {
    private final long guid;
    private volatile byte[] advertisement;
    private Channel queryChannel;
    private ScheduledFuture<?> polling;

    public BackendAdvertisement(long guid) { this.guid = guid; }

    public void attach(Channel server) {
        server.pipeline().addBefore("rak-server-tail-handler", "backend-advertisement", this);
    }

    public void start(EventLoopGroup group, InetSocketAddress backend) throws InterruptedException {
        queryChannel = new Bootstrap().group(group)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .handler(new SimpleChannelInboundHandler<RakPong>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, RakPong pong) {
                        if (!backend.equals(pong.getSender())) return;
                        var data = pong.getPongData();
                        byte[] bytes = new byte[data.readableBytes()];
                        data.getBytes(data.readerIndex(), bytes);
                        advertisement = bytes;
                    }
                }).bind(0).sync().channel();
        polling = queryChannel.eventLoop().scheduleAtFixedRate(
                () -> queryChannel.writeAndFlush(new RakPing(System.currentTimeMillis(), backend)),
                0, 5, TimeUnit.SECONDS);
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, RakPing ping) {
        byte[] bytes = advertisement;
        if (bytes != null) ctx.writeAndFlush(ping.reply(guid, Unpooled.wrappedBuffer(bytes)));
    }

    @Override public void close() {
        if (polling != null) polling.cancel(false);
        if (queryChannel != null) queryChannel.close();
    }
}
