package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.inventory.*;
import cn.nukkit.inventory.request.NetworkMapping;
import cn.nukkit.inventory.transaction.action.SlotChangeAction;
import cn.nukkit.item.Item;
import cn.nukkit.event.*;
import cn.nukkit.event.server.*;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.AuthInputAction;
import cn.nukkit.network.protocol.types.ContainerIds;
import cn.nukkit.network.protocol.types.inventory.ContainerSlotType;
import cn.nukkit.network.protocol.types.inventory.itemstack.response.ItemStackResponseStatus;
import java.util.*;


public final class NativeStateSync implements Listener {
    public static final String CHANNEL="navro:backend_sync_v1";
    private static final int CREATED_OUTPUT=PlayerUIComponent.CREATED_ITEM_OUTPUT_UI_SLOT;
    private final Map<Player,Session> sessions=new IdentityHashMap<>();
    private record Slot(int container,int index) { }
    private record Source(Inventory inventory,int nativeSlot) { }
    private record ResponseSlot(Source source,Item snapshot,boolean emptyConfirmed) { }
    private static final class Pending {
        final Source source;
        Item sent;
        Pending(Source source) { this.source=source; }
    }
    private static final class Session {
        final String nonce;
        long sequence;
        int lastTick;
        boolean tickSent;
        final Map<Slot,Pending> pending=new LinkedHashMap<>();
        long uiRevision;
        int due;
        Session(String nonce) { this.nonce=nonce; }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void onReceive(DataPacketReceiveEvent event) {
        var player=event.getPlayer();var packet=event.getPacket();
        if(packet instanceof ScriptMessagePacket message&&CHANNEL.equals(message.channel)) {
            event.setCancelled(true);
            String value=message.message;
            if(value==null||!value.startsWith("hello|")||value.length()!=42)return;
            String nonce=value.substring(6);
            try { UUID.fromString(nonce); } catch(IllegalArgumentException ex) { return; }
            sessions.put(player,new Session(nonce));sendTick(player);return;
        }
        if(!sessions.containsKey(player))return;
        if(packet instanceof ContainerClosePacket close && !event.isCancelled()) {
            forgetWindow(sessions.get(player),close.windowId);
        }
        boolean transaction=packet instanceof InventoryTransactionPacket
                ||packet instanceof ItemStackRequestPacket
                ||packet instanceof PlayerAuthInputPacket input && (
                    input.getInputData().contains(AuthInputAction.PERFORM_ITEM_INTERACTION)
                    ||input.getInputData().contains(AuthInputAction.PERFORM_ITEM_STACK_REQUEST));
        if(transaction)capture(player);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void onTransaction(InventoryTransactionEvent event) {
        Player player=event.getTransaction().getSource();
        Session session=sessions.get(player);
        if(session==null)return;
        for(var action:event.getTransaction().getActionList())if(action instanceof SlotChangeAction change) {
            var source=new Source(change.getInventory(),change.getSlot());
            var slot=wireSlot(player,source);
            if(slot!=null)track(player,session,slot,source,false);
        }
    }
    private void capture(Player player) {
        Session s=sessions.get(player);if(s==null)return;
        s.due=player.getServer().getTick()+1;


        Set<Inventory> inventories=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int id=0;id<256;id++) { Inventory inv=player.getWindowById(id);if(inv!=null)inventories.add(inv); }
        if(player.getInventory()!=null)inventories.add(player.getInventory());
        if(player.getOffhandInventory()!=null)inventories.add(player.getOffhandInventory());
        if(player.getUIInventory()!=null)inventories.add(player.getUIInventory());
        if(player.getCraftingGrid()!=null)inventories.add(player.getCraftingGrid());
        for(Inventory inv:inventories) {
            if(inv==player.getUIInventory()) {
                track(player,s,new Slot(ContainerIds.UI,0),new Source(inv,0),true);
                track(player,s,new Slot(ContainerIds.UI,CREATED_OUTPUT),new Source(inv,CREATED_OUTPUT),true);
                continue;
            }
            if(inv instanceof PlayerCursorInventory)continue;
            int size=slotLimit(inv);
            for(int index=0;index<size;index++) {
                var source=new Source(inv,index);
                var slot=wireSlot(player,source);
                if(slot!=null&&isCurrent(player,slot,source))track(player,s,slot,source,true);
            }
        }
    }
    private static int slotLimit(Inventory inventory) {
        return inventory.getSize()+(inventory instanceof PlayerInventory?4:0);
    }
    private static Slot wireSlot(Player player,Source source) {
        Inventory inventory=source.inventory();
        int index=source.nativeSlot();
        if(index<0||index>=slotLimit(inventory))return null;
        if(inventory instanceof PlayerUIComponent component) {
            int uiIndex=componentSlot(component,index);
            return uiIndex<0?null:new Slot(ContainerIds.UI,uiIndex);
        }
        if(inventory==player.getInventory()) {
            return index<inventory.getSize()?new Slot(ContainerIds.INVENTORY,index)
                    :new Slot(ContainerIds.ARMOR,index-inventory.getSize());
        }
        if(inventory==player.getOffhandInventory())return new Slot(ContainerIds.OFFHAND,index);
        if(inventory==player.getUIInventory())return new Slot(ContainerIds.UI,index);
        int id=player.getWindowId(inventory);
        return id>=0&&id<=255?new Slot(id,index):null;
    }
    private static int componentSlot(PlayerUIComponent component,int index) {
        if(component instanceof PlayerCursorInventory)return index==0?0:-1;
        if(component instanceof BigCraftingGrid)return 32+index;
        if(component instanceof CraftingGrid)return 28+index;
        if(!(component instanceof FakeBlockUIComponent workstation))return -1;
        return switch(workstation.getFakeBlockType()) {
            case ANVIL -> index<2?AnvilInventory.ANVIL_INPUT_UI_SLOT+index:-1;
            case ENCHANT_TABLE -> EnchantInventory.ENCHANT_INPUT_ITEM_UI_SLOT+index;
            case BEACON -> 27+index;
            case LOOM -> index<3?LoomInventory.OFFSET+index:-1;
            case CARTOGRAPHY -> CartographyTableInventory.CARTOGRAPHY_INPUT_UI_SLOT+index;
            case GRINDSTONE -> index<2?GrindstoneInventory.GRINDSTONE_EQUIPMENT_UI_SLOT+index:-1;
            case STONECUTTER -> index==0?StonecutterInventory.STONECUTTER_INPUT_UI_SLOT:-1;
            case SMITHING_TABLE -> SmithingInventory.SMITHING_EQUIPMENT_UI_SLOT+index;
            default -> -1;
        };
    }
    private static Pending track(Player player,Session session,Slot slot,Source source,boolean refresh) {
        if(session.pending.isEmpty())session.due=player.getServer().getTick()+1;
        Pending previous=session.pending.get(slot);
        if(!refresh&&previous!=null&&previous.source.inventory()==source.inventory()
                &&previous.source.nativeSlot()==source.nativeSlot())return previous;
        var pending=new Pending(source);
        session.pending.put(slot,pending);
        return pending;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSend(DataPacketSendEvent event) {
        if(event.isCancelled())return;
        var player=event.getPlayer();Session s=sessions.get(player);if(s==null)return;
        var packet=event.getPacket();
        if(packet instanceof UpdateBlockPacket ||packet instanceof UpdateSubChunkBlocksPacket)sendTick(player);
        if(packet instanceof InventorySlotPacket slot) {
            observeSend(event,player,s,new Slot(slot.inventoryId,slot.slot),slot.item);
        } else if(packet instanceof InventoryContentPacket content && content.slots!=null) {
            for(int index=0;index<content.slots.length;index++)
                observeSend(event,player,s,new Slot(content.inventoryId,index),content.slots[index]);
        } else if(packet instanceof ItemStackResponsePacket response) {
            observeResponse(event,player,s,response);
        } else if(packet instanceof ContainerOpenPacket open) {
            event.afterSend(()->forgetWindow(s,open.windowId));
        } else if(packet instanceof ContainerClosePacket close) {
            event.afterSend(()->forgetWindow(s,close.windowId));
        } else if(packet instanceof ChangeDimensionPacket) {
            event.afterSend(()-> {s.uiRevision++;s.pending.clear();});
        }
    }
    private static void forgetWindow(Session session,int id) {
        if(id==ContainerIds.INVENTORY||id==ContainerIds.OFFHAND||id==ContainerIds.ARMOR||id==ContainerIds.UI)return;
        session.uiRevision++;
        int windowId=id&0xff;
        session.pending.keySet().removeIf(slot->slot.container()==windowId
                ||slot.container()==ContainerIds.UI&&slot.index()!=0);
    }
    private void observeSend(DataPacketSendEvent event,Player player,Session session,Slot slot,Item item) {
        Pending pending=session.pending.get(slot);
        if(pending==null)return;
        Item snapshot=copy(item);
        event.afterSend(()-> {
            if(sessions.get(player)==session&&session.pending.get(slot)==pending)pending.sent=snapshot;
        });
    }
    private void observeResponse(DataPacketSendEvent event,Player player,Session session,ItemStackResponsePacket packet) {
        Map<Slot,ResponseSlot> updates=new LinkedHashMap<>();
        long uiRevision=session.uiRevision;
        for(var response:packet.entries) {
            if(response==null||response.getResult()!=ItemStackResponseStatus.OK||response.getContainers()==null)continue;
            for(var container:response.getContainers()) {
                if(container==null||container.getItems()==null)continue;
                var name=container.getContainerName();
                var type=name==null?container.getContainer():name.getContainer();
                if(type==null||type==ContainerSlotType.DYNAMIC_CONTAINER||name!=null&&name.getDynamicId()!=null)continue;
                Inventory inventory;
                try { inventory=NetworkMapping.getInventory(player,type,null); }
                catch(RuntimeException ignored) { continue; }
                if(inventory==null)continue;
                if(type==ContainerSlotType.CURSOR&&inventory instanceof PlayerCursorInventory)inventory=player.getUIInventory();
                if(inventory==null)continue;
                for(var wire:container.getItems()) {
                    if(wire==null)continue;
                    var source=new Source(inventory,NetworkMapping.toInternalSlot(type,wire.getSlot()));
                    if(inventory instanceof FakeBlockUIComponent&&wire.getSlot()==CREATED_OUTPUT
                            &&isWorkstationResult(type)&&player.getTopWindow().orElse(null)==inventory
                            &&player.getUIInventory()!=null) {
                        source=new Source(player.getUIInventory(),CREATED_OUTPUT);
                    }
                    var slot=wireSlot(player,source);
                    if(slot==null||!isCurrent(player,slot,source))continue;
                    Item snapshot=copy(item(source));
                    boolean responseConfirmsEmpty=snapshot.isNull()&&wire.getCount()==0
                            &&wire.getStackNetworkId()==0&&wire.getSlot()==slot.index()
                            &&switch(type) {
                                case HOTBAR, INVENTORY, HOTBAR_AND_INVENTORY -> slot.container()==ContainerIds.INVENTORY;
                                case ARMOR -> slot.container()==ContainerIds.ARMOR;
                                case OFFHAND -> slot.container()==ContainerIds.OFFHAND;
                                default -> false;
                            };
                    updates.put(slot,new ResponseSlot(source,snapshot,responseConfirmsEmpty));
                }
            }
        }
        if(updates.isEmpty())return;
        event.afterSend(()-> {
            for(var entry:updates.entrySet()) {
                Slot slot=entry.getKey();ResponseSlot update=entry.getValue();Source source=update.source();
                if(sessions.get(player)!=session||!isCurrent(player,slot,source))continue;
                if(slot.container()==ContainerIds.UI&&slot.index()!=0&&session.uiRevision!=uiRevision)continue;
                var pending=track(player,session,slot,source,true);
                if(!same(update.snapshot(),item(source)))continue;
                if(update.emptyConfirmed())pending.sent=update.snapshot();
                else sendSlot(player,slot,source);
            }
        });
    }
    private static boolean isWorkstationResult(ContainerSlotType type) {
        return switch(type) {
            case ANVIL_RESULT, STONECUTTER_RESULT, LOOM_RESULT, CARTOGRAPHY_RESULT,
                    GRINDSTONE_RESULT, SMITHING_TABLE_RESULT -> true;
            default -> false;
        };
    }
    private static Item copy(Item item) { return item==null||item.isNull()?Item.AIR_ITEM.clone():item.clone(); }
    private static Item item(Source source) { return source.inventory().getItem(source.nativeSlot()); }
    private static boolean isCurrent(Player player,Slot slot,Source source) {
        if(!slot.equals(wireSlot(player,source)))return false;
        return switch(slot.container()) {
            case ContainerIds.INVENTORY,ContainerIds.ARMOR -> player.getInventory()==source.inventory();
            case ContainerIds.OFFHAND -> player.getOffhandInventory()==source.inventory();
            case ContainerIds.UI -> isCurrentUi(player,source.inventory());
            default -> player.getWindowById(slot.container())==source.inventory();
        };
    }
    private static boolean isCurrentUi(Player player,Inventory inventory) {
        var ui=player.getUIInventory();
        if(ui==null)return false;
        if(inventory==ui||inventory==ui.getCursorInventory())return true;
        var top=player.getTopWindow().orElse(null);
        if(inventory instanceof CraftingGrid) {
            return top instanceof CraftingGrid?inventory==top:inventory==player.getCraftingGrid();
        }
        if(inventory instanceof FakeBlockUIComponent) {
            int id=player.getWindowId(inventory);
            return inventory==top&&id>=0&&player.getWindowById(id)==inventory;
        }
        return false;
    }
    private static void sendSlot(Player player,Slot slot,Source source) {
        if(slot.container()==ContainerIds.ARMOR&&source.inventory() instanceof PlayerInventory inventory)
            inventory.sendArmorSlot(source.nativeSlot(),player);
        else if(slot.container()==ContainerIds.UI||slot.container()==ContainerIds.OFFHAND)
            source.inventory().sendSlot(source.nativeSlot(),new Player[]{player});
        else source.inventory().sendSlot(source.nativeSlot(),player);
    }

    public void tick() {
        for(Player player:new ArrayList<>(sessions.keySet())) {
            if(!player.isOnline()){sessions.remove(player);continue;}
            sendTick(player);flushInventory(player);
        }
    }
    public void sendTick(Player player) {
        Session s=sessions.get(player);if(s==null)return;
        int tick=player.getServer().getTick();if(s.tickSent&&s.lastTick==tick)return;
        var packet=new ScriptMessagePacket();packet.channel=CHANNEL;
        packet.message="tick|"+s.nonce+"|"+s.sequence+"|"+tick;
        if(player.dataPacket(packet)){s.sequence++;s.lastTick=tick;s.tickSent=true;}
    }
    public void flushInventory(Player player) {
        Session s=sessions.get(player);if(s==null||s.pending.isEmpty()||player.getServer().getTick()-s.due<0)return;
        for(var entry:new ArrayList<>(s.pending.entrySet())) {
            Slot slot=entry.getKey();Pending pending=entry.getValue();Source source=pending.source;
            if(sessions.get(player)!=s||s.pending.get(slot)!=pending)continue;
            if(!isCurrent(player,slot,source)) {
                s.pending.remove(slot,pending);continue;
            }
            if(pending.sent==null||!same(pending.sent,item(source)))sendSlot(player,slot,source);
            if(pending.sent!=null&&same(pending.sent,item(source)))s.pending.remove(slot,pending);
        }
    }
    private static boolean same(Item a,Item b) {
        if(a==null||a.isNull())return b==null||b.isNull();
        return b!=null&&a.equalsExact(b)&&a.getStackNetId()==b.getStackNetId();
    }
    public void remove(Player player) { sessions.remove(player); }
    public void clear() { sessions.clear(); }
}
