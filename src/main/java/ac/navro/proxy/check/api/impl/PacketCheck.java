package ac.navro.proxy.check.api.impl;

import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;


public class PacketCheck extends Check {
    public PacketCheck(WirePlayerState player) { super(player); }
    public boolean onPacketReceive(BedrockPacket packet, boolean cancelled) { return cancelled; }
    public void onPacketSend(BedrockPacket packet) { }
}
