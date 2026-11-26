package ac.navro.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Selects detection or native forwarding before installing Bedrock decoding/encryption. */
final class ConnectionRoutingHandler extends ChannelInboundHandlerAdapter {
    private final int raknetVersion;
    private final Consumer<Channel> detection;
    private final BiConsumer<Channel, InitialBedrockHandshake.Declaration> forwarding;
    private ScheduledFuture<?> timeout;

    ConnectionRoutingHandler(int raknetVersion, Consumer<Channel> detection,
                             BiConsumer<Channel, InitialBedrockHandshake.Declaration> forwarding) {
        this.raknetVersion = raknetVersion;
        this.detection = detection;
        this.forwarding = forwarding;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        timeout = ctx.executor().schedule(() -> ctx.close(), 10, TimeUnit.SECONDS);
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof RakMessage frame)) {
            ReferenceCountUtil.release(message);
            ctx.close();
            return;
        }
        try {
            var declaration = InitialBedrockHandshake.inspect(frame.content(), raknetVersion);
            if (SupportedCodecs.forProtocol(declaration.protocol()) != null) detection.accept(ctx.channel());
            else forwarding.accept(ctx.channel(), declaration);
        } catch (Exception error) {
            ReferenceCountUtil.release(message);
            System.out.println("[NavroProxy] Connection rejected: " + error.getMessage());
            ctx.close();
            return;
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(message);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) {
        if (timeout != null) timeout.cancel(false);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (timeout != null) timeout.cancel(false);
        ctx.fireChannelInactive();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { ctx.close(); }
}
