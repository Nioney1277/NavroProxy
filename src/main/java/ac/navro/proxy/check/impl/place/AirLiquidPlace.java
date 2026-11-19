package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.util.block.BlockPlacementPhysics;
import ac.navro.proxy.util.block.BlockUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;


@CheckInfo(name="AirLiquidPlace")
public final class AirLiquidPlace extends PacketCheck {
    public enum Support { RECENT, ABSENT, UNKNOWN }
    public interface Context {
        boolean placeable(ItemData item);
        boolean validate(ItemData held,ItemData claimed);
        ItemData held();
        GameType serverGameType();
        Support support(Vector3i position);
        int clickedBlockId(Vector3i position);
        void restore(Vector3i position);
        void resendInventory();
        InventoryTransactionPacket embedded(PlayerAuthInputPacket input);
    }
    private final Context context;
    public AirLiquidPlace(WirePlayerState player,Context context){super(player);this.context=context;}
    public void onInput(PlayerAuthInputPacket input,boolean cancelled) {
        if(cancelled)return;var data=context.embedded(input);
        if(data==null||data.getTransactionType()!=InventoryTransactionType.ITEM_USE||data.getActionType()!=0||!validPlacement(data))return;
        var held=context.held();if(!context.validate(held,data.getItemInHand()))return;
        if(reject(data,held,context.clickedBlockId(data.getBlockPosition()))) {
            input.setItemUseTransaction(null);input.getInputData().remove(PlayerAuthInputData.PERFORM_ITEM_INTERACTION);
        }
    }
    public boolean reject(InventoryTransactionPacket data,ItemData held,int clickedId) {
        if(!validPlacement(data)||!context.placeable(held)||player.isExempted()||context.serverGameType()==GameType.CREATIVE)return false;
        var pos=data.getBlockPosition();var support=context.support(pos);
        if(support==Support.RECENT||!BlockPlacementPhysics.isAirOrLiquid(clickedId))return false;
        if(support==Support.UNKNOWN)return false;
        fail();context.restore(pos);context.restore(BlockUtil.getBlockPosition(pos,data.getBlockFace()));player.tickSinceBlockResync=5;
        context.resendInventory();return true;
    }
    private boolean validPlacement(InventoryTransactionPacket data) {
        return data.getClientInteractPrediction()!=null&&data.getClientInteractPrediction().ordinal()!=0&&data.getBlockPosition()!=null
                &&data.getBlockFace()>=0&&data.getBlockFace()<=5&&context.placeable(data.getItemInHand());
    }
}
