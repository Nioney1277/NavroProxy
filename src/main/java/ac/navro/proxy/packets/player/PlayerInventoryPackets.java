package ac.navro.proxy.packets.player;

import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.compensated.UiInventorySlots;
import ac.navro.proxy.compensated.cache.container.ContainerCache;
import ac.navro.proxy.compensated.cache.container.impl.TradeContainerCache;
import ac.navro.proxy.data.inventory.ItemCache;
import ac.navro.proxy.inventory.InventoryType;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.NetworkRecipeData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public final class PlayerInventoryPackets {
    private PlayerInventoryPackets() { }

    public enum TransactionResult { UNHANDLED, ACCEPTED, REJECTED, EXCEPTION }


    public static TransactionResult onTransactionReceive(ac.navro.proxy.SessionState state,
            org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket packet) {
        try {
            boolean block=packet.getTransactionType()==org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE&&packet.getActionType()==0;
            boolean accepted=block?state.placementPipeline().handleTransaction(packet):state.transactionValidator().handle(packet,state.authInputPackets().itemUse());
            return accepted?TransactionResult.ACCEPTED:TransactionResult.REJECTED;
        } catch (Exception exception) {


            var trace=state.player().worldDiagnostics();
            if(trace!=null)trace.event("inventory_transaction_exception",
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                            "errorClass",exception.getClass().getName(),"error",exception.getMessage()));
            return TransactionResult.EXCEPTION;
        }
    }

    public static void onPacketReceive(WirePlayerState player, CompensatedInventory inventory, BedrockPacket input) {
        onPacketReceive(player, inventory, input, item -> { });
    }

    public static void onPacketReceive(WirePlayerState player, CompensatedInventory inventory, BedrockPacket input,
                                       java.util.function.Consumer<ItemData> onHeldItemChanged) {
        if (input instanceof ContainerClosePacket packet) {
            if (inventory.openContainer == null) return;
            if (packet.getId() != inventory.openContainer.getId() && packet.getId() != -1) return;
            inventory.openContainer = null;
        }
        if (input instanceof MobEquipmentPacket packet) {
            int newSlot = packet.getHotbarSlot();
            if (packet.getRuntimeEntityId() != player.runtimeId()) return;
            if (newSlot < 0 || newSlot > 8 || packet.getContainerId() != 0 || inventory.heldItemSlot == newSlot) return;
            inventory.heldItemSlot = newSlot;
            onHeldItemChanged.accept(inventory.inventoryContainer.getHeldItemData());
        }
    }

    public static void onPacketSend(WirePlayerState player, CompensatedInventory inventory, BedrockPacket input) {
        if (input instanceof CreativeContentPacket packet) {
            List<CreativeItemData> entries = new ArrayList<>();
            for (CreativeItemData item : packet.getContents()) {
                entries.add(new CreativeItemData(ItemUtil.copy(item.getItem()), item.getNetId(), item.getGroupId()));
            }
            player.queueAcknowledgment(packet, () -> {
                inventory.getCreativeData().clear();
                for (CreativeItemData entry : entries) inventory.getCreativeData().put(entry.getNetId(), entry.getItem());
            });
        }
        if (input instanceof CraftingDataPacket packet) {
            List<RecipeData> recipes = List.copyOf(packet.getCraftingData());
            var mixes = List.copyOf(packet.getPotionMixData());
            player.queueAcknowledgment(packet, () -> {
                inventory.getCraftingData().clear();
                for (RecipeData recipe : recipes) {
                    switch (recipe.getType()) {
                        case MULTI, SHAPED, SHAPELESS, SMITHING_TRANSFORM, SMITHING_TRIM -> {
                            if (recipe instanceof NetworkRecipeData identified) {
                                inventory.getCraftingData().put(identified.getNetId(), recipe);
                            }
                        }
                        default -> { }
                    }
                }
                inventory.setPotionMixData(new ArrayList<>(mixes));
            });
        }
        if (input instanceof ContainerOpenPacket packet) {
            InventoryType type = UiInventorySlots.windowType(packet.getType());
            if (type == null) return;
            byte windowId = packet.getId();
            var position = packet.getBlockPosition();
            long entityId = packet.getUniqueEntityId();
            player.queueAcknowledgment(packet, () -> {
                ContainerCache existing = inventory.getContainer(windowId);
                if (existing == inventory.openContainer
                        && existing != inventory.inventoryContainer
                        && existing != inventory.armorContainer
                        && existing != inventory.offhandContainer
                        && existing != inventory.hudContainer) existing = null;
                inventory.openContainer = Objects.requireNonNullElseGet(existing,
                        () -> new ContainerCache(inventory, windowId, type, position, entityId));
            });
        }
        if (input instanceof ContainerClosePacket packet) {
            byte windowId = packet.getId();
            player.queueAcknowledgment(packet, () -> {
                var current = inventory.openContainer;
                if (current != null && (windowId == -1 || current.getId() == windowId)) {
                    inventory.openContainer = null;
                }
            });
        }
        if (input instanceof InventorySlotPacket packet) {
            int containerId = packet.getContainerId(), slot = packet.getSlot();
            ItemData item = ItemUtil.copy(packet.getItem()), storageItem = ItemUtil.copy(packet.getStorageItem());
            player.queueAcknowledgment(packet, () -> {
                if (containerId == 125) {
                    ItemCache cache = bundleCacheForStorage(inventory, storageItem);
                    if (cache == null) return;
                    ItemCache[] contents = cache.getBundle().getContents();
                    if (slot < 0 || slot >= contents.length) return;
                    contents[slot] = ItemCache.build(inventory, item);
                    return;
                }
                if (containerId == ContainerCache.UI) {
                    UiInventorySlots.apply(inventory, slot, item);
                    return;
                }
                ContainerCache container = inventory.getContainer((byte) containerId);
                if (container == null || slot < 0 || slot >= container.getContainerSize()) return;
                container.set(slot, item);
            });
        }
        if (input instanceof UpdateTradePacket packet) {
            InventoryType type = packet.getContainerType() == null ? null
                    : InventoryType.from(packet.getContainerType().getId());
            if (packet.getPlayerUniqueEntityId() != player.runtimeId() || type != InventoryType.TRADING) return;
            var offers = ItemUtil.copyTag(packet.getOffers());
            byte windowId = (byte) packet.getContainerId();
            long traderId = packet.getTraderUniqueEntityId();
            player.queueAcknowledgment(packet, () -> {
                try {
                    inventory.openContainer = new TradeContainerCache(inventory, offers, windowId, type,
                            org.cloudburstmc.math.vector.Vector3i.ZERO, traderId);
                } catch (Exception ignored) { }
            });
        }
        if (input instanceof InventoryContentPacket packet) {
            int containerId = packet.getContainerId();
            ItemData storageItem = ItemUtil.copy(packet.getStorageItem());
            List<ItemData> items = packet.getContents() == null ? null
                    : packet.getContents().stream().map(ItemUtil::copy).toList();
            player.queueAcknowledgment(packet, () -> {
                if (items == null) return;
                if (containerId == 125) {
                    ItemCache cache = bundleCacheForStorage(inventory, storageItem);
                    if (cache == null) return;
                    ItemCache[] contents = cache.getBundle().getContents();
                    int length = Math.min(items.size(), contents.length);
                    for (int i = 0; i < length; i++) contents[i] = ItemCache.build(inventory, items.get(i));
                    return;
                }
                if (containerId == ContainerCache.UI) {
                    for (int slot = 0; slot < items.size(); slot++) UiInventorySlots.apply(inventory, slot, items.get(slot));
                    return;
                }
                ContainerCache container = inventory.getContainer((byte) containerId);
                if (container == null) return;
                ItemCache[] contents = container.getContents();
                if (contents == null || contents.length == 0) return;
                int length = Math.min(items.size(), contents.length);
                for (int i = 0; i < length; i++) container.set(i, items.get(i), false);
            });
        }
        if (input instanceof PlayerHotbarPacket packet) {
            if (packet.getContainerId() != inventory.inventoryContainer.getId() || !packet.isSelectHotbarSlot()) return;
            int slot = packet.getSelectedHotbarSlot();
            if (slot >= 0 && slot < 9) player.queueAcknowledgment(packet, () -> inventory.heldItemSlot = slot);
        }
    }

    private static ItemCache bundleCacheForStorage(CompensatedInventory inventory, ItemData storageItem) {
        storageItem = inventory.nativeItem(storageItem);
        int id;
        try { id = ac.navro.proxy.util.NbtNumber.getInt(Objects.requireNonNull(storageItem.getTag()), "bundle_id"); }
        catch (Exception ignored) { return null; }
        return inventory.getBundleCache().get(id);
    }
}
