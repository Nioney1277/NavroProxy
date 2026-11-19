package ac.navro.proxy.player.data.tracker;

import ac.navro.proxy.data.ItemUseTracker;

import java.util.HashSet;
import java.util.Set;


public final class FlagTracker<I> {
    public interface Context<I> {
        ItemUseTracker<I> getItemUseTracker();
        I airItem();
    }

          
    public static final int USING_ITEM = -1;

    private final Set<Integer> flags = new HashSet<>();
    private boolean flying, wasFlying;

    public boolean isFlying() { return flying; }
    public boolean isWasFlying() { return wasFlying; }

    public void flying(boolean flying) {
        this.flying = this.wasFlying = flying;
    }

    public void setFlying(boolean flying) {
        this.wasFlying = this.flying;
        this.flying = flying;
    }

    public void clear() {
        this.flags.clear();
    }

    public void set(final Context<I> player, final Set<Integer> flags) {
        this.set(player, flags, true);
    }

    public void set(final Context<I> player, final Set<Integer> flags, boolean server) {
        boolean sneaking = this.has(1);
        boolean swimming = this.has(57);
        boolean wasUsing = this.has(USING_ITEM);

        this.clear();
        this.flags.addAll(flags);

        if (server) {
            this.set(1, sneaking);
            this.set(57, swimming);
        }

        boolean oldUsingItem = player.getItemUseTracker().getUsedItem() != player.airItem();
        if (this.has(USING_ITEM)) {
            this.set(USING_ITEM, oldUsingItem);
            if (!oldUsingItem) {
                player.getItemUseTracker().setDirtyUsing(ItemUseTracker.DirtyUsing.METADATA);
            } else {



                player.getItemUseTracker().setDirtyUsing(ItemUseTracker.DirtyUsing.NONE);
            }
        } else {
            player.getItemUseTracker().setDirtyUsing(ItemUseTracker.DirtyUsing.NONE);
        }

        player.getItemUseTracker().onMetadataUseStateChanged(wasUsing, this.has(USING_ITEM));

    }

    public void set(final int flag, boolean value) {
        if (value) {
            this.flags.add(flag);
        } else {
            this.flags.remove(flag);
        }
    }

    public boolean has(final int flag) {
        return flags.contains(flag);
    }

    public Set<Integer> cloneFlags() {
        return new HashSet<>(this.flags);
    }


    public void restoreFlags(final Set<Integer> snapshot) {
        this.flags.clear();
        this.flags.addAll(snapshot);
    }
}
