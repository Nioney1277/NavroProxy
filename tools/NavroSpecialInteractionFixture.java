import ac.navro.anticheat.Navro;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.validator.inventory.ItemTransactionValidator;
import ac.navro.anticheat.util.ItemUtil;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.item.*;
import cn.nukkit.math.*;
import cn.nukkit.nbt.tag.*;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroSpecialInteractionFixture {
    static final List<String> events=new ArrayList<>();static boolean capture;
    public static final class Session extends Player {public Session(){super(null,0L,null);}@Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}}
    static void set(Object o,Class<?> c,String name,Object value)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static final class Held extends Item {
        final Item delegate;
        Held(Item item){super(item.getId(),item.getDamage(),item.getCount());delegate=item;}
        void event(String event){if(capture)events.add(event);}
        @Override public int getId(){event("id");return delegate.getId();}
        @Override public int getDamage(){event("damage");return delegate.getDamage();}
        @Override public int getBlockId(){event("mapped");return delegate.getBlockId();}
        @Override public boolean isNull(){event("empty");return delegate.isNull();}
        @Override public String getNamespaceId(GameVersion version){event("namespace");return delegate.getNamespaceId(version);}
    }
    static final class World extends CompensatedWorldImpl {
        CompoundTag tag;boolean failure;
        World(){super(null);}
        @Override public CompoundTag getBlockEntityTag(int x,int y,int z){events.add("tag:"+x+","+y+","+z);if(failure)throw new IllegalStateException("block entity read failed");return tag;}
    }
    static CompoundTag tag(int kind){
        var t=new CompoundTag();return switch(kind){
            case 0->null;case 1->t;case 2->t.putByte("hasBook",0);case 3->t.putByte("hasBook",1);case 4->t.putShort("hasBook",256);case 5->t.putLong("hasBook",1L<<32);case 6->t.putFloat("hasBook",0.5f);case 7->t.putDouble("hasBook",Double.NaN);
            case 8->t.putString("hasBook","true");case 9->t.putCompound("hasBook",new CompoundTag());case 10->t.putList(new ListTag<>("hasBook"));case 11->t.putCompound("book",new CompoundTag());case 12->t.putString("book","book");case 13->t.putInt("hasBook",0).putCompound("book",new CompoundTag());case 14->t.putString("hasBook","true").putCompound("book",new CompoundTag());case 15->t.putByte("hasBook",1).putString("book","not a compound");
            case 16->t.putString("vault_state","AcTiVe");case 17->t.putString("vault_state","inactive").putString("state","active");case 18->t.putString("vault_state","").putString("state","ACTIVE");case 19->t.putString("state","");case 20->t.putInt("vault_state",123).putCompound("state",new CompoundTag());case 21->t.putFloat("vault_state",Float.NaN);case 22->t.putCompound("vault_state",new CompoundTag()).putString("state","active");case 23->t.putString("vault_state","").putCompound("state",new CompoundTag());case 24->t.putString("vault_state","active").putCompound("state",new CompoundTag());
            case 25->t.putInt("hasBook",65536);case 26->t.putLong("hasBook",Long.MIN_VALUE);case 27->t.putDouble("hasBook",Double.POSITIVE_INFINITY);case 28->t.putInt("vault_state",0).putString("state","active");case 29->t.putShort("state",42);case 30->t.putByteArray("hasBook",new byte[]{1});case 31->t.putByteArray("vault_state",new byte[]{1});case 32->t.putByteArray("state",new byte[]{1});case 33->t.putString("vault_state"," ACTIVE ");case 34->t.putInt("hasBook",0).putList(new ListTag<>("book"));case 35->t.putCompound("book",new CompoundTag().putInt("pages",5));case 36->t.putString("hasBook","");default->throw new AssertionError();
        };
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();
        var interaction=ItemTransactionValidator.class.getDeclaredMethod("handleUseItemOn",BlockVector3.class,Block.class,Item.class,BlockFace.class);interaction.setAccessible(true);
        var lectern=ItemTransactionValidator.class.getDeclaredMethod("lecternHasBook",BlockVector3.class);lectern.setAccessible(true);var vault=ItemTransactionValidator.class.getDeclaredMethod("isVaultActive",BlockVector3.class,Block.class);vault.setAccessible(true);
        var bell=ItemTransactionValidator.class.getDeclaredMethod("isProperHit",BlockBell.class,BlockFace.class,float.class);bell.setAccessible(true);
        List<Integer> ids=new ArrayList<>();for(int id=0;id<Block.MAX_BLOCK_ID;id++){Block b=Block.get(id);if(b instanceof BlockCauldron||b instanceof BlockCandle||b instanceof BlockLectern||b instanceof BlockNoteblock||b instanceof BlockRespawnAnchor||b instanceof BlockSignPost||b instanceof BlockWallSign||b.getClass().getSimpleName().contains("Sign")||b instanceof BlockTNT)ids.add(id);}ids.addAll(List.of(0,1,73,74,86,91,92,462,569,2047));
        int[][] numbers={{325,8},{325,10},{325,11},{325,0},{351,15},{351,0},{259,0},{385,0},{0,0},{1,0},{89,0},{260,0},{397,3}};String[] names={"minecraft:candle","minecraft:red_candle","minecraft:player_head","minecraft:piglin_head","minecraft:glowstone","minecraft:air","minecraft:bone_meal","minecraft:water_bucket","minecraft:apple"};
        float[] ys={0,0.8124f,Math.nextUp(0.8124f),Math.nextDown(0.8124f),1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-0f,Float.MIN_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE};int[] metas={0,1,2,3,4,7,8,15,16,31,255,8191};
        Random random=new Random(0x5350454349414cL);int cases=16384;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4e535031);out.writeInt(cases);
            for(int i=0;i<cases;i++) {
                capture=false;int mode=i%4;var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);set(player,NavroPlayer.class,"session",unsafe.allocateInstance(Session.class));var w=new World();set(player,NavroPlayer.class,"compensatedWorld",w);int kind=random.nextInt(37);w.tag=tag(kind);w.failure=i%53==0;
                int id=mode==2?569:mode==3?BlockID.BELL:ids.get(random.nextInt(ids.size())),meta=metas[random.nextInt(metas.length)];Block block=Block.get(id,meta,null,0,0,0,0);
                Item item;if(random.nextBoolean()){var number=numbers[random.nextInt(numbers.length)];item=Item.get(number[0],number[1],1);}else item=Item.fromString(names[random.nextInt(names.length)]);item.setCount(new int[]{-1,0,1,7}[random.nextInt(4)]);
                boolean forced=mode==0&&i<256;
                if(forced){
                    int branch=(i/4)%16;id=new int[]{118,118,118,92,92,667,449,25,86,73,527,63,462,46,46,569}[branch];
                    meta=switch(branch){case 4->1;case 5->4;case 10->3;case 12->2;default->0;};block=Block.get(id,meta,null,0,0,0,0);
                    item=switch(branch){case 0->Item.get(325,8,7);case 1->Item.get(325,10,7);case 2->Item.get(325,11,7);case 4->Item.fromString("minecraft:candle");case 5,6,15->Item.get(0,0,7);case 7->Item.fromString("minecraft:player_head");case 10->Item.get(89,0,7);case 12->Item.get(351,15,7);case 13->Item.get(259,0,7);case 14->Item.get(385,0,7);default->Item.get(1,0,7);};item.setCount(7);
                    kind=branch==6?1:branch==15?22:kind;w.tag=tag(kind);w.failure=false;
                }
                String name=ItemUtil.identifier(player,item);int runtime=ItemUtil.runtimeId(player,item);var held=new Held(item);boolean nullHeld=mode==0&&i%31==0,nullBlock=i%43==0,nullPos=i%41==0;if(forced){nullHeld=false;nullBlock=false;nullPos=false;}var pos=new BlockVector3(i%17-8,64,i%19-9);BlockFace face=i%29==0?null:BlockFace.fromIndex(random.nextInt(6));if(forced)face=BlockFace.UP;float y=ys[random.nextInt(ys.length)];
                out.writeInt(mode);out.writeInt(id);out.writeInt(meta);out.writeInt(kind);out.writeBoolean(w.failure);out.writeBoolean(nullHeld);out.writeBoolean(nullBlock);out.writeBoolean(nullPos);out.writeInt(pos.x);out.writeInt(pos.y);out.writeInt(pos.z);out.writeInt(face==null?-1:face.getIndex());out.writeFloat(y);
                out.writeInt(item.getId());out.writeInt(item.getDamage());out.writeInt(item.getCount());out.writeInt(item.getBlockId());out.writeBoolean(item.isNull());out.writeBoolean(name!=null);if(name!=null)out.writeUTF(name);out.writeInt(runtime);
                events.clear();capture=true;boolean result=false;String error="";
                try{var v=new ItemTransactionValidator(player);result=switch(mode){case 0->(boolean)interaction.invoke(v,nullPos?null:pos,nullBlock?null:block,nullHeld?null:held,face);case 1->(boolean)lectern.invoke(v,nullPos?null:pos);case 2->(boolean)vault.invoke(v,nullPos?null:pos,nullBlock?null:block);case 3->(boolean)bell.invoke(null,nullBlock?null:(BlockBell)block,face,y);default->throw new AssertionError();};}
                catch(java.lang.reflect.InvocationTargetException e){error=e.getCause().getClass().getSimpleName();}
                capture=false;out.writeBoolean(result);out.writeUTF(error);out.writeInt(events.size());for(var event:events)out.writeUTF(event);
            }
        }
        System.out.println("Current original special interactions / lectern / vault / bell: "+cases+" cases; actual native items, getter and NBT read order, malformed NBT, null and float edges.");
    }
}
