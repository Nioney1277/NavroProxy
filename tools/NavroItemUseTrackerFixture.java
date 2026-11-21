import ac.navro.anticheat.Navro;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.util.ItemUtil;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.food.Food;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.ProtocolInfo;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroItemUseTrackerFixture {
    static Unsafe unsafe;static List<Item> pool=new ArrayList<>();
    public static final class Session extends Player {boolean using;public Session(){super(null,0L,null);}@Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}@Override public boolean isUsingItem(){return using;}}
    public static final class Named extends StringItemBase {public Named(String name){super(name,"fixture");}}
    static void set(Object o,Class<?> c,String name,Object value)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object get(Object o,String name)throws Exception{var f=ItemUseTracker.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Item item(int index){return index==-1?null:index==-2?Item.AIR_ITEM:pool.get(index);}
    static Map<String,Object> view(Item item){var m=new LinkedHashMap<String,Object>();if(item==null){m.put("kind","null");return m;}if(item==Item.AIR_ITEM){m.put("kind","air");return m;}int index=item.getNamedTag().getInt("fixture_profile");m.put("kind","item");m.put("index",index);m.put("count",item.getCount());m.put("input",item==pool.get(index));return m;}
    static Map<String,Object> profile(NavroPlayer p,Item i){var m=new LinkedHashMap<String,Object>();m.put("id",i.getId());m.put("name",ItemUtil.identifier(p,i));m.put("runtime",ItemUtil.runtimeId(p,i));m.put("canRelease",i.canRelease());m.put("duration",i.getUseDuration());m.put("component",ItemUtil.hasUseDurationComponent(p,i));Item positive=i.clone();positive.setCount(1);m.put("emptyPositive",positive.isNull());String error="";boolean food=false;try{food=Food.getByRelative(i)!=null;}catch(Exception e){error=e.getClass().getSimpleName();}m.put("food",food);m.put("foodError",error);return m;}
    static Map<String,Object> snapshot(ItemUseTracker.Snapshot s){if(s==null)return null;var m=new LinkedHashMap<String,Object>();m.put("item",view(s.usedItem()));m.put("javaItemId",s.javaItemId());m.put("dirty",s.dirtyUsing()==null?-1:s.dirtyUsing().ordinal());m.put("duration",s.useDuration());m.put("latch",s.useButtonLatched());m.put("pendingRelease",s.buttonReleasePending());m.put("await",s.awaitingMetadataStartConfirmation());m.put("using",s.predictionUsingItem());m.put("action",s.predictionAction());m.put("inputTick",s.predictionInputTick());m.put("factor",Float.floatToRawIntBits(s.predictionItemSlowdownFactor()));m.put("since",s.sinceTridentUse());return m;}
    static Map<String,Object> state(NavroPlayer p,ItemUseTracker t,ItemUseTracker.Snapshot[] saved)throws Exception{var m=new LinkedHashMap<String,Object>();m.put("used",view(t.getUsedItem()));String[] fields={"javaItemId","useDuration","useButtonLatched","buttonReleasePending","awaitingMetadataStartConfirmation","predictionUsingItem","predictionAction","predictionInputTick","pendingMetadataFalsePackets","lastMetadataTrueSentPlayerTick","lastMetadataFalseAckPlayerTick","lastAcknowledgedMetadataUsing"};for(String f:fields)m.put(f,get(t,f));m.put("dirty",t.getDirtyUsing()==null?-1:t.getDirtyUsing().ordinal());m.put("factor",Float.floatToRawIntBits(t.getPredictionItemSlowdownFactor()));m.put("slowdown",t.hasPredictionItemSlowdown());m.put("spear",t.isUsingSpear());m.put("since",p.sinceTridentUse);m.put("flags",new TreeSet<>(p.getFlagTracker().cloneFlags()));m.put("snapshot",snapshot(t.snapshot()));List<Object> slots=new ArrayList<>();for(var s:saved){if(s==null)slots.add(null);else{var v=snapshot(s);v.put("liveAlias",s.usedItem()==t.getUsedItem());slots.add(v);}}m.put("saved",slots);m.put("counts",pool.stream().map(Item::getCount).toList());return m;}
    static void definition(String id,int type)throws Exception{CompoundTag root=switch(type){case 0->new CompoundTag();case 1->new CompoundTag().putCompound("components",new CompoundTag().putInt("minecraft:use_duration",0));case 2->new CompoundTag().putCompound("components",new CompoundTag().putCompound("item_properties",new CompoundTag().putInt("use_duration",0)));case 3->new CompoundTag().putString("components","bad");case 4->new CompoundTag().putCompound("components",new CompoundTag());case 5->new CompoundTag().putCompound("components",new CompoundTag().putString("item_properties","bad"));default->null;};var d=(CustomItemDefinition)unsafe.allocateInstance(CustomItemDefinition.class);for(String field:new String[]{"nbt","nbt465","nbt419"})set(d,CustomItemDefinition.class,field,root);var f=Item.class.getDeclaredField("CUSTOM_ITEM_DEFINITIONS");f.setAccessible(true);((Map<String,CustomItemDefinition>)f.get(null)).put(id,d);}
    static int randomItem(Random r){int n=r.nextInt(pool.size()+2);return n<pool.size()?n:n==pool.size()?-1:-2;}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);set(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();
        int[] ids={261,455,471,381,772,373,260,1,0,401,444,325};for(int id:ids)pool.add(Item.get(id,0,1));
        for(String name:new String[]{"minecraft:wooden_spear","minecraft:stone_spear","minecraft:iron_spear","minecraft:golden_spear","minecraft:diamond_spear","minecraft:netherite_spear","minecraft:ominous_bottle","minecraft:shield"})pool.add(Item.fromString(name));pool.add(new Named("MINECRAFT:WOODEN_SPEAR"));
        for(int k=0;k<7;k++){String name="fixture:component_"+k;definition(name,k);pool.add(new Named(name));}
        for(int index=0;index<pool.size();index++)pool.get(index).setNamedTag(new CompoundTag().putInt("fixture_profile",index));
        Gson gson=new GsonBuilder().serializeNulls().create();Random random=new Random(0x555345545241434bL);int sessions=64,steps=256;int[] ints={0,1,-1,10,9,455,261,471,381,772,373,Integer.MIN_VALUE,Integer.MAX_VALUE};long[] ticks={0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE};
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Session)unsafe.allocateInstance(Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(player,NavroPlayer.class,"session",session);
            List<Object> profiles=new ArrayList<>();for(Item item:pool){var p=profile(player,item);for(int count:new int[]{-1,0,1,Integer.MIN_VALUE,Integer.MAX_VALUE}){Item copy=item.clone();copy.setCount(count);if(!p.equals(profile(player,copy)))throw new AssertionError("native item use properties depend on count");}profiles.add(p);}
            out.write(gson.toJson(Map.of("magic","NIU1","sessions",sessions,"steps",steps,"profiles",profiles,"air",profile(player,Item.AIR_ITEM))));out.newLine();
            for(int s=0;s<sessions;s++){
                for(Item item:pool)item.setCount(1);player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);session=(Session)unsafe.allocateInstance(Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(player,NavroPlayer.class,"session",session);set(player,PlayerData.class,"flagTracker",new FlagTracker());set(player,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(player));var tracker=new ItemUseTracker(player);set(player,NavroPlayer.class,"itemUseTracker",tracker);player.tick=s*100L;ItemUseTracker.Snapshot[] saved=new ItemUseTracker.Snapshot[4];
                for(int step=0;step<steps;step++){
                    int op=random.nextInt(28),a=randomItem(random),v=ints[random.nextInt(ints.length)],slot=random.nextInt(4);boolean b=random.nextBoolean(),c=random.nextBoolean();long tick=random.nextBoolean()?player.tick:ticks[random.nextInt(ticks.length)];int factor=new int[]{Float.floatToRawIntBits(1F),Float.floatToRawIntBits(0.122499995F),0x7fc00000,0x7f800000,0xff800000}[random.nextInt(5)];
                    switch(step){case 0->{op=3;a=0;v=261;b=true;}case 1->op=11;case 2->op=10;case 3->{op=15;slot=0;}case 4->{op=8;b=false;}case 5->op=5;case 6->{op=2;b=true;}case 7->{op=9;b=false;}case 8->{op=3;a=1;v=455;b=false;}case 9->op=11;case 10->op=12;case 11->{op=4;a=1;}case 12->{op=9;b=true;}case 13->op=11;case 14->op=12;case 15->{op=16;slot=0;}case 16->op=10;case 17->{op=14;a=7;}case 18->{op=6;b=false;}case 19->{op=25;v=Integer.MAX_VALUE;}case 20->{op=8;b=false;}case 21->{op=9;b=false;}case 22->{op=0;tick=Long.MIN_VALUE;}case 23->{op=9;b=false;}case 24->{op=3;a=0;v=261;b=false;}default->{}}
                    Map<String,Object> row=new LinkedHashMap<>();row.put("session",s);row.put("step",step);row.put("op",op);row.put("item",a);row.put("value",v);row.put("slot",slot);row.put("b",b);row.put("c",c);row.put("tick",tick);row.put("factor",factor);Object result=null;String error="";
                    try{switch(op){case 0->player.tick=tick;case 1->player.getFlagTracker().set(new int[]{-1,4,32,97,100}[Math.floorMod(v,5)],b);case 2->session.using=b;case 3->tracker.use(item(a),v,b);case 4->result=tracker.resumeLatchedUse(item(a));case 5->tracker.release();case 6->tracker.finishAuthInput(b);case 7->tracker.onMetadataUseStateChanged(b,c);case 8->tracker.onMovementMetadataSent(b);case 9->tracker.onMovementMetadataAcknowledged(b);case 10->tracker.snapshotPredictionUseState();case 11->tracker.applyPredictedUseStart();case 12->tracker.preTick();case 13->tracker.postTick();case 14->tracker.onHeldItemChanged(item(a));case 15->saved[slot]=tracker.snapshot();case 16->tracker.restore(b?saved[slot]:null);case 17->tracker.setUsedItem(item(a));case 18->tracker.setJavaItemId(v);case 19->tracker.setDirtyUsing(v<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(v,3)]);case 20->tracker.setUseDuration(v);case 21->tracker.setUseButtonLatched(b);case 22->tracker.setButtonReleasePending(b);case 23->player.sinceTridentUse=v;case 24->result=new TreeSet<>(tracker.snapshotPredictionFlags(tick));case 25->set(tracker,ItemUseTracker.class,"pendingMetadataFalsePackets",v);case 26->tracker.restore(new ItemUseTracker.Snapshot(item(a),v,v<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(v,3)],v,b,c,c,b,c,tick,Float.intBitsToFloat(factor),v));case 27->{int index=Math.floorMod(v,pool.size());pool.get(index).setCount(v);}}}catch(Exception e){error=e.getClass().getSimpleName();}
                    row.put("result",result);row.put("error",error);row.put("state",state(player,tracker,saved));out.write(gson.toJson(row));out.newLine();
                }
            }
        }
        System.out.println("Original ItemUseTracker: "+(sessions*steps)+" continuous operations, native items/components/food, sentinel identity, snapshots, ACK/latch/flags and numeric edges.");
    }
}
