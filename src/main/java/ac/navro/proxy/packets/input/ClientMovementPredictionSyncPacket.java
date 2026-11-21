package ac.navro.proxy.packets.input;

import ac.navro.proxy.util.EntityMetadataUtil;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;

import java.util.HashSet;
import java.util.Set;

public final class ClientMovementPredictionSyncPacket extends MovementPredictionSyncPacket {
    private Set<Integer> unknownFlags = Set.of();

    public void captureFlags(Set<Integer> flags) {
        getFlags().clear();
        var unknown = new HashSet<Integer>();
        for (int flagId : flags) {
            var flag = EntityMetadataUtil.nativeFlag(flagId);
            if (flag == null) unknown.add(flagId);
            else getFlags().add(flag);
        }
        unknownFlags = Set.copyOf(unknown);
    }

    public static Set<Integer> copyFlags(MovementPredictionSyncPacket packet) {
        var flags = packet instanceof ClientMovementPredictionSyncPacket nativePacket
                ? new HashSet<>(nativePacket.unknownFlags) : new HashSet<Integer>();
        for (var flag : packet.getFlags()) {
            int flagId = EntityMetadataUtil.nativeFlagId(flag);
            if (flagId >= 0) flags.add(flagId);
        }
        return Set.copyOf(flags);
    }
}
