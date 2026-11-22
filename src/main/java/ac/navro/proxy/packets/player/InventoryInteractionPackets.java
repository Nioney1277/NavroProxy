package ac.navro.proxy.packets.player;

import ac.navro.proxy.check.api.impl.InventoryCheck;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;

public final class InventoryInteractionPackets {
    private final WirePlayerState player;
    private boolean open;
    private int window = -1;
    private boolean openingGrace;
    private boolean openingAcknowledged;
    private long revision;

    public InventoryInteractionPackets(WirePlayerState player) {
        this.player = player;
    }

    public boolean onPacketReceive(BedrockPacket packet, boolean checked) {
        boolean rejected = false;
        if (packet instanceof InteractPacket interact && interact.getAction() == InteractPacket.Action.OPEN_INVENTORY) {
            open = true;
            window = 0;
        }
        if (checked && !openingGrace && isInventoryClick(packet)) {
            for (var check : player.getCheckHolder().values()) {
                if (check instanceof InventoryCheck inventoryCheck && player.checkEnabled(check.getClass())) {
                    rejected |= inventoryCheck.onInventoryClick();
                }
            }
        }
        if (packet instanceof PlayerAuthInputPacket && openingAcknowledged) openingGrace = false;
        if (packet instanceof ContainerClosePacket close && (close.getId() == -1 || close.getId() == window)) {
            reset();
        }
        return !rejected;
    }

    public void onPacketSend(BedrockPacket packet) {
        if (packet instanceof StartGamePacket || packet instanceof ChangeDimensionPacket
                || packet instanceof RespawnPacket respawn && respawn.getState() == RespawnPacket.State.SERVER_SEARCHING) {
            reset();
        } else if (packet instanceof ContainerOpenPacket container) {
            long current = ++revision;
            open = true;
            window = container.getId();
            openingGrace = true;
            openingAcknowledged = false;
            player.queueAcknowledgment(packet, () -> {
                if (revision == current) openingAcknowledged = true;
            });
        } else if (packet instanceof ContainerClosePacket close && (close.getId() == -1 || close.getId() == window)) {
            long current = revision;
            player.queueAcknowledgment(packet, () -> {
                if (revision == current) reset();
            });
        }
    }

    private void reset() {
        revision++;
        open = false;
        window = -1;
        openingGrace = false;
        openingAcknowledged = false;
    }

    private boolean isInventoryClick(BedrockPacket packet) {
        if (packet instanceof ItemStackRequestPacket requests) {
            return requests.getRequests().stream().anyMatch(this::isInventoryClick);
        }
        if (packet instanceof PlayerAuthInputPacket input) {
            return input.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST)
                    && isInventoryClick(input.getItemStackRequest());
        }
        if (packet instanceof InventoryTransactionPacket transaction) {
            if (transaction.getTransactionType() != InventoryTransactionType.NORMAL) return false;
            boolean containerAction = false;
            boolean worldAction = false;
            for (var action : transaction.getActions()) {
                if (action == null || action.getSource() == null) continue;
                InventorySource source = action.getSource();
                if (source.getType() == InventorySource.Type.WORLD_INTERACTION
                        && source.getFlag() != InventorySource.Flag.DROP_ITEM) return false;
                containerAction |= source.getType() == InventorySource.Type.CONTAINER
                        || source.getType() == InventorySource.Type.UNTRACKED_INTERACTION_UI
                        || source.getType() == InventorySource.Type.CREATIVE;
                worldAction |= source.getType() == InventorySource.Type.WORLD_INTERACTION;
            }
            return containerAction && (open || !worldAction);
        }
        return false;
    }

    private boolean isInventoryClick(ItemStackRequest request) {
        if (request == null || request.getActions() == null) return false;
        for (ItemStackRequestAction action : request.getActions()) {
            if (action == null) continue;
            if (action instanceof TransferItemStackRequestAction transfer
                    && (open || cursor(transfer.getSource()) || cursor(transfer.getDestination()))) return true;
            if (action instanceof SwapAction swap
                    && (open || cursor(swap.getSource()) || cursor(swap.getDestination()))) return true;
            if (action instanceof DropAction drop && (open || cursor(drop.getSource()))) return true;
            if (action instanceof DestroyAction destroy && (open || cursor(destroy.getSource()))) return true;
            if (switch (action.getType()) {
                case CRAFT_RECIPE, CRAFT_RECIPE_AUTO, CRAFT_CREATIVE, CRAFT_RECIPE_OPTIONAL,
                        CRAFT_REPAIR_AND_DISENCHANT, CRAFT_LOOM, LAB_TABLE_COMBINE, BEACON_PAYMENT -> true;
                default -> false;
            }) return true;
        }
        return false;
    }

    private static boolean cursor(ItemStackRequestSlotData slot) {
        return slot != null && (slot.getContainer() == ContainerSlotType.CURSOR
                || slot.getContainerName() != null && slot.getContainerName().getContainer() == ContainerSlotType.CURSOR);
    }
}
