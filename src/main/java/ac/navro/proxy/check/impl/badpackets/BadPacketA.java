package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.MathUtil;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

@CheckInfo(name = "Bad Packet", type = "A")
public class BadPacketA extends PacketCheck {
    public BadPacketA(WirePlayerState player) { super(player); }

    @Override public boolean onPacketReceive(BedrockPacket input, boolean cancelled) {
        if (input instanceof PlayerAuthInputPacket packet) {
            Vector3f pos = packet.getPosition();
            Vector3f delta = packet.getDelta();
            Vector2f rot = Vector2f.from(packet.getRotation().getY(), packet.getRotation().getX());
            if (!MathUtil.isValid(pos) || !MathUtil.isValid(rot) || !MathUtil.isValid(delta)) {
                fail("pos=" + pos + ", rot=" + rot + ", delta=" + delta);
            }
        }
        return cancelled;
    }
}
