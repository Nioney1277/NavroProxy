package ac.navro.anticheat.compensated.world.base;

import ac.navro.anticheat.Navro;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.compensated.*;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.compensated.world.base.CompensatedWorld;
import ac.navro.anticheat.data.block.*;
import ac.navro.anticheat.data.inventory.ItemCache;
import ac.navro.anticheat.check.api.Check;
import ac.navro.anticheat.check.api.holder.CheckHolder;
import ac.navro.anticheat.check.impl.place.*;
import ac.navro.anticheat.collision.sampling.NearbyBlockSampler;
import ac.navro.anticheat.util.ItemUtil;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.inventory.transaction.data.UseItemData;
import cn.nukkit.item.*;
import cn.nukkit.level.Level;
import cn.nukkit.math.*;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.*;
import com.google.gson.Gson;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroClickBlockFixture {
    static Unsafe unsafe;static NavroPlayer current;static CheckHolder postChecks;
    static boolean capture,predictionFailure,directFailure,rotationFailure,rotateSlot,switchChecks;
    static List<String> events=new ArrayList<>();static int sendFailure,sends;
    static void set(Object o,Class<?> c,String name,Object value)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static String p(BlockVector3 p){return p.x+","+p.y+","+p.z;}
    static int[] pos(BlockVector3 p){return new int[]{p.x,p.y,p.z};}
    public static final class Authority extends Level {
        int[] ids;public Authority(){super(null,"","",null);}
        @Override public Block getBlock(int x,int y,int z,int layer){return Block.get(layer==0?1:89);}
        @Override public int getBlockRuntimeId(GameVersion version,int x,int y,int z,int layer){return ids[layer];}
    }
    public static final class Session extends Player {
        Authority authority;public Session(){super(null,0L,null);}
        @Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}
        @Override public Level getLevel(){return authority;}
        @Override public boolean hasPermission(String permission){return true;}
        @Override public boolean dataPacket(DataPacket packet){var b=(UpdateBlockPacket)packet;events.add("send:"+b.x+","+b.y+","+b.z+":"+b.dataLayer+":"+b.blockRuntimeId+":"+b.flags);if(++sends==sendFailure)throw new IllegalStateException("send failed");return true;}
    }
    public static final class Checks extends CheckHolder {
        Check air,rotation;public Checks(){super(null);}
        @Override public Check get(Object key){if(key==AirLiquidPlace.class){if(capture)events.add("check:AirLiquidPlace");return air;}if(key==RotationPlace.class){if(capture)events.add("check:RotationPlace");return rotation;}return null;}
    }
    public static final class Rotation extends RotationPlace {
        public Rotation(){super(null);}
        @Override public void predictedPlacement(UseItemData data,BlockVector3 target,int id){events.add("rotation:"+p(target)+":"+id+":"+current.compensatedInventory.inventoryContainer.getHeldItemCache().count());if(rotateSlot){current.compensatedInventory.heldItemSlot=(current.compensatedInventory.heldItemSlot+1)%9;events.add("slot:"+current.compensatedInventory.heldItemSlot);}if(rotationFailure)throw new IllegalStateException("rotation failed");}
    }
    static Checks checks(boolean present,boolean air,boolean rotation)throws Exception{if(!present)return null;var c=(Checks)unsafe.allocateInstance(Checks.class);c.air=air?new AirLiquidPlace(current):null;c.rotation=rotation?(Rotation)unsafe.allocateInstance(Rotation.class):null;return c;}
    static final class World extends CompensatedWorldImpl {
        int clickedFull;String name;World(){super(null);}
        @Override protected void updateCachedBlock(int x,int y,int z,int layer,int network){var c=getChunk(x>>4,z>>4);if(c!=null)c.setFullBlock(x&15,y,z&15,layer,network);}
        @Override public int getRawBlockAt(int x,int y,int z,int layer){if(capture)events.add("read:"+x+","+y+","+z+":"+layer);var c=getChunk(x>>4,z>>4);return c==null||y<getMinY()||y>=getHeightY()?0:c.getFullBlock(x&15,y,z&15,layer);}
        @Override public NavroBlockState getBlockState(BlockVector3 position,int layer){int full=getRawBlockAt(position.x,position.y,position.z,layer);Block block=Block.get(full>>Block.DATA_BITS,full&Block.DATA_MASK,null,position.x,position.y,position.z,layer);try{var ctor=NetworkBlockState.class.getDeclaredConstructor(int.class,int.class,CompoundTag.class);ctor.setAccessible(true);var state=ctor.newInstance(full,full,new CompoundTag().putString("name",full==clickedFull?name:"").putCompound("states",new CompoundTag()));return new NavroBlockState(block,state,position,layer);}catch(Exception e){throw new AssertionError(e);}}
        @Override public CompoundTag getBlockEntityTag(int x,int y,int z){if(capture)events.add("nbt:"+x+","+y+","+z);return super.getBlockEntityTag(x,y,z);}
        @Override public void predictLocalBlock(BlockVector3 position,int layer,int full){events.add("predict:"+p(position)+":"+layer+":"+full);if(predictionFailure)throw new IllegalStateException("prediction failed");predictBucketNetworkBlock(position.x,position.y,position.z,layer,full);if(switchChecks)try{set(current,NavroPlayer.class,"checkHolder",postChecks);}catch(Exception e){throw new AssertionError(e);}}
        @Override public void updateLegacyBlock(int x,int y,int z,int layer,int full){if(capture){events.add("direct:"+x+","+y+","+z+":"+layer+":"+full);if(directFailure)throw new IllegalStateException("direct failed");}updateBlock(x,y,z,layer,full);}
    }
    static void load(World w,BlockVector3 p){if(w.getChunk(p.x>>4,p.z>>4)==null)w.put(p.x>>4,p.z>>4,CompensatedWorld.CachedChunk.network(-64,320,24,0));}
    static Map<String,Object> item(NavroPlayer player,Item item){if(item==null)return null;Map<String,Object> m=new LinkedHashMap<>();m.put("id",item.getId());m.put("damage",item.getDamage());m.put("count",item.getCount());m.put("block",item.getBlockId());m.put("empty",item.isNull());m.put("canPlace",item.canBePlaced());m.put("mapped",item.getBlockUnsafe()!=null);Block d=item.getBlockUnsafe()==null?Block.get(0):Block.get(item.getBlockUnsafe().getId(),0);m.put("defaultId",d.getId());m.put("defaultFull",d.getFullId());m.put("name",ItemUtil.identifier(player,item));m.put("runtime",ItemUtil.runtimeId(player,item));m.put("tag",item.hasCompoundTag());return m;}
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));var config=new ac.navro.anticheat.config.Config();set(config,ac.navro.anticheat.config.Config.class,"collisionSampling",false);Navro.setConfig(config);Block.init();RuntimeItems.init();Item.init();
        var method=ItemTransactionValidator.class.getDeclaredMethod("handleClickBlock",UseItemData.class);method.setAccessible(true);
        int[][] blockInputs={{0,0},{1,0},{8,0},{9,7},{10,0},{561,0},{78,7},{175,2},{420,0},{96,3},{64,0},{64,8},{193,8},{107,0},{461,4},{118,0},{92,0},{667,4},{449,0},{25,0},{527,3},{63,0},{462,2},{46,0},{569,0},{199,0},{513,0},{2047,0}};
        int[][] numeric={{1,0},{0,0},{325,0},{325,8},{325,10},{325,11},{325,5},{255-420,0},{89,0},{259,0},{351,15},{260,0}};String[] names={"minecraft:spruce_planks","minecraft:candle","minecraft:player_head","minecraft:air","minecraft:bone_meal","minecraft:apple"};int[] counts={1,2,7,0,-1,Integer.MIN_VALUE,Integer.MAX_VALUE};
        BlockVector3[] points={new BlockVector3(8,64,8),new BlockVector3(15,64,15),new BlockVector3(-1,64,-1),new BlockVector3(0,-64,0),new BlockVector3(0,319,0),new BlockVector3(0,320,0),new BlockVector3(Integer.MAX_VALUE,64,Integer.MIN_VALUE),new BlockVector3(0,Integer.MIN_VALUE,0)};
        Random r=new Random(0x434c49434bL);Gson gson=new Gson();int cases=8192;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){out.write("{\"magic\":\"NCB1\",\"cases\":"+cases+"}\n");
            for(int i=0;i<cases;i++){
                capture=false;events.clear();sends=0;current=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Session)unsafe.allocateInstance(Session.class);set(current,NavroPlayer.class,"session",session);var inventory=new CompensatedInventory(current);set(current,NavroPlayer.class,"compensatedInventory",inventory);inventory.heldItemSlot=i%9;for(int s=0;s<9;s++)inventory.inventoryContainer.set(s,Item.get(260,0,17+s));
                Item original;if(r.nextBoolean()){var n=numeric[r.nextInt(numeric.length)];original=Item.get(n[0],n[1],1);}else original=Item.fromString(names[r.nextInt(names.length)]);original.setCount(counts[r.nextInt(counts.length)]);original.setNamedTag(new CompoundTag().putString("payload","held"));inventory.inventoryContainer.set(inventory.heldItemSlot,original);Item held=inventory.inventoryContainer.getHeldItemData();var alias=inventory.inventoryContainer.getHeldItemCache();var aliases=new IdentityHashMap<ItemCache,Integer>();for(int s=0;s<9;s++)aliases.put(inventory.inventoryContainer.get(s),s);
                int game=i%8;current.gameType=game==7?null:GameType.from(game);current.tickSinceBlockResync=13;boolean sneak=r.nextBoolean();current.setInputData(sneak?EnumSet.of(AuthInputAction.SNEAKING):EnumSet.noneOf(AuthInputAction.class));
                boolean holder=i%4!=0,air=i%3==0,rot=i%3!=1,postHolder=i%7!=0,postRot=i%5!=0;switchChecks=i%3==0;predictionFailure=i%29==0;directFailure=i%31==0;rotationFailure=i%23==0;rotateSlot=i%11==0;sendFailure=i%17==0?1+r.nextInt(4):0;
                set(current,NavroPlayer.class,"checkHolder",checks(holder,air,rot));postChecks=checks(postHolder,air,postRot);set(current,NavroPlayer.class,"trapdoorSampler",new NearbyBlockSampler(current,NearbyBlockSampler.Kind.TRAPDOOR));
                var w=new World();set(current,NavroPlayer.class,"compensatedWorld",w);var clicked=points[r.nextInt(points.length)].clone();BlockFace face=i%19==0?null:BlockFace.fromIndex(r.nextInt(6));int faceIndex=face==null?-1:face.getIndex();var target=face==null?clicked.clone():clicked.getSide(face);var lower=new BlockVector3(clicked.x,clicked.y-1,clicked.z);
                var b=blockInputs[r.nextInt(blockInputs.length)];w.clickedFull=Block.get(b[0],b[1]).getFullId();w.name=i%13==0?new String[]{"minecraft:air","minecraft:cave_air","minecraft:void_air"}[i%3]:"";int layerOne=Block.get(i%3==0?9:0).getFullId(),targetFull=Block.get(i%5==0?420:1).getFullId(),lowerFull=Block.get(i%7==0?193:64).getFullId();boolean loaded=i%9!=0;
                if(loaded){load(w,clicked);load(w,target);load(w,lower);}w.updateBlock(clicked,0,w.clickedFull);w.updateBlock(clicked,1,layerOne);w.updateBlock(target,0,targetFull);w.updateBlock(lower,0,lowerFull);int tagKind=i%4;w.updateBlockEntityTag(clicked.x,clicked.y,clicked.z,switch(tagKind){case 0->new CompoundTag();case 1->new CompoundTag().putCompound("book",new CompoundTag());case 2->new CompoundTag().putString("vault_state","active");default->new CompoundTag().putString("hasBook","true");});
                boolean authority=i%7!=0;int[] ids={r.nextInt(),r.nextInt()};if(authority){session.authority=(Authority)unsafe.allocateInstance(Authority.class);session.authority.ids=ids;}
                var data=new UseItemData();data.blockPos=i%41==0?null:clicked;data.face=face;data.hotbarSlot=(inventory.heldItemSlot+3)%9;data.itemInHand=i%37==0?null:i%5==0?Item.get(261,0,1):held.clone();data.clientInteractPrediction=i%7==0?0:i%7==1?2:1;data.clickPos=i%11==0?null:new Vector3f(0,new float[]{0,0.8124f,Math.nextUp(0.8124f),Float.NaN}[i%4],0);
                Map<String,Object> record=new LinkedHashMap<>();record.put("slot",inventory.heldItemSlot);record.put("game",game);record.put("held",item(current,held));record.put("claim",item(current,data.itemInHand));record.put("pos",data.blockPos==null?null:pos(clicked));record.put("clicked",pos(clicked));record.put("target",pos(target));record.put("lower",pos(lower));record.put("face",faceIndex);record.put("prediction",data.clientInteractPrediction);record.put("cursorY",data.clickPos==null?null:Float.floatToRawIntBits(data.clickPos.y));record.put("hotbar",data.hotbarSlot);record.put("sneak",sneak);record.put("loaded",loaded);record.put("full",w.clickedFull);record.put("name",w.name);record.put("layerOne",layerOne);record.put("targetFull",targetFull);record.put("lowerFull",lowerFull);record.put("tagKind",tagKind);record.put("holder",holder);record.put("air",air);record.put("rotation",rot);record.put("postHolder",postHolder);record.put("postRotation",postRot);record.put("switch",switchChecks);record.put("predictFail",predictionFailure);record.put("directFail",directFailure);record.put("rotationFail",rotationFailure);record.put("rotateSlot",rotateSlot);record.put("sendFail",sendFailure);record.put("authority",authority);record.put("authorityIds",ids);
                List<Object> buckets=new ArrayList<>();for(int d:new int[]{0,8,10,11})buckets.add(item(current,Item.get(325,d,1)));record.put("buckets",buckets);List<Object> initial=new ArrayList<>();for(int s=0;s<9;s++)initial.add(item(current,inventory.inventoryContainer.get(s).getData()));record.put("initial",initial);
                capture=true;boolean result=false;String error="";try{result=(boolean)method.invoke(new ItemTransactionValidator(current),data);}catch(java.lang.reflect.InvocationTargetException e){error=e.getCause().getClass().getSimpleName();}capture=false;
                record.put("result",result);record.put("error",error);record.put("events",new ArrayList<>(events));record.put("heldSlotAfter",inventory.heldItemSlot);record.put("resync",current.tickSinceBlockResync);record.put("aliasCount",alias.count());List<Object> after=new ArrayList<>();List<Integer> cacheIds=new ArrayList<>();for(int s=0;s<9;s++){after.add(item(current,inventory.inventoryContainer.get(s).getData()));cacheIds.add(aliases.getOrDefault(inventory.inventoryContainer.get(s),-1));}record.put("after",after);record.put("cacheIds",cacheIds);List<Integer> blocks=new ArrayList<>();for(var p:new BlockVector3[]{clicked,target,lower})for(int layer=0;layer<2;layer++)blocks.add(w.getRawBlockAt(p.x,p.y,p.z,layer));record.put("blocks",blocks);record.put("revision",w.bucketPredictionRevision());out.write(gson.toJson(record));out.newLine();
            }
        }
        System.out.println("Original complete click-block dispatch: "+cases+" cases, actual helpers/world/cache/check lookup, corrections, failures and inventory mutations.");
    }
}
