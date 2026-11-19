package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.MathUtil;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

@CheckInfo(name = "Bad Packet", type = "B")
public class BadPacketB extends PacketCheck {
    public BadPacketB(WirePlayerState player) { super(player); }

    @Override public boolean onPacketReceive(BedrockPacket input, boolean cancelled) {
        if (!(input instanceof PlayerAuthInputPacket packet)) return cancelled;
        float yaw = packet.getRotation().getY();
        float pitch = packet.getRotation().getX();
        float wrappedY = MathUtil.wrapDegrees(yaw);
        float wrappedX = MathUtil.clamp(pitch, -90, 90);
        if (wrappedY != yaw) fail("claimedYaw=" + yaw + ", wrappedYaw=" + wrappedY);
        else if (wrappedX != pitch) fail("claimedPitch=" + pitch + ", wrappedPitch=" + wrappedX);
        return cancelled;
    }
}
