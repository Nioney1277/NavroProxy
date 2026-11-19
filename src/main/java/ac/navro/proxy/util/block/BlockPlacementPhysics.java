package ac.navro.proxy.util.block;

import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.math.vector.Vector3i;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NativePlacementProperties;


public final class BlockPlacementPhysics {
    private BlockPlacementPhysics() { }

    public static boolean isAirOrLiquid(int id) {
        return id == 0 || id == 8 || id == 9 || id == 10 || id == 11;
    }

    public static Vector3i target(Vector3i clickedPosition, int face, NetworkBlockState clickedBlock) {
        return target(clickedPosition, face, NativePlacementProperties.canBeReplaced(clickedBlock));
    }

    public static void predict(CompensatedWorld world, Vector3i clickedPosition, int face,
                               NetworkBlockState clickedBlock, int placedLegacyFullId) {
        world.predictLocalBlock(target(clickedPosition, face, clickedBlock), 0, placedLegacyFullId);
    }

    public static Vector3i target(Vector3i clickedPosition, int face, boolean clickedCanBeReplaced) {
        return clickedCanBeReplaced ? clickedPosition : BlockUtil.getBlockPosition(clickedPosition, face);
    }

    public static void predict(CompensatedWorld world, Vector3i clickedPosition, int face,
                               boolean clickedCanBeReplaced, int placedLegacyFullId) {
        world.predictLocalBlock(target(clickedPosition, face, clickedCanBeReplaced), 0, placedLegacyFullId);
    }
}
