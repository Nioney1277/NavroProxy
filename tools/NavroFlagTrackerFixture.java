import ac.navro.anticheat.Navro;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.util.EntityMetadataUtil;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.ProtocolInfo;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroFlagTrackerFixture {
    static Set<Integer> update(Random r,int mask){Set<Integer> s=new HashSet<>();int[] bits={-1,0,1,4,32,57,63,64,92,127,128,Integer.MIN_VALUE,Integer.MAX_VALUE};for(int b:bits)if(r.nextBoolean())s.add(b);if(mask%31==0)return null;return s;}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);NavroItemUseTrackerFixture.unsafe=u;NavroItemUseTrackerFixture.set(null,Server.class,"instance",u.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();
        var pool=NavroItemUseTrackerFixture.pool;for(int id:new int[]{261,455,1,0,260})pool.add(Item.get(id,0,1));for(int n=0;n<pool.size();n++)pool.get(n).setNamedTag(new CompoundTag().putInt("fixture_profile",n));
        Gson gson=new GsonBuilder().serializeNulls().create();Random random=new Random(0x464c414753L);int sessions=48,steps=192;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);var session=(NavroItemUseTrackerFixture.Session)u.allocateInstance(NavroItemUseTrackerFixture.Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;NavroItemUseTrackerFixture.set(p,NavroPlayer.class,"session",session);List<Object> profiles=new ArrayList<>();for(Item i:pool)profiles.add(NavroItemUseTrackerFixture.profile(p,i));out.write(gson.toJson(Map.of("magic","NFT1","sessions",sessions,"steps",steps,"profiles",profiles,"air",NavroItemUseTrackerFixture.profile(p,Item.AIR_ITEM))));out.newLine();
            for(int sn=0;sn<sessions;sn++){
                p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);session=(NavroItemUseTrackerFixture.Session)u.allocateInstance(NavroItemUseTrackerFixture.Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;NavroItemUseTrackerFixture.set(p,NavroPlayer.class,"session",session);var flags=new FlagTracker();NavroItemUseTrackerFixture.set(p,PlayerData.class,"flagTracker",flags);NavroItemUseTrackerFixture.set(p,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(p));var tracker=new ItemUseTracker(p);NavroItemUseTrackerFixture.set(p,NavroPlayer.class,"itemUseTracker",tracker);p.tick=sn;ItemUseTracker.Snapshot[] saved=new ItemUseTracker.Snapshot[4];
                for(int step=0;step<steps;step++){
                    int op=random.nextInt(22),index=random.nextInt(pool.size()+2)-2,value=new int[]{-1,1,4,57,63,64,127,128,Integer.MIN_VALUE,Integer.MAX_VALUE}[random.nextInt(10)],slot=random.nextInt(4);boolean b=random.nextBoolean(),c=random.nextBoolean(),nilPlayer=step%29==0;long tick=random.nextBoolean()?p.tick:random.nextLong();Set<Integer> update=update(random,step);
                    switch(step){case 0->{op=0;value=1;b=true;}case 1->{op=0;value=57;b=true;}case 2->{op=8;index=-2;}case 3->{op=1;update=new HashSet<>(Set.of(-1));b=true;nilPlayer=false;}case 4->{op=8;index=-1;}case 5->{op=1;update=new HashSet<>(Set.of(-1));b=true;nilPlayer=false;}case 6->{op=6;index=0;value=261;b=true;}case 7->{op=11;}case 8->{op=15;slot=0;}case 9->{op=13;b=false;}case 10->{op=1;update=new HashSet<>();b=true;nilPlayer=false;}case 11->{op=16;slot=0;b=true;}case 12->{op=2;update=new HashSet<>(Set.of(-1,4,1));}default->{}}
                    Map<String,Object> row=new LinkedHashMap<>();row.put("session",sn);row.put("step",step);row.put("op",op);row.put("item",index);row.put("value",value);row.put("slot",slot);row.put("b",b);row.put("c",c);row.put("nilPlayer",nilPlayer);row.put("tick",tick);row.put("update",update);Object result=null;String error="";
                    try{switch(op){case 0->flags.set(value,b);case 1->{if(c)flags.set(nilPlayer?null:p,update);else flags.set(nilPlayer?null:p,update,b);}case 2->flags.restoreFlags(update);case 3->flags.clear();case 4->flags.setFlying(b);case 5->flags.flying(b);case 6->tracker.use(NavroItemUseTrackerFixture.item(index),value,b);case 7->tracker.release();case 8->tracker.setUsedItem(NavroItemUseTrackerFixture.item(index));case 9->tracker.setDirtyUsing(value<0?null:ItemUseTracker.DirtyUsing.values()[Math.floorMod(value,3)]);case 10->session.using=b;case 11->tracker.applyPredictedUseStart();case 12->tracker.snapshotPredictionUseState();case 13->tracker.onMovementMetadataAcknowledged(b);case 14->tracker.onMovementMetadataSent(b);case 15->saved[slot]=tracker.snapshot();case 16->tracker.restore(b?saved[slot]:null);case 17->p.tick=tick;case 18->tracker.postTick();case 19->tracker.onHeldItemChanged(NavroItemUseTrackerFixture.item(index));case 20->{Set<Integer> copy=flags.cloneFlags();copy.add(value);result=new TreeSet<>(copy);}case 21->{Set<Integer> next=EntityMetadataUtil.mergeFlags(flags.cloneFlags(),update,b,c);if(b){next.remove(-1);if(next.contains(4))next.add(-1);}flags.set(nilPlayer?null:p,next);}}}catch(Exception e){error=e.getClass().getSimpleName();}
                    row.put("result",result);row.put("error",error);var flyingField=FlagTracker.class.getDeclaredField("flying");flyingField.setAccessible(true);var wasField=FlagTracker.class.getDeclaredField("wasFlying");wasField.setAccessible(true);row.put("flying",flyingField.get(flags));row.put("wasFlying",wasField.get(flags));row.put("state",NavroItemUseTrackerFixture.state(p,tracker,saved));out.write(gson.toJson(row));out.newLine();
                }
            }
        }
        System.out.println("Original flag/use handshake: "+(sessions*steps)+" continuous operations; metadata/replay distinction, nil inputs, physical flags and flying history.");
    }
}
