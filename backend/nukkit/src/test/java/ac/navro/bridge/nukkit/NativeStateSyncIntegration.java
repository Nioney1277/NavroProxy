package ac.navro.bridge.nukkit;

import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.inventory.*;
import cn.nukkit.inventory.transaction.InventoryTransaction;
import cn.nukkit.inventory.transaction.action.*;
import cn.nukkit.item.*;
import cn.nukkit.level.Position;
import cn.nukkit.event.server.*;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.inventory.ContainerSlotType;
import cn.nukkit.network.protocol.types.inventory.FullContainerName;
import cn.nukkit.network.protocol.types.inventory.itemstack.response.*;
import sun.misc.Unsafe;
import java.util.*;
import java.nio.file.*;
import com.google.gson.Gson;



public final class NativeStateSyncIntegration {
    static final Unsafe U;
    static {try{var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);U=(Unsafe)f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static void set(Object object,Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static class Peer extends Player {
        NativeStateSync sync;
        Map<Integer,Inventory> windows;
        List<DataPacket> delivered;
        boolean cancelSlot;
        boolean cancelResponse;
        Inventory topWindow;
        PlayerUIInventory ui;
        CraftingGrid grid;
        Peer(){super(null,0L,new java.net.InetSocketAddress(0));}
        @Override public boolean isOnline(){return true;}
        @Override public Inventory getWindowById(int id){return windows.get(id);}
        @Override public int getWindowId(Inventory inv){return windows.entrySet().stream().filter(e->e.getValue()==inv).map(Map.Entry::getKey).findFirst().orElse(-1);}
        @Override public Optional<Inventory> getTopWindow(){return Optional.ofNullable(topWindow);}
        @Override public PlayerUIInventory getUIInventory(){return ui;}
        @Override public CraftingGrid getCraftingGrid(){return grid;}
        @Override public boolean dataPacket(DataPacket packet){
            var afterSend=new ArrayList<Runnable>();var previous=PostSend.setTestScheduler(afterSend::add);
            try {
                var event=new DataPacketSendEvent(this,packet);sync.onSend(event);
                if(packet instanceof InventorySlotPacket&&cancelSlot||packet instanceof ItemStackResponsePacket&&cancelResponse){event.setCancelled(true);return false;}
                delivered.add(packet);
                while(!afterSend.isEmpty())afterSend.remove(0).run();
                return true;
            } finally { PostSend.setTestScheduler(previous); }
        }
    }
    static class Transaction extends InventoryTransaction {
        final NativeStateSync sync;
        boolean cancel;
        Transaction(Peer p,List<InventoryAction> actions){super(p,actions);sync=p.sync;}
        @Override protected boolean callExecuteEvent(){
            var event=new InventoryTransactionEvent(this);event.setCancelled(cancel);sync.onTransaction(event);return !event.isCancelled();
        }
    }
    static Peer peer(NativeStateSync sync,Server server)throws Exception {
        var peer=(Peer)U.allocateInstance(Peer.class);peer.sync=sync;peer.windows=new HashMap<>();peer.delivered=new ArrayList<>();peer.protocol=2193;peer.spawned=true;
        set(peer,Entity.class,"server",server);set(peer,Player.class,"rawSocketAddress",new java.net.InetSocketAddress("127.0.0.1",19133));return peer;
    }
    static void tick(Server server,int tick)throws Exception{set(server,Server.class,"tickCounter",tick);}
    static void hello(Peer p,String nonce){var pk=new ScriptMessagePacket();pk.channel=NativeStateSync.CHANNEL;pk.message="hello|"+nonce;var e=new DataPacketReceiveEvent(p,pk);p.sync.onReceive(e);check(e.isCancelled(),"Handshake never reaches gameplay scripts");}
    static Item stone(int count){return Item.get(1,0,count);}
    static ItemStackResponsePacket response(ItemStackResponseStatus status,ContainerSlotType type,Integer dynamicId,int slot,Item item) {
        var packet=new ItemStackResponsePacket();
        var metadata=new ItemStackResponseSlot(slot,slot,item.getCount(),item.getStackNetId(),"",0,"");
        var container=new ItemStackResponseContainer(type,List.of(metadata),new FullContainerName(type,dynamicId));
        packet.entries.add(new ItemStackResponse(status,-1,List.of(container)));
        return packet;
    }
    static List<InventorySlotPacket> slots(Peer peer) {
        return peer.delivered.stream().filter(InventorySlotPacket.class::isInstance).map(InventorySlotPacket.class::cast).toList();
    }
    public static void main(String[] args)throws Exception {
        var server=(Server)U.allocateInstance(Server.class);set(null,Server.class,"instance",server);Block.init();RuntimeItems.init();Item.init();
        var plugin=new NavroFireworkBridge();set(plugin,NavroFireworkBridge.class,"trustedProxies",Set.of("127.0.0.1"));var sync=new NativeStateSync(plugin);var peer=peer(sync,server);var inv=new BaseInventory(null,InventoryType.CHEST,new HashMap<>(),3) { };peer.windows.put(5,inv);inv.getViewers().add(peer);peer.topWindow=inv;
        inv.setItem(0,stone(4),false);peer.delivered.clear();tick(server,10);
        sync.onReceive(new DataPacketReceiveEvent(peer,new InventoryTransactionPacket()));tick(server,11);sync.tick();check(peer.delivered.isEmpty(),"Unnegotiated players get no sync traffic");
        tick(server,100);String nonce="12345678-1234-1234-1234-123456789012";hello(peer,nonce);
        check(peer.delivered.size()==3&&peer.delivered.get(0) instanceof ScriptMessagePacket,"Handshake sends readiness and native clock, no inventory snapshots");
        check(((ScriptMessagePacket)peer.delivered.get(1)).message.equals("tick|"+nonce+"|0|100"),"Exact Server.getTick sample");
        sync.tick();check(peer.delivered.size()==3,"No repeated sample in the same tick");tick(server,107);sync.tick();check(((ScriptMessagePacket)peer.delivered.get(3)).message.endsWith("|107"),"Native tick advance, not an artificial +1 clock");
        peer.delivered.clear();
        var transaction=new Transaction(peer,List.of(new SlotChangeAction(inv,0,inv.getItem(0),stone(3)),new SlotChangeAction(inv,1,inv.getItem(1),stone(1))));
        check(transaction.execute()&&transaction.hasExecuted(),"Real Nukkit transaction executes");
        check(inv.getItem(0).getCount()==3&&inv.getItem(1).getCount()==1,"Native actions changed actual inventory");
        check(peer.delivered.isEmpty(),"Nukkit success excludes source viewer; this is the missing wire information");
        sync.flushInventory(peer);check(peer.delivered.isEmpty(),"Never synchronize the pre-execution event snapshot");
        tick(server,108);sync.tick();
        var slots=slots(peer);
        check(slots.size()==2,"Exactly the two unknown final slots are supplemented");
        check(slots.get(0).inventoryId==5&&slots.get(0).slot==0&&slots.get(0).item.equalsExact(inv.getItem(0)),"Committed slot 0 identity, not client claim");
        check(slots.get(1).item.equalsExact(inv.getItem(1)),"Committed slot 1 identity");
        var fixture=new ArrayList<Map<String,Object>>();for(var pk:slots){fixture.add(encode(pk,pk.inventoryId,pk.slot,pk.item));}
        peer.delivered.clear();sync.tick();check(peer.delivered.isEmpty(),"No periodic inventory replication");
        sync.onReceive(new DataPacketReceiveEvent(peer,new InventoryTransactionPacket()));inv.sendContents(peer);tick(server,109);sync.tick();
        check(peer.delivered.stream().noneMatch(InventorySlotPacket.class::isInstance),"Native full contents already confirm the result; no duplicate slot supplements");
        peer.delivered.clear();sync.onReceive(new DataPacketReceiveEvent(peer,new InventoryTransactionPacket()));peer.cancelSlot=true;tick(server,110);sync.tick();
        check(peer.delivered.stream().noneMatch(InventorySlotPacket.class::isInstance),"Cancelled sends do not claim a delivered inventory result");
        peer.cancelSlot=false;tick(server,111);sync.tick();check(slots(peer).size()==3,"Cancelled supplements retry after native delivery is available");
        peer.delivered.clear();
        var cancelled=new Transaction(peer,List.of(new SlotChangeAction(inv,0,inv.getItem(0),stone(2)),new SlotChangeAction(inv,1,inv.getItem(1),stone(2))));
        cancelled.cancel=true;cancelled.execute();
        check(inv.getItem(0).getCount()==3&&inv.getItem(1).getCount()==1,"Cancelled native transaction never publishes proposed targets");
        var rollback=slots(peer);
        check(rollback.size()==2&&rollback.get(0).item.getCount()==3&&rollback.get(1).item.getCount()==1,"Native rollback publishes two actual slots");
        tick(server,112);sync.tick();
        check(slots(peer).size()==2,"Native rollback already confirms both affected slots");

        peer.delivered.clear();
        var named=stone(3).setCustomName("Authoritative stone").setStackNetId(800);
        inv.setItem(0,named,false);
        Item actual=inv.getItem(0);
        var modern=response(ItemStackResponseStatus.OK,ContainerSlotType.LEVEL_ENTITY,null,0,actual);
        peer.cancelResponse=true;peer.dataPacket(modern);peer.cancelResponse=false;
        check(peer.delivered.isEmpty(),"Cancelled response does not emit a supplement");
        peer.dataPacket(response(ItemStackResponseStatus.ERROR,ContainerSlotType.LEVEL_ENTITY,null,0,actual));
        peer.dataPacket(response(ItemStackResponseStatus.OK,ContainerSlotType.DYNAMIC_CONTAINER,41,0,actual));
        check(slots(peer).isEmpty(),"Rejected and bundle-internal responses do not emit slot supplements");
        peer.delivered.clear();peer.dataPacket(modern);
        check(peer.delivered.size()==2&&peer.delivered.get(0)==modern,"Native response precedes its supplement");
        var confirmed=slots(peer).get(0);
        check(confirmed.slot==0&&confirmed.inventoryId==5&&confirmed.item.equalsExact(actual)
                &&confirmed.item.getStackNetId()==actual.getStackNetId(),"Modern response supplements the real slot identity and NBT");
        var modernFixture=List.of(encode(modern,confirmed.inventoryId,confirmed.slot,actual),
                encode(confirmed,confirmed.inventoryId,confirmed.slot,actual));
        tick(server,113);sync.tick();check(slots(peer).size()==1,"Queued response supplement is not duplicated on the next tick");

        peer.delivered.clear();peer.cancelSlot=true;peer.dataPacket(modern);
        check(slots(peer).isEmpty(),"Cancelled modern supplement does not count as delivered");
        peer.cancelSlot=false;tick(server,114);sync.tick();check(slots(peer).size()==1,"Missing modern supplement is retried");
        peer.delivered.clear();sync.onReceive(new DataPacketReceiveEvent(peer,new InventoryTransactionPacket()));
        var close=new ContainerClosePacket();close.windowId=5;peer.dataPacket(close);
        var open=new ContainerOpenPacket();open.windowId=5;peer.dataPacket(open);
        tick(server,115);sync.tick();check(slots(peer).isEmpty(),"Even reuse of the same window object drops earlier pending slots");
        var workstationFixture=workstations(peer,server);
        sync.remove(peer);peer.delivered.clear();tick(server,122);sync.tick();check(peer.delivered.isEmpty(),"Quit clears native session");
        if(args.length>0)Files.writeString(Path.of(args[0]),new Gson().toJson(Map.of("source","Actual Nukkit InventoryTransaction.execute with event bus boundary; SlotChangeAction, BaseInventory and InventorySlotPacket.encode are native production implementations","protocol",2193,"slots",fixture,"responseAndSupplement",modernFixture,"workstations",workstationFixture)));
        System.out.println("NATIVE_STATE_SYNC_INTEGRATION_PASSED exact tick, actual transaction, missing identities, no duplication, cancelled delivery, closed window, no unnegotiated traffic");
    }
    static List<Map<String,Object>> workstations(Peer peer,Server server)throws Exception {
        peer.ui=new PlayerUIInventory(peer);
        var position=new Position(0,64,0);
        var smithing=new SmithingInventory(peer.ui,position);
        var enchanting=new EnchantInventory(peer.ui,position);
        var rows=new ArrayList<Map<String,Object>>();
        openWindow(peer,smithing,Player.SMITHING_WINDOW_ID);
        rows.add(workstationSlot(peer,ContainerSlotType.SMITHING_TABLE_INPUT,51,51,811));
        rows.add(workstationSlot(peer,ContainerSlotType.SMITHING_TABLE_TEMPLATE,2,53,812));
        rows.add(workstationSlot(peer,ContainerSlotType.SMITHING_TABLE_RESULT,50,50,813));

        peer.cancelSlot=true;
        var previous=peer.ui.getItem(51);
        peer.dataPacket(response(ItemStackResponseStatus.OK,ContainerSlotType.SMITHING_TABLE_INPUT,null,51,previous));
        peer.cancelSlot=false;
        var close=new ContainerClosePacket();close.windowId=Player.SMITHING_WINDOW_ID;peer.dataPacket(close);
        openWindow(peer,enchanting,Player.ENCHANT_WINDOW_ID);
        peer.delivered.clear();tick(server,116);peer.sync.tick();
        check(slots(peer).isEmpty(),"Cancelled smithing slot is not retried after changing workstations");
        rows.add(workstationSlot(peer,ContainerSlotType.ENCHANTING_MATERIAL,15,15,814));
        peer.delivered.clear();
        peer.dataPacket(response(ItemStackResponseStatus.OK,ContainerSlotType.SMITHING_TABLE_INPUT,null,51,previous));
        check(slots(peer).isEmpty(),"Inactive native workstation cannot supply the response");

        peer.topWindow=null;
        peer.grid=peer.ui.getBigCraftingGrid();
        var crafting=new ContainerOpenPacket();crafting.windowId=-1;crafting.type=1;peer.dataPacket(crafting);
        rows.add(workstationSlot(peer,ContainerSlotType.CRAFTING_INPUT,36,36,815));
        return rows;
    }
    static void openWindow(Peer peer,FakeBlockUIComponent workstation,int windowId) {
        peer.topWindow=workstation;
        peer.windows.put(windowId,workstation);
        var open=new ContainerOpenPacket();open.windowId=windowId;open.type=workstation.getFakeBlockType().getNetworkType();peer.dataPacket(open);
    }
    static Map<String,Object> workstationSlot(Peer peer,ContainerSlotType type,int responseSlot,int uiSlot,int stackId) {
        peer.delivered.clear();
        peer.ui.setItemForce(uiSlot,stone(2).setCustomName("Native workstation "+uiSlot).setStackNetId(stackId));
        Item actual=peer.ui.getItem(uiSlot);
        var response=response(ItemStackResponseStatus.OK,type,null,responseSlot,actual);
        peer.dataPacket(response);
        var slots=slots(peer);
        check(slots.size()==1&&peer.delivered.get(0)==response,"Expected one slot after the accepted workstation response: "+type);
        var slot=slots.get(0);
        check(slot.inventoryId==124&&slot.slot==uiSlot&&slot.item.equalsExact(actual)
                &&slot.item.getStackNetId()==stackId,"Native component supplies its physical UI slot: "+type);
        int windowType=peer.topWindow instanceof FakeBlockUIComponent workstation?workstation.getFakeBlockType().getNetworkType():1;
        return Map.of("windowType",windowType,"response",encode(response,124,uiSlot,actual),"slot",encode(slot,124,uiSlot,actual));
    }
    static Map<String,Object> encode(DataPacket packet,int container,int slot,Item item) {
        packet.protocol=2193;packet.gameVersion=GameVersion.byProtocol(2193,false);packet.encode();
        return Map.of("container",container,"slot",slot,"count",item.getCount(),"stackId",item.getStackNetId(),
                "name",item.getCustomName(),"bytes",Base64.getEncoder().encodeToString(packet.getBuffer()));
    }
}
