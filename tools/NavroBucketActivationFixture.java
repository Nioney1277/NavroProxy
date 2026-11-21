package ac.navro.anticheat.compensated.world.base;

import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.compensated.cache.container.impl.PlayerContainerCache;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.collision.sampling.NearbyBlockSampler;
import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.item.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.types.GameType;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroBucketActivationFixture {
    static final List<String> events=new ArrayList<>();static boolean capture,predictionFails,setFails;static int directFailure;
    static void set(Object o,Class<?> c,String name,Object value)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static String point(BlockVector3 p){return p.x+","+p.y+","+p.z;}
    static void pos(DataOutputStream out,BlockVector3 p)throws Exception{out.writeInt(p.x);out.writeInt(p.y);out.writeInt(p.z);}
    public static final class Session extends Player {
        public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}
    }
    static final class Inventory extends PlayerContainerCache {
        Inventory(CompensatedInventory inventory){super(inventory);}
        @Override public void set(int slot,Item data){
            if(capture){events.add("set:"+slot+":"+data.getId()+":"+data.getDamage()+":"+data.getCount()+":"+data.hasCompoundTag());if(setFails)throw new IllegalStateException("inventory write failed");}
            super.set(slot,data);
        }
    }
    static final class World extends CompensatedWorldImpl {
        World(){super(null);}
        @Override protected void updateCachedBlock(int x,int y,int z,int layer,int network){var c=getChunk(x>>4,z>>4);if(c!=null)c.setFullBlock(x&15,y,z&15,layer,network);}
        @Override public int getRawBlockAt(int x,int y,int z,int layer){
            if(capture)events.add("read:"+x+","+y+","+z+":"+layer);var c=getChunk(x>>4,z>>4);return c==null||y<getMinY()||y>=getHeightY()?0:c.getFullBlock(x&15,y,z&15,layer);
        }
        @Override public NavroBlockState getBlockState(BlockVector3 p,int layer){int full=getRawBlockAt(p.x,p.y,p.z,layer);return new NavroBlockState(Block.get(full>>Block.DATA_BITS,full&Block.DATA_MASK,null,p.x,p.y,p.z,layer),p,layer);}
        @Override public void predictLocalBlock(BlockVector3 p,int layer,int full){events.add("predict:"+point(p)+":"+layer+":"+full);if(predictionFails)throw new IllegalStateException("prediction failed");predictBucketNetworkBlock(p.x,p.y,p.z,layer,full);}
        @Override public void updateLegacyBlock(int x,int y,int z,int layer,int full){
            if(capture){events.add("direct:"+x+","+y+","+z+":"+layer+":"+full);if(directFailure==1)throw new IllegalStateException("direct write failed before");}
            updateBlock(x,y,z,layer,full);if(capture&&directFailure==2)throw new IllegalStateException("direct write failed after");
        }
    }
    static void load(World w,BlockVector3 p){if(w.getChunk(p.x>>4,p.z>>4)==null)w.put(p.x>>4,p.z>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    public static void main(String[] args)throws Exception {
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));
        var config=new ac.navro.anticheat.config.Config();set(config,ac.navro.anticheat.config.Config.class,"collisionSampling",false);Navro.setConfig(config);Block.init();RuntimeItems.init();Item.init();
        var bucket=ItemTransactionValidator.class.getDeclaredMethod("handleBucketUse",CompensatedInventory.class,Item.class,BlockVector3.class,BlockVector3.class,Block.class);bucket.setAccessible(true);
        var scaffold=ItemTransactionValidator.class.getDeclaredMethod("isScaffoldingPlacement",Item.class,Block.class,BlockVector3.class);scaffold.setAccessible(true);
        var activate=ItemTransactionValidator.class.getDeclaredMethod("predictActivatedBlock",BlockVector3.class,Block.class);activate.setAccessible(true);
        List<Integer> activationIds=new ArrayList<>();for(int id=0;id<Block.MAX_BLOCK_ID;id++){Block b=Block.get(id);if(b instanceof BlockTrapdoor||b instanceof BlockDoor||b instanceof BlockFenceGate)activationIds.add(id);}activationIds.addAll(List.of(1,54,61,62,451,452,0,2047));
        int[] damages={0,8,10,11,2,3,5,65535,-1,Integer.MIN_VALUE,Integer.MAX_VALUE};int[] blockIds={0,1,8,9,10,11,561,78,95,420,415,2047};int[] metadata={0,1,7,8,15,8191};
        BlockVector3[] points={new BlockVector3(8,64,8),new BlockVector3(15,64,15),new BlockVector3(-1,64,-1),new BlockVector3(0,-64,0),new BlockVector3(0,319,0),new BlockVector3(0,320,0),new BlockVector3(0,-65,0),new BlockVector3(Integer.MAX_VALUE,64,Integer.MIN_VALUE),new BlockVector3(0,Integer.MIN_VALUE,0)};
        Random random=new Random(0x4255434b4554L);int cases=8192;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4e424131);out.writeInt(cases);
            for(int i=0;i<cases;i++) {
                int mode=i%3;capture=false;events.clear();var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);set(player,NavroPlayer.class,"session",unsafe.allocateInstance(Session.class));
                var inventory=new CompensatedInventory(player);set(inventory,CompensatedInventory.class,"inventoryContainer",new Inventory(inventory));set(player,NavroPlayer.class,"compensatedInventory",inventory);inventory.heldItemSlot=i%9;player.gameType=GameType.from(i%7);
                int inputId=mode==2&&i%5!=0?255-420:i%13==0?1:325,inputDamage=damages[random.nextInt(damages.length)],count=new int[]{-1,0,1,7}[random.nextInt(4)];Item raw=Item.get(inputId,inputDamage,count);raw.setCount(count);raw.setNamedTag(new CompoundTag().putString("payload","held"));inventory.inventoryContainer.set(inventory.heldItemSlot,raw);
                Item held=inventory.inventoryContainer.getHeldItemData();var alias=inventory.inventoryContainer.getHeldItemCache();var w=new World();set(player,NavroPlayer.class,"compensatedWorld",w);set(player,NavroPlayer.class,"trapdoorSampler",new NearbyBlockSampler(player,NearbyBlockSampler.Kind.TRAPDOOR));
                int blockId=mode!=1?blockIds[random.nextInt(blockIds.length)]:activationIds.get(random.nextInt(activationIds.size())),meta=mode==0?metadata[random.nextInt(metadata.length)]:random.nextInt(Block.DATA_SIZE);
                Block block=Block.get(blockId,meta,null,0,0,0,0);var clicked=points[random.nextInt(points.length)].clone();var target=clicked.getSide(cn.nukkit.math.BlockFace.fromIndex(random.nextInt(6)));
                var lower=new BlockVector3(clicked.x,clicked.y-1,clicked.z);int lowerFull=switch(random.nextInt(4)){case 0->Block.get(blockId,meta^8).getFullId();case 1->64<<Block.DATA_BITS;case 2->1<<Block.DATA_BITS;default->block.getFullId();};
                int layerFull=Block.get(blockIds[random.nextInt(blockIds.length)],metadata[random.nextInt(metadata.length)]).getFullId();boolean loaded=i%7!=0;
                predictionFails=i%23==0;setFails=i%29==0;directFailure=i%31==0?1:i%37==0?2:0;
                if(loaded){load(w,clicked);load(w,target);load(w,lower);}w.updateBlock(clicked,0,block.getFullId());w.updateBlock(clicked,1,layerFull);w.updateBlock(lower,0,lowerFull);
                boolean nullPos=mode!=0&&i%41==0,nullBlock=mode!=0&&i%43==0,nullHeld=mode==2&&i%47==0;
                out.writeInt(mode);out.writeInt(player.gameType.ordinal());out.writeInt(inventory.heldItemSlot);out.writeInt(held.getId());out.writeInt(held.getDamage());out.writeInt(held.getCount());out.writeInt(held.getBlockId());out.writeBoolean(nullHeld);out.writeInt(blockId);out.writeInt(meta);out.writeInt(block.getFullId());pos(out,clicked);pos(out,target);out.writeInt(layerFull);out.writeInt(lowerFull);out.writeBoolean(loaded);out.writeBoolean(predictionFails);out.writeBoolean(setFails);out.writeInt(directFailure);out.writeBoolean(nullPos);out.writeBoolean(nullBlock);
                capture=true;boolean result=true;String error="";
                try{var validator=new ItemTransactionValidator(player);if(mode==0)result=(boolean)bucket.invoke(validator,inventory,held,clicked,target,block);else if(mode==1)activate.invoke(validator,nullPos?null:clicked,nullBlock?null:block);else result=(boolean)scaffold.invoke(validator,nullHeld?null:held,nullBlock?null:block,nullPos?null:target);}
                catch(java.lang.reflect.InvocationTargetException e){error=e.getCause().getClass().getSimpleName()+":"+e.getCause().getMessage();}
                capture=false;out.writeBoolean(result);out.writeUTF(error);out.writeInt(events.size());for(var e:events)out.writeUTF(e);
                Item after=inventory.inventoryContainer.getHeldItemData();out.writeInt(after.getId());out.writeInt(after.getDamage());out.writeInt(after.getCount());out.writeBoolean(after.hasCompoundTag());out.writeBoolean(inventory.inventoryContainer.getHeldItemCache()==alias);out.writeInt(alias.count());
                for(var p:new BlockVector3[]{clicked,target,lower})for(int layer=0;layer<2;layer++)out.writeInt(w.getRawBlockAt(p.x,p.y,p.z,layer));out.writeLong(w.bucketPredictionRevision());
            }
        }
        System.out.println("Current original bucket/activation/scaffolding helpers: "+cases+" cases; real cache/revision, layers, boundaries, nulls, failures, native item constructors.");
    }
}
