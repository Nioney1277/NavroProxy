package ac.navro.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


final class NativeRakNetForwarder extends ChannelInboundHandlerAdapter {
    private static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_QUEUED_PACKETS = 128;
    private final Channel frontend;
    private final Queue<RakMessage> pending = new ArrayDeque<>();
    private Channel backend;
    private ScheduledFuture<?> timeout;
    private int queuedBytes;
    private boolean ready;

    private NativeRakNetForwarder(Channel frontend) { this.frontend = frontend; }

    static void install(Channel frontend, ProxyConfig config, int raknetVersion, int protocol) {
        if (config.pmmpNgBackendAuth() || config.encryptBackend() || config.floodgateForwardingKey() != null)
            throw new IllegalArgumentException("Protocol " + protocol +
                    " uses native forwarding and requires backend-auth: native with backend-encryption: false");
        var relay = new NativeRakNetForwarder(frontend);
        frontend.pipeline().addLast("navro-native-forwarding", relay);
        relay.connect(config, raknetVersion);
        System.out.println("[NavroProxy] Native forwarding protocol=" + protocol +
                " client=" + frontend.remoteAddress() + "; player authentication is handled by the backend");
    }

    private void connect(ProxyConfig config, int raknetVersion) {
        timeout = frontend.eventLoop().schedule(() -> disconnect(frontend), 10, TimeUnit.SECONDS);
        var future = new Bootstrap().group(frontend.eventLoop())
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, raknetVersion)
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        backend = channel;
                        channel.pipeline().addLast("navro-native-return", new ChannelInboundHandlerAdapter() {
                            @Override public void channelActive(ChannelHandlerContext ctx) {
                                if (!frontend.isActive()) { ctx.close(); return; }
                                ready = true;
                                timeout.cancel(false);
                                for (RakMessage message; (message = pending.poll()) != null;) ctx.write(message);
                                queuedBytes = 0;
                                ctx.flush();
                            }
                            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                forward(frontend, message);
                            }
                            @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                                frontend.config().setAutoRead(ctx.channel().isWritable());
                            }
                            @Override public void channelInactive(ChannelHandlerContext ctx) { disconnect(frontend); }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
                        });
                    }
                }).connect(new InetSocketAddress(config.backendHost(), config.backendPort()));
        future.addListener(result -> { if (!result.isSuccess() || !frontend.isOpen()) {
            if (!result.isSuccess()) System.out.println("[NavroProxy] Native backend connection failed: " + result.cause());
            future.channel().close(); disconnect(frontend);
        }});
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof RakMessage frame)) {
            ReferenceCountUtil.release(message); ctx.close(); return;
        }
        if (ready) { forward(backend, message); return; }
        int size = frame.content().readableBytes();
        if (pending.size() >= MAX_QUEUED_PACKETS || size > MAX_QUEUED_BYTES - queuedBytes) {
            frame.release(); ctx.close(); return;
        }
        pending.add(frame);
        queuedBytes += size;
    }

    private static void forward(Channel target, Object message) {
        if (!(message instanceof RakMessage) || !target.isActive()) {
            ReferenceCountUtil.release(message); return;
        }
        target.writeAndFlush(message).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private static void disconnect(Channel channel) {
        if (channel.isActive() && channel instanceof RakChannel rak) {
            var session = rak.rakPipeline().get(RakSessionCodec.class);
            if (session != null && !session.isClosed()) { session.disconnect(); return; }
        }
        channel.close();
    }

    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (backend != null) backend.config().setAutoRead(frontend.isWritable());
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (timeout != null) timeout.cancel(false);
        if (backend != null) disconnect(backend);
        for (RakMessage message; (message = pending.poll()) != null;) message.release();
        queuedBytes = 0;
        ctx.fireChannelInactive();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
}
