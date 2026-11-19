package ac.navro.proxy.util.block;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;


public final class BlockActionText {
    private BlockActionText() { }
    public static String name(PlayerActionType action) {
        if (action == null) return "null";
        var nativeAction = ac.navro.proxy.data.vanilla.PlayerActionType.fromOrNull(action.ordinal());
        return nativeAction == null ? action.toString() : nativeAction.toString();
    }
    public static String position(Vector3i position) {
        return position == null ? "null" : "BlockPosition(level=" + ",x=" + position.getX()
                + ",y=" + position.getY() + ",z=" + position.getZ() + ')';
    }
    public static String position(Vector3f position) {
        return position == null ? "null" : "Vector3(x=" + position.getX() + ",y=" + position.getY()
                + ",z=" + position.getZ() + ')';
    }
    public static String action(PlayerBlockActionData action) {
        return action == null ? "null" : "PlayerBlockActionData(action=" + name(action.getAction())
                + ", position=" + position(action.getBlockPosition()) + ", facing=" + action.getFace() + ')';
    }
}
