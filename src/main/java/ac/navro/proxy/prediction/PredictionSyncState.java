package ac.navro.proxy.prediction;

import ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;

import java.util.Set;

public final class PredictionSyncState {
    private Report pending;
    private long sequence;

    public record Report(long sequence, long receivedAfterTick, long uniqueEntityId, Set<Integer> flags,
                         float scale, float width, float height, float speed, float underwaterSpeed,
                         float lavaSpeed, float jumpStrength, float health, float hunger,
                         float unknown1, float unknown2, float unknown3, boolean flying) {
        public Report { flags = Set.copyOf(flags); }
    }

    public boolean receive(MovementPredictionSyncPacket packet, long expectedUniqueId, long tick) {
        if (packet.getUniqueEntityId() == -1 || packet.getUniqueEntityId() != expectedUniqueId || pending != null) return false;
        var dimensions = packet.getBoundingBox();
        pending = new Report(++sequence, tick, packet.getUniqueEntityId(), ClientMovementPredictionSyncPacket.copyFlags(packet),
                dimensions.getX(), dimensions.getY(), dimensions.getZ(), packet.getSpeed(), packet.getUnderwaterSpeed(),
                packet.getLavaSpeed(), packet.getJumpStrength(), packet.getHealth(), packet.getHunger(),
                packet.getUnknown1(), packet.getUnknown2(), packet.getUnknown3(), packet.isFlying());
        return true;
    }

    public Report pending() { return pending; }

    public Report consume() {
        final Report report = pending;
        pending = null;
        return report;
    }

    public void clear() { pending = null; }
}
