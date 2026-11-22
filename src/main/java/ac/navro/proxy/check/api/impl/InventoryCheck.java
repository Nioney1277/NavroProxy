package ac.navro.proxy.check.api.impl;

import ac.navro.proxy.check.api.Check;
import ac.navro.proxy.state.WirePlayerState;

public abstract class InventoryCheck extends Check {
    protected InventoryCheck(WirePlayerState player) {
        super(player);
    }

    public abstract boolean onInventoryClick();
}
