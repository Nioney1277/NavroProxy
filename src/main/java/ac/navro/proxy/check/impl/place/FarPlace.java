package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.block.BlockReachUtil;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;


@CheckInfo(name="FarPlace")
public final class FarPlace extends PacketCheck {
    private final PlacementPipeline context;
    public FarPlace(WirePlayerState player,PlacementPipeline context){super(player);this.context=context;}
    @Override protected void appendViolationDetails(StringBuilder builder,int vl,String verbose){super.appendViolationDetails(builder,vl,BlockReachUtil.distanceDetail(verbose));}
    public boolean onTransaction(InventoryTransactionPacket packet,boolean cancelled) {
        if(cancelled||player.vehicleData!=null)return cancelled;
        if(isFar(packet)){fail(BlockReachUtil.describe(player,packet.getBlockPosition()));resync(packet);return true;}return false;
    }
    public void onInput(PlayerAuthInputPacket input,boolean cancelled) {
        if(cancelled||player.vehicleData!=null)return;var transaction=context.embeddedTransaction(input);
        if(isFar(transaction)){fail(BlockReachUtil.describe(player,transaction.getBlockPosition()));input.setItemUseTransaction(null);
            input.getInputData().remove(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);resync(transaction);}
    }
    private boolean isFar(InventoryTransactionPacket packet) {
        return packet!=null&&packet.getTransactionType()==InventoryTransactionType.ITEM_USE&&packet.getActionType()==0&&packet.getBlockPosition()!=null
                &&BlockReachUtil.isPlaceable(player.protocolVersion(),context.decodedClaim(packet.getItemInHand()))&&BlockReachUtil.isFar(player,packet.getBlockPosition());
    }
    private void resync(InventoryTransactionPacket packet){context.resyncFarPlacement(packet);}
}
