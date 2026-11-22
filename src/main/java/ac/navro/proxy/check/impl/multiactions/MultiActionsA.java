package ac.navro.proxy.check.impl.multiactions;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.InventoryCheck;
import ac.navro.proxy.state.WirePlayerState;

@CheckInfo(name = "MultiActions", type = "A")
public final class MultiActionsA extends InventoryCheck {
    public MultiActionsA(WirePlayerState player) {
        super(player);
    }

    @Override
    public boolean onInventoryClick() {
        if (!player.predictionSprinting()) return false;
        fail("sprinting=true");
        return true;
    }
}
