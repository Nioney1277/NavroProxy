package ac.navro.proxy.state;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.player.data.tracker.FlagTracker;
import ac.navro.proxy.prediction.fluid.FluidPoseState;
import ac.navro.proxy.prediction.live.PredictionItemUse;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;


public final class MovementMetadataAdapter implements MovementMetadata.Context<ItemData> {
    private final SessionState session;
    private final WirePlayerState player;
    private final PredictionItemUse itemUse;

    public MovementMetadataAdapter(SessionState session, PredictionItemUse itemUse) {
        this.session = session;
        this.player = session.player();
        this.itemUse = itemUse;
    }

    public FlagTracker<ItemData> getFlagTracker() { return player.trackedFlags(); }
    public ItemUseTracker<ItemData> getItemUseTracker() { return itemUse.tracker(); }
    public ItemData airItem() { return ItemData.AIR; }
    public ItemData compensatedChest() { return session.inventory().armorContainer.get(1).getData(); }
    public boolean isNull(ItemData item) { return itemUse.isNull(item); }
    public int nativeItemId(ItemData item) { return itemUse.nativeItemId(item); }
    public void setServerSprinting(boolean value) { player.setServerSprinting(value); }
    public void setClientSwimming(boolean value) { player.setClientSwimming(value); }
    public boolean hasFlag(int flag) { return player.hasFlag(flag); }
    public FluidPoseState fluidPrediction() { return player.fluidPrediction(); }
    public EntityDimensions dimensions() { return player.dimensions(); }
    public Box boundingBox() { return player.boundingBox(); }
    public MovementAabbSnapshot snapshotAabb() { return player.snapshotAabb(); }
    public void restoreAabb(MovementAabbSnapshot saved, Vec3 target) { player.restoreAabb(saved, target); }
    public void applyDimensions(EntityDimensions dimensions) { player.applyDimensions(dimensions); }
    public boolean isLivePlayer() { return true; }
    public void movementSamplerPoseDimensions(EntityDimensions before, Box box, boolean horizontal) { }
    public void trapdoorSamplerPoseDimensions(EntityDimensions before, Box box, boolean horizontal) { }
    public void trace(String name, Object... values) { }
    public void stage(String name, Object... values) { }
}
