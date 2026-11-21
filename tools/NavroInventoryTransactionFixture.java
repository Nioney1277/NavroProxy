import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.player.*;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.check.api.holder.CheckHolder;
import ac.navro.anticheat.packets.player.PlayerInventoryPackets;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.inventory.transaction.data.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.NetworkInventoryAction;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;




public final class NavroInventoryTransactionFixture {
    public static class Session extends Player {
        public GameVersion version;public PlayerInventory inventory;
        public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return version;}
        @Override public PlayerInventory getInventory(){return inventory;}
    }
    public static class ServerInventory extends PlayerInventory {
        public ServerInventory(){super(null);}
        @Override public Item getItemInHand(){return Item.AIR_ITEM;}
    }
    static void set(Object o,Class<?> c,String name,Object value)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
        set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();
        var manager=new NavroPlayerManager();set(Navro.getInstance(),Navro.class,"playerManager",manager);
        int[] protocols={729,776,944,975,2168,2193};int scenarios=12,rows=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4e495431);out.writeInt(protocols.length*scenarios);
            for(int protocol:protocols)for(int scenario=0;scenario<scenarios;scenario++) {
                var p=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Session)unsafe.allocateInstance(Session.class);
                session.protocol=protocol;session.version=GameVersion.byProtocol(protocol,false);session.inventory=(ServerInventory)unsafe.allocateInstance(ServerInventory.class);
                set(p,NavroPlayer.class,"session",session);manager.put(session,p);p.position=new ac.navro.anticheat.util.math.Vec3(8,64,8);
                set(p,PlayerData.class,"flagTracker",new FlagTracker());set(p,NavroPlayer.class,"itemUseTracker",new ItemUseTracker(p));
                set(p,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(p));set(p,NavroPlayer.class,"checkHolder",unsafe.allocateInstance(CheckHolder.class));
                var inventory=new CompensatedInventory(p);set(p,NavroPlayer.class,"compensatedInventory",inventory);
                set(p,NavroPlayer.class,"transactionValidator",new ItemTransactionValidator(p));
                out.writeInt(protocol);out.writeInt(scenario);
                Item held=NavroDecodedInventoryFixture.wire(out,session.version,false,Item.get(261,0,4));inventory.inventoryContainer.set(0,held);
                Item claimed=NavroDecodedInventoryFixture.wire(out,session.version,true,Item.get(scenario==7?1:261,0,scenario==1?5:scenario==2?4:2));
                var packet=new InventoryTransactionPacket();
                if(scenario<=4) {
                    packet.transactionType=InventoryTransactionPacket.TYPE_NORMAL;
                    var world=new NetworkInventoryAction();world.sourceType=NetworkInventoryAction.SOURCE_WORLD;world.inventorySlot=0;world.newItem=claimed;
                    var container=new NetworkInventoryAction();container.sourceType=NetworkInventoryAction.SOURCE_CONTAINER;container.windowId=0;container.inventorySlot=0;
                    packet.actions=scenario==3?new NetworkInventoryAction[]{world}:scenario==4?new NetworkInventoryAction[]{container,world}:new NetworkInventoryAction[]{world,container};
                } else if(scenario<=9) {
                    packet.transactionType=InventoryTransactionPacket.TYPE_USE_ITEM;
                    var data=new UseItemData();data.actionType=scenario==9?2:1;data.hotbarSlot=scenario==6?9:0;data.itemInHand=claimed;data.blockPos=new BlockVector3(0,0,0);packet.transactionData=data;
                    if(scenario==8){var action=new NetworkInventoryAction();action.sourceType=NetworkInventoryAction.SOURCE_CONTAINER;action.inventorySlot=0;action.oldItem=Item.get(260,0,1);packet.actions=new NetworkInventoryAction[]{action};}
                } else if(scenario==10) {
                    p.getItemUseTracker().use(held,261,false);packet.transactionType=InventoryTransactionPacket.TYPE_RELEASE_ITEM;
                    var data=new ReleaseItemData();data.actionType=0;packet.transactionData=data;
                } else packet.transactionType=InventoryTransactionPacket.TYPE_MISMATCH;
                var event=new DataPacketReceiveEvent(session,packet);new PlayerInventoryPackets().onPacketReceive(event);
                out.writeBoolean(!event.isCancelled());out.writeInt(inventory.inventoryContainer.get(0).count());
                out.writeBoolean(inventory.inventoryContainer.get(0)==ac.navro.anticheat.data.inventory.ItemCache.AIR);
                out.writeInt(p.getItemUseTracker().getJavaItemId());out.writeUTF(p.getItemUseTracker().getDirtyUsing().name());
                out.writeBoolean(p.getFlagTracker().has(-1));rows++;
            }
        }System.out.println("Original inventory listener cancellation/cache/use-state cases="+rows);
    }
}
