package ac.navro.proxy.util.block;

import org.cloudburstmc.math.vector.Vector3i;
import ac.navro.proxy.data.block.NativeBlockProperties;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.vanilla.GameType;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import java.util.function.Consumer;


public final class BlockUtil {
    private BlockUtil() { }


    public static void restoreCorrectBlock(Consumer<UpdateBlockPacket> player, Vector3i vector, BlockDefinition state) {
        restoreCorrectBlock(player, vector, state, 0);
    }


    public static void restoreCorrectBlock(Consumer<UpdateBlockPacket> player, Vector3i vector,
                                            BlockDefinition layerZero, BlockDefinition layerOne) {
        if (player == null || vector == null) return;
        restoreCorrectBlock(player, vector, layerZero, 0);
        restoreCorrectBlock(player, vector, layerOne, 1);
    }

    private static void restoreCorrectBlock(Consumer<UpdateBlockPacket> player, Vector3i vector,
                                             BlockDefinition state, int layer) {
        if (player == null || vector == null || state == null) return;
        final UpdateBlockPacket packet = new UpdateBlockPacket();
        packet.setBlockPosition(vector);
        packet.setDefinition(state);


        packet.getFlags().add(UpdateBlockPacket.Flag.NEIGHBORS);
        packet.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
        packet.getFlags().add(UpdateBlockPacket.Flag.UNUSED);
        packet.setDataLayer(layer);
        player.accept(packet);
    }

    public static boolean determineCanBreak(final GameType gameType, final NativeBlockProperties.Properties state) {
        int id = state.blockId();
        if (id == 0 || id == 10 || id == 11 || id == 8 || id == 9) {
            return false;
        }

        float destroyTime = (float) state.hardness();
        return destroyTime != -1 || gameType == GameType.CREATIVE;
    }

    public static boolean determineCanBreak(final GameType gameType, final NetworkBlockState state) {
        return determineCanBreak(gameType, NativeBlockProperties.forState(state));
    }

    public static Vector3i getBlockPosition(Vector3i blockPos, int face) {
        return switch (face) {
            case 0 -> blockPos.sub(0, 1, 0);
            case 1 -> blockPos.add(0, 1, 0);
            case 2 -> blockPos.sub(0, 0, 1);
            case 3 -> blockPos.add(0, 0, 1);
            case 4 -> blockPos.sub(1, 0, 0);
            case 5 -> blockPos.add(1, 0, 0);
            default -> blockPos;
        };
    }
}
