package ac.navro.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;





public record BackendProtocolProfile(
        String serverEngine,
        String vanillaVersion,
        String levelName,
        String levelId,
        int dimension,
        AuthoritativeMovementMode authoritativeMovement,
        boolean serverAuthoritativeBlockBreaking,
        boolean inventoriesServerAuthoritative,
        boolean hashedBlockNetworkIds,
        long blockRegistryChecksum,
        int rewindHistorySize,
        int serverChunkTickRange,
        boolean commandsEnabled,
        boolean texturePacksRequired
) {
    public static BackendProtocolProfile from(StartGamePacket packet) {
        return new BackendProtocolProfile(
                packet.getServerEngine(),
                packet.getVanillaVersion(),
                packet.getLevelName(String.class),
                packet.getLevelId(),
                packet.getDimensionId(),
                packet.getAuthoritativeMovementMode(),
                packet.isServerAuthoritativeBlockBreaking(),
                packet.isInventoriesServerAuthoritative(),
                packet.isBlockNetworkIdsHashed(),
                packet.getBlockRegistryChecksum(),
                packet.getRewindHistorySize(),
                packet.getServerChunkTickRange(),
                packet.isCommandsEnabled(),
                packet.isTexturePacksRequired());
    }
}
