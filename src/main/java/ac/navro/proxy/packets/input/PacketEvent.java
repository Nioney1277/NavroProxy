package ac.navro.proxy.packets.input;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;


public final class PacketEvent {
    private final BedrockPacket packet;
    private boolean cancelled;
    public PacketEvent(BedrockPacket packet) { this.packet=java.util.Objects.requireNonNull(packet); }
    public BedrockPacket getPacket() { return packet; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled=cancelled; }
}
