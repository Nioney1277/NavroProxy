package ac.navro.proxy.check.impl.multiactions;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.InventoryCheck;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

@CheckInfo(name = "MultiActions", type = "B")
public final class MultiActionsB extends InventoryCheck {
    public MultiActionsB(WirePlayerState player) {
        super(player);
    }

    @Override
    public boolean onInventoryClick() {
        var motion = player.rawInputMotion();
        boolean moving = motion != null && motion.finite() && (motion.x() != 0 || motion.y() != 0);
        for (PlayerAuthInputData input : player.inputData()) {
            moving |= switch (input) {
                case UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT -> true;
                default -> false;
            };
        }
        if (moving) fail("input=true");
        return moving;
    }
}
