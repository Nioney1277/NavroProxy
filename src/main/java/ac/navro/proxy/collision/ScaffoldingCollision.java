package ac.navro.proxy.collision;

import ac.navro.proxy.prediction.live.PredictionContext;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Vec3;
import java.util.Set;


public final class ScaffoldingCollision {
    public static final int DESCEND_THROUGH_BLOCK_FLAG = 71;
    private ScaffoldingCollision() { }


    public static void prepareInput(PredictionContext player) {
        player.scaffoldingSneakDown = player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_DOWN);
        player.scaffoldingSneaking = player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAKING);
        final int oldTicks = player.scaffoldingSneakHoldTicks;
        if (player.inputMode == org.cloudburstmc.protocol.bedrock.data.InputMode.GAMEPAD
                && player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_TOGGLE_DOWN)) {
            player.scaffoldingSneakHoldTicks = oldTicks + 1;
            if (oldTicks >= 5) {
                player.scaffoldingSneakDown = true;
                player.scaffoldingSneaking = true;
            }
        } else {
            if (oldTicks >= 6) {
                player.scaffoldingSneakDown = false;
                player.scaffoldingSneaking = false;
            }
            player.scaffoldingSneakHoldTicks = 0;
        }
        player.scaffoldDescend = shouldDescend(
                player.getFlagTracker().has("OVER_DESCENDABLE_BLOCK"),
                player.inputMode, player.scaffoldingSneaking,
                player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.DESCEND_BLOCK),
                player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_TOGGLE_DOWN),
                player.scaffoldingSneakDown);
        player.getFlagTracker().set(DESCEND_THROUGH_BLOCK_FLAG, player.scaffoldDescend);
    }

    public static Vec3 applyAcceptedDescend(PredictionContext player, Vec3 velocity) {
        if (!player.getFlagTracker().has(DESCEND_THROUGH_BLOCK_FLAG)) return velocity;
        return new Vec3(velocity.x, -0.15F, velocity.z);
    }

    public static boolean shouldDescend(final PredictionContext player) {
        return player.getFlagTracker().has(DESCEND_THROUGH_BLOCK_FLAG);
    }

    static boolean shouldDescend(boolean overDescendable, org.cloudburstmc.protocol.bedrock.data.InputMode mode,
                                 boolean sneaking, Set<org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData> actions) {
        return shouldDescend(overDescendable, mode, sneaking,
                actions.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.DESCEND_BLOCK),
                actions.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_TOGGLE_DOWN),
                actions.contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.SNEAK_DOWN));
    }

    private static boolean shouldDescend(boolean overDescendable, org.cloudburstmc.protocol.bedrock.data.InputMode mode,
                                         boolean sneaking, boolean descendBlock,
                                         boolean sneakToggle, boolean sneakDown) {
        if (!overDescendable) return false;
        if (mode == org.cloudburstmc.protocol.bedrock.data.InputMode.MOUSE) return sneaking;

        if (mode == org.cloudburstmc.protocol.bedrock.data.InputMode.TOUCH) return descendBlock;
        if (mode == org.cloudburstmc.protocol.bedrock.data.InputMode.GAMEPAD) return sneaking && sneakToggle && sneakDown;
        return false;
    }
}
