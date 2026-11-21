package ac.navro.anticheat.compensated.world.base;

import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.data.inventory.ItemCache;
import ac.navro.anticheat.check.api.holder.CheckHolder;
import ac.navro.anticheat.check.impl.place.RotationPlace;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.item.*;
import cn.nukkit.inventory.transaction.data.UseItemData;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.types.GameType;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroItemPlacementStageFixture {
    private static final List<String> events=new ArrayList<>();private static boolean predictionFails,callbackFails;
    private static NavroPlayer current;private static Unsafe unsafe;
    public static final class Session extends Player {
        public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}
    }
    private static final class World extends CompensatedWorldImpl {
        World(){super(null);}
        @Override protected void updateCachedBlock(int x,int y,int z,int layer,int network){var c=getChunk(x>>4,z>>4);if(c!=null)c.setFullBlock(x&15,y,z&15,layer,network);}
        @Override public void predictLocalBlock(BlockVector3 pos,int layer,int full){
            events.add("predict:"+pos.x+","+pos.y+","+pos.z+":"+layer+":"+full);
            if(predictionFails)throw new IllegalStateException("placement prediction failed");
            predictBucketNetworkBlock(pos.x,pos.y,pos.z,layer,full);
        }
        @Override public int getRawBlockAt(int x,int y,int z,int layer){var c=getChunk(x>>4,z>>4);return c==null||y<getMinY()||y>=getHeightY()?0:c.getFullBlock(x&15,y,z&15,layer);}
    }
    public static final class Observer extends RotationPlace {
        public Observer(NavroPlayer player){super(player);}
        @Override public void predictedPlacement(UseItemData data,BlockVector3 pos,int id){
            events.add("callback:"+pos.x+","+pos.y+","+pos.z+":"+id+":"+current.compensatedInventory.inventoryContainer.getHeldItemCache().count());
            if(callbackFails)throw new IllegalStateException("rotation callback failed");
        }
    }
    private static void set(Object value,Class<?> type,String name,Object data)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(value,data);}
    private static void pos(DataOutputStream out,BlockVector3 pos)throws Exception{out.writeInt(pos.x);out.writeInt(pos.y);out.writeInt(pos.z);}
    private static void load(World w,BlockVector3 p){w.put(p.x>>4,p.z>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));
        ac.navro.anticheat.Navro.setConfig(new ac.navro.anticheat.config.Config());
        Block.init();RuntimeItems.init();Item.init();
        var method=ItemTransactionValidator.class.getDeclaredMethod("handleBlockPlacement",CompensatedInventory.class,Item.class,BlockVector3.class,int.class,Block.class,UseItemData.class);method.setAccessible(true);
        String[] names={"minecraft:spruce_planks","minecraft:wheat_seeds","minecraft:sugar_cane","minecraft:wooden_door","minecraft:torchflower_seeds","minecraft:brown_dye","minecraft:bucket","minecraft:bow","minecraft:air","minecraft:glow_berries"};
        int[] ids={1,5,295,338,351,325,323,261,0,355};int[] counts={1,2,64,0,-1,Integer.MIN_VALUE,Integer.MAX_VALUE};
        int[][] blocks={{1,0},{78,7},{78,6},{175,2},{175,0},{8,0},{32,1},{0,0}};
        BlockVector3[] positions={new BlockVector3(8,64,8),new BlockVector3(15,64,15),new BlockVector3(0,-64,0),new BlockVector3(8,319,8),new BlockVector3(8,320,8),new BlockVector3(8,-65,8),new BlockVector3(Integer.MAX_VALUE,64,Integer.MIN_VALUE)};
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4E535031);int cases=1680;out.writeInt(cases);
            for(int i=0;i<cases;i++){
                boolean numeric=(i&1)==0;int which=(i/2)%ids.length;int damage=(i/20)%4==0?3:(i/20)%4==1?1:(i/20)%4==2?65535:0;
                Item held=numeric?Item.get(ids[which],damage,1):Item.fromString(names[which]);if(!numeric)held.setDamage(damage);
                int count=counts[(i/11)%counts.length];held.setCount(count);
                var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);current=player;set(player,NavroPlayer.class,"session",unsafe.allocateInstance(Session.class));
                var inventory=new CompensatedInventory(player);set(player,NavroPlayer.class,"compensatedInventory",inventory);inventory.heldItemSlot=i%9;inventory.inventoryContainer.set(inventory.heldItemSlot,held);
                var alias=inventory.inventoryContainer.getHeldItemCache();var w=new World();set(player,NavroPlayer.class,"compensatedWorld",w);
                int mode=i%8;player.gameType=mode<7?GameType.from(mode):null;
                var clicked=positions[i%positions.length];int face=i%8-1;int[] b=blocks[(i/7)%blocks.length];Block block=Block.get(b[0],b[1]);
                boolean loaded=i%11!=0,observe=i%3!=0;predictionFails=i%19==0;callbackFails=i%17==0;
                var target=ac.navro.anticheat.util.block.BlockPlacementPhysics.target(clicked,face,block);
                if(loaded){load(w,clicked);if((target.x>>4)!=(clicked.x>>4)||(target.z>>4)!=(clicked.z>>4))load(w,target);}
                w.updateBlock(clicked,0,block.getFullId());
                if(observe){var holder=(CheckHolder)unsafe.allocateInstance(CheckHolder.class);holder.put(RotationPlace.class,(Observer)unsafe.allocateInstance(Observer.class));set(player,NavroPlayer.class,"checkHolder",holder);}
                var data=new UseItemData();data.hotbarSlot=(inventory.heldItemSlot+1)%9;
                out.writeBoolean(numeric);if(numeric)out.writeInt(ids[which]);else out.writeUTF(names[which]);out.writeInt(damage);out.writeInt(count);out.writeInt(mode);out.writeInt(inventory.heldItemSlot);
                pos(out,clicked);out.writeInt(face);out.writeInt(b[0]);out.writeInt(b[1]);out.writeBoolean(loaded);out.writeBoolean(observe);out.writeBoolean(predictionFails);out.writeBoolean(callbackFails);
                events.clear();String error="";
                try{method.invoke(new ItemTransactionValidator(player),inventory,held,clicked,face,block,data);}catch(java.lang.reflect.InvocationTargetException failed){error=failed.getCause().getClass().getSimpleName()+":"+failed.getCause().getMessage();}
                out.writeUTF(error);out.writeInt(events.size());for(var event:events)out.writeUTF(event);
                out.writeInt(alias.count());out.writeBoolean(inventory.inventoryContainer.getHeldItemCache()==ItemCache.AIR);out.writeInt(inventory.inventoryContainer.getHeldItemCache().count());
                out.writeInt(held.getCount());out.writeInt(w.getRawBlockAt(clicked.x,clicked.y,clicked.z,0));out.writeInt(w.getRawBlockAt(target.x,target.y,target.z,0));out.writeLong(w.bucketPredictionRevision());
            }
        }
        System.out.println("Original item placement stage: 1680 cases; native constructors, default meta zero, real inventory/cache, modes, callbacks, failures and integer count edges.");
    }
}
