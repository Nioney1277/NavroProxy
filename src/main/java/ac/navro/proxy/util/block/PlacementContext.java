package ac.navro.proxy.util.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.vanilla.GameType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;





public interface PlacementContext {
    record Cell(int x,int y,int z) {
        public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
    }
    record ItemView(int id,int damage,boolean placeable,boolean mapped,int blockId,boolean slab) { }
    record BlockView(NetworkBlockState state,int id,int damage,boolean air,boolean replaceable,
                     boolean slab,boolean sturdy,Box outline) { }
    final class UseItemData {
        public final Cell blockPos;
        public final Direction face;
        public final ItemView itemInHand;
        public final int actionType,clientInteractPrediction,triggerType,hotbarSlot;
        public final Vec3 clickPos,playerPos;
        public UseItemData(Cell blockPos,Direction face,ItemView itemInHand,int actionType,int prediction,
                           int triggerType,int hotbarSlot,Vec3 clickPos,Vec3 playerPos) {
            this.blockPos=blockPos;this.face=face;this.itemInHand=itemInHand;this.actionType=actionType;
            this.clientInteractPrediction=prediction;this.triggerType=triggerType;this.hotbarSlot=hotbarSlot;
            this.clickPos=clickPos;this.playerPos=playerPos;
        }
    }
    long epoch();
    Object levelIdentity();
    boolean available();
    long runtimeId();
    Vec3 acceptedMotion();
    GameType gameType();
    int minY();
    int maxY();
    boolean chunkLoaded(int x,int z);
    BlockView block(Cell position);
    UseItemData placementData(InventoryTransactionPacket transaction);
    InventoryTransactionPacket embeddedTransaction(PlayerAuthInputPacket input);
    void scheduleExpiry(Runnable expiry,int ticks);
    void applyPlacement(InventoryTransactionPacket transaction);
    void resyncPlacement(Cell target,Cell placed);
    void resyncSlot(int slot);
    boolean debugEnabled();
    void debug(String message);
}
