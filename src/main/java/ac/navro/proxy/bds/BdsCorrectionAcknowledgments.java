package ac.navro.proxy.bds;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import java.util.ArrayDeque;
import java.util.Queue;




public final class BdsCorrectionAcknowledgments extends ChannelInboundHandlerAdapter {
    private final Queue<Runnable> pending = new ArrayDeque<>();
    public void defer(Runnable task) { pending.add(task); }
    public void commit() {
        for (Runnable task; (task = pending.poll()) != null;) task.run();
    }
    @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (!(message instanceof BedrockBatchWrapper)) {
            context.fireChannelRead(message);
            return;
        }
        try { context.fireChannelRead(message); }
        finally { commit(); }
    }
}
