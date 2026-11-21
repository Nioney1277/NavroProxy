import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.data.inventory.ItemCache;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.util.ItemUtil;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.InventoryTransactionPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.types.NetworkInventoryAction;
import cn.nukkit.utils.BinaryStream;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroDecodedInventoryFixture {
    static Unsafe unsafe;
    public static final class Session extends Player {
        public GameVersion version;
        public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return version;}
    }
    static void set(Object object,Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    static Item origin(int carrier,int id,String name,int count,CompoundTag inner){
        CompoundTag tag=new CompoundTag().putInt("mv_origin_id",id).putInt("mv_origin_meta",0).putInt("bundle_id",999);
        if(name!=null)tag.putString("mv_origin_namespace",name);
        tag.putCompound("mv_origin_nbt",inner);
        return Item.get(carrier,0,count).setNamedTag(tag);
    }
    static Item bundle(int mode,int tagMode){
        CompoundTag inner=new CompoundTag();
        switch(tagMode){case 0->inner.putInt("bundle_id",3);case 1->{}case 2->inner.putString("other","present");case 3->inner.putInt("bundle_id",-1);case 4->inner.putString("bundle_id","bad");case 5->inner.putLong("bundle_id",0x100000003L);case 6->inner.putDouble("bundle_id",3.75);}
        if(mode!=0)return origin(mode==1?248:261,255,"minecraft:bundle",1,inner);
        Item item=Item.fromString("minecraft:bundle");item.setCount(1);return item.setNamedTag(inner);
    }
    static Item stack(int kind,int count){return switch(kind){
        case 0->Item.get(1,0,count);case 1->Item.get(3,0,count);case 2->Item.get(261,0,count);
        case 3->origin(248,1,null,count,new CompoundTag().putString("inner","stone"));
        case 4->origin(248,3,null,count,new CompoundTag());
        case 5->origin(261,1,null,count,new CompoundTag());
        case 6->origin(248,0,null,count,new CompoundTag());
        case 7->Item.get(519,0,count);case 8->null;default->throw new AssertionError(kind);
    };}
    static Item wire(DataOutputStream out,GameVersion version,boolean transaction,Item source)throws Exception{
        out.writeBoolean(source!=null);if(source==null)return null;
        boolean descriptor=version.getProtocol()>=(transaction?ProtocolInfo.v1_26_30:ProtocolInfo.v1_26_20_26);
        BinaryStream writer=new BinaryStream();if(descriptor)writer.putNetworkItemStackDescriptor(version,source);else writer.putSlot(version,source);
        byte[] bytes=writer.getBuffer();BinaryStream header=new BinaryStream(bytes);int runtime=descriptor?(short)header.getLShort():header.getVarInt();
        String name=runtime==0?"minecraft:air":RuntimeItems.getMapping(version).getNamespacedIdByNetworkId(runtime);
        if(name==null)throw new AssertionError("Unknown written native runtime "+runtime);
        out.writeBoolean(descriptor);out.writeUTF(name);out.writeInt(runtime);out.writeInt(bytes.length);out.write(bytes);
        BinaryStream reader=new BinaryStream(bytes);Item item=descriptor?reader.getNetworkItemStackDescriptor(version):reader.getSlot(version);
        if(reader.getOffset()!=bytes.length)throw new AssertionError("Unread native item bytes");return item;
    }
    static void state(DataOutputStream out,NavroPlayer player,ItemCache cache)throws Exception{
        Item item=cache.getData();out.writeInt(item.getId());out.writeInt(item.getDamage());out.writeInt(item.getCount());out.writeBoolean(cache.isEmpty());
        String name=ItemUtil.identifier(player,item);out.writeBoolean(name!=null);if(name!=null)out.writeUTF(name);out.writeInt(ItemUtil.runtimeId(player,item));
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.item.enchantment.Enchantment.init();
        var checkPlayer=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var checkSession=(Session)unsafe.allocateInstance(Session.class);checkSession.version=GameVersion.getLastVersion();set(checkPlayer,NavroPlayer.class,"session",checkSession);
        for(String name:Item.NAMESPACED_ID_ITEM.keySet()){
            Item item=Item.fromString(name);if(ItemUtil.isBundle(checkPlayer,item)!=(item instanceof ItemBundle))throw new AssertionError("Unexported bundle class/tag difference: "+name);
        }
        int[] protocols={729,776,944,975,1001,2193},counts={0,1,2,30,34,64,65};Random random=new Random(0x4e444931L);int cases=1728;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4e444931);out.writeInt(cases);
            for(int i=0;i<cases;i++){
                int protocol=protocols[i%protocols.length];GameVersion version=GameVersion.byProtocol(protocol,false);int type=(i/protocols.length)%3;
                var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Session)unsafe.allocateInstance(Session.class);session.version=version;session.protocol=protocol;set(player,NavroPlayer.class,"session",session);
                var inventory=new CompensatedInventory(player);set(player,NavroPlayer.class,"compensatedInventory",inventory);
                out.writeInt(protocol);out.writeInt(type);
                if(type==0){
                    Item b=wire(out,version,false,bundle(random.nextInt(3),new int[]{0,1,2,3,5,6}[random.nextInt(6)]));
                    Item a=wire(out,version,false,stack(random.nextInt(9),counts[random.nextInt(counts.length)]));
                    Item c=wire(out,version,false,stack(random.nextInt(9),counts[random.nextInt(counts.length)]));
                    int slot=random.nextInt(3);out.writeInt(slot);ItemCache owner=ItemCache.build(inventory,b);out.writeBoolean(owner.getBundle()!=null);
                    var keys=new TreeSet<>(inventory.getBundleCache().keySet());out.writeInt(keys.size());for(int key:keys)out.writeInt(key);
                    ItemCache duplicate=ItemCache.build(inventory,b);out.writeBoolean(duplicate.getBundle()!=null);out.writeBoolean(owner.clone().getBundle()==owner.getBundle());
                    if(owner.getBundle()!=null){
                        ItemCache first=ItemCache.build(inventory,a),second=ItemCache.build(inventory,c);var data=owner.getBundle();
                        out.writeBoolean(data.add(slot,first));state(out,player,data.getContents()[slot]);ItemCache before=data.getContents()[slot];
                        out.writeBoolean(data.add(slot,second));out.writeInt(data.count);state(out,player,data.getContents()[slot]);
                        out.writeBoolean(data.getContents()[slot]==before);out.writeBoolean(data.getContents()[slot]==first);out.writeInt(first.count());out.writeInt(second.count());
                    }
                }else if(type==1){
                    Item predicted=wire(out,version,false,stack(random.nextInt(9),counts[random.nextInt(counts.length)]));
                    Item claimed=wire(out,version,true,stack(random.nextInt(9),counts[random.nextInt(counts.length)]));inventory.inventoryContainer.set(0,predicted);
                    var packet=new InventoryTransactionPacket();packet.transactionType=InventoryTransactionPacket.TYPE_NORMAL;
                    var world=new NetworkInventoryAction();world.sourceType=NetworkInventoryAction.SOURCE_WORLD;world.inventorySlot=0;world.newItem=claimed;
                    var container=new NetworkInventoryAction();container.sourceType=NetworkInventoryAction.SOURCE_CONTAINER;container.windowId=0;container.inventorySlot=0;
                    boolean reverse=random.nextBoolean();out.writeBoolean(reverse);packet.actions=reverse?new NetworkInventoryAction[]{container,world}:new NetworkInventoryAction[]{world,container};
                    ItemCache before=inventory.inventoryContainer.get(0);out.writeBoolean(new ItemTransactionValidator(player).handle(packet));state(out,player,inventory.inventoryContainer.get(0));out.writeBoolean(before==inventory.inventoryContainer.get(0));
                }else{
                    try{wire(out,version,false,bundle(1,4));throw new AssertionError("Expected the native bundle setter to reject a string ID");}
                    catch(ClassCastException expected){out.writeUTF(expected.getClass().getName());}
                }
            }
        }
        System.out.println("Original decoded inventory: "+cases+" cache/bundle/drop cases; six native protocol entries.");
    }
}
