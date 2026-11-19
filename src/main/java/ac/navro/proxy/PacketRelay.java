package ac.navro.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;


final class PacketRelay extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<BedrockPacketWrapper> CURRENT = AttributeKey.valueOf("navro.currentWirePacket");

    static void install(Channel channel) {
        channel.pipeline().addBefore(BedrockPeer.NAME, "navro-wire-relay", new PacketRelay());
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof BedrockPacketWrapper wrapper)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ctx.channel().attr(CURRENT).set(wrapper);
        try {
            ctx.fireChannelRead(msg);
        } finally {
            ctx.channel().attr(CURRENT).set(null);
        }
    }

    static void forward(BedrockSession source, BedrockSession destination, BedrockPacket packet) {
        var wrapper = source.getPeer().getChannel().attr(CURRENT).get();
        if (wrapper != null && wrapper.getPacket() == packet) {
            destination.getPeer().sendPacket(wrapper.retain());
        } else {
            destination.sendPacket(ReferenceCountUtil.retain(packet));
        }
    }
}
