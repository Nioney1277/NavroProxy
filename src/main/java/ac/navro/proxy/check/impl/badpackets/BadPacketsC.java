package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;


@CheckInfo(name = "Bad Packet", type = "C")
public final class BadPacketsC extends PacketCheck {
    public BadPacketsC(WirePlayerState player) {
        super(player);
    }

    @Override
    public boolean onPacketReceive(BedrockPacket input, boolean cancelled) {
        if (cancelled || player.acknowledgedDead) return cancelled;

        if (input instanceof RespawnPacket
                || (input instanceof PlayerActionPacket action
                    && action.getAction() == PlayerActionType.RESPAWN)) {
            fail();
        }

        return cancelled;
    }
}