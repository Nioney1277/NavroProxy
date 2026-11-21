import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.collision.sampling.NearbyBlockSampler;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.prediction.fluid.*;
import ac.navro.anticheat.util.math.*;
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


public final class NavroMovementMetadataFixture {
    static void set(Object o,Class<?> c,String n,Object v)throws Exception{NavroItemUseTrackerFixture.set(o,c,n,v);}
    static int bits(float v){return Float.floatToRawIntBits(v);}
    static Object dims(EntityDimensions d){return d==null?null:List.of(bits(d.width()),bits(d.height()),bits(d.eyeHeight()),d.fixed());}
    static Object vec(Vec3 v){return List.of(bits(v.x),bits(v.y),bits(v.z));}
    static Object box(Box b){return List.of(bits(b.minX),bits(b.minY),bits(b.minZ),bits(b.maxX),bits(b.maxY),bits(b.maxZ));}
    static Object snapshot(MovementAabbSnapshot s){return s==null?null:Map.of("dimensions",dims(s.dimensions()),"position",vec(s.position()),"box",box(s.box()),"revision",s.resizeRevision());}
    static Map<String,Object> pose(NavroPlayer p)throws Exception{
        var m=new LinkedHashMap<String,Object>();var s=p.fluidPrediction;
        m.put("dimensions",dims(p.dimensions));m.put("position",vec(p.position));m.put("box",box(p.boundingBox));m.put("revision",p.aabbResizeRevision());m.put("serverSprinting",p.isServerSprinting());m.put("clientSprinting",p.isClientSprinting());m.put("clientSwimming",p.isClientSwimming());
        for(String n:new String[]{"wasSwimming","wasCrawling","horizontalPoseApplied","inputHorizontalPoseApplied","inputNonCrawlingHorizontal","inputActive","readyPoseBeforeMovement","inputReadyPoseBeforeMovement"})m.put(n,FluidPredictionState.class.getField(n).get(s));
        for(String n:new String[]{"dimensionsBeforeHorizontalPose","inputDimensions","inputStandingDimensions","pendingPoseDimensions","readyPoseDimensions","inputReadyPoseDimensions"})m.put(n,dims((EntityDimensions)FluidPredictionState.class.getField(n).get(s)));
        m.put("inputAabb",snapshot(s.inputAabb));return m;
    }
    static Float f(int bits){return bits==0x7fffffff?null:Float.intBitsToFloat(bits);}
    static Set<Integer> flags(Random r){var s=new HashSet<Integer>();for(int b:new int[]{-9,-1,0,1,3,4,19,32,56,57,63,64,92,114,127,128,200})if(r.nextBoolean())s.add(b);return s;}
    public static void main(String[] args)throws Exception{
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var u=(Unsafe)field.get(null);NavroItemUseTrackerFixture.unsafe=u;set(null,Server.class,"instance",u.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();
        var pool=NavroItemUseTrackerFixture.pool;for(int id:new int[]{261,455,1,0,260,444})pool.add(Item.get(id,0,1));pool.add(new Item(444,0,1));for(int n=0;n<pool.size();n++)pool.get(n).setNamedTag(new CompoundTag().putInt("fixture_profile",n));
        Gson gson=new GsonBuilder().serializeNulls().create();Random random=new Random(0x4d455441504f5345L);int sessions=32,steps=256;int[] floats={0x7fffffff,bits(0.58F),bits(0.6F),bits(0.625F),bits(1.49F),bits(1.8F),bits(1.5F),bits(1.65F),bits(0.2F),bits(1F),bits(2F),bits(0F),bits(-0F),bits(-1F),0x7fc00000,0x7f800000,0xff800000,bits(1000000F),bits(1.001F),bits(0.421F)};
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            var profilePlayer=(NavroPlayer)u.allocateInstance(NavroPlayer.class);var session=(NavroItemUseTrackerFixture.Session)u.allocateInstance(NavroItemUseTrackerFixture.Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(profilePlayer,NavroPlayer.class,"session",session);List<Object> profiles=new ArrayList<>();for(Item item:pool)profiles.add(NavroItemUseTrackerFixture.profile(profilePlayer,item));out.write(gson.toJson(Map.of("magic","NMM1","sessions",sessions,"steps",steps,"profiles",profiles,"air",NavroItemUseTrackerFixture.profile(profilePlayer,Item.AIR_ITEM))));out.newLine();
            for(int sn=0;sn<sessions;sn++){
                var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);session=(NavroItemUseTrackerFixture.Session)u.allocateInstance(NavroItemUseTrackerFixture.Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;set(p,NavroPlayer.class,"session",session);set(p,PlayerData.class,"flagTracker",new FlagTracker());set(p,PlayerData.class,"fluidPrediction",new FluidPredictionState());set(p,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(p));set(p,NavroPlayer.class,"swimDebug",new SwimDebug(p));set(p,NavroPlayer.class,"movementSampler",new NearbyBlockSampler(p,NearbyBlockSampler.Kind.MOVEMENT));set(p,NavroPlayer.class,"trapdoorSampler",new NearbyBlockSampler(p,NearbyBlockSampler.Kind.TRAPDOOR));var tracker=new ItemUseTracker(p);set(p,NavroPlayer.class,"itemUseTracker",tracker);var inventory=new CompensatedInventory(p);set(p,NavroPlayer.class,"compensatedInventory",inventory);p.dimensions=EntityDimensions.changing(0.6F,1.8F).withEyeHeight(1.62F);p.boundingBox=Box.EMPTY;p.position=new Vec3(sn*123.456F,sn-16.25F,-sn*123.456F);p.tick=sn;
                for(int step=0;step<steps;step++){
                    int op=random.nextInt(25),w=floats[random.nextInt(floats.length)],h=floats[random.nextInt(floats.length)],z=floats[random.nextInt(floats.length)],item=random.nextInt(pool.size()+2)-2,value=new int[]{-1,1,3,4,19,32,56,57,114,128}[random.nextInt(10)];boolean b=random.nextBoolean(),c=random.nextBoolean();Set<Integer> update=step%13==0?null:flags(random);long tick=random.nextBoolean()?p.tick:random.nextLong();

                    switch(step){case 0->{op=0;w=h=z=0x7fffffff;update=Set.of(114);b=false;c=true;}case 1->{op=0;w=bits(0.58F);h=z=0x7fffffff;update=null;}case 2->op=4;case 3->{op=8;value=114;b=false;}case 4->op=6;case 5->op=5;case 6->{op=0;w=bits(0.58F);h=bits(1.49F);z=bits(1.001F);update=Set.of(3,4,32,57);b=true;c=false;}default->{}}
                    var row=new LinkedHashMap<String,Object>();row.put("session",sn);row.put("step",step);row.put("op",op);row.put("width",w);row.put("height",h);row.put("scale",z);row.put("item",item);row.put("value",value);row.put("b",b);row.put("c",c);row.put("flags",update);row.put("tick",tick);Object result=null;String error="";
                    try{switch(op){case 0,1,2,3->p.applyClientVisibleMovementMetadata(f(w),f(h),f(z),update,b,c);case 4->FluidMovement.beginInput(p);case 5->FluidMovement.finishInput(p);case 6->FluidMovement.updatePose(p);case 7->FluidMovement.applySwimmingAction(p,b);case 8->p.getFlagTracker().set(value,b);case 9->{Item chest=NavroItemUseTrackerFixture.item(item);if(chest!=null)chest=chest.clone();if(chest!=null)chest.setCount(b?1:0);inventory.armorContainer.set(1,chest);}case 10->tracker.use(NavroItemUseTrackerFixture.item(item),value,b);case 11->tracker.setUsedItem(NavroItemUseTrackerFixture.item(item));case 12->session.using=b;case 13->tracker.onMovementMetadataSent(b);case 14->tracker.onMovementMetadataAcknowledged(b);case 15->{p.tick=tick;tracker.snapshotPredictionUseState();}case 16->FluidMovement.queueMetadataPose(p,EntityDimensions.fixed(0.58F,b?0.625F:1.8F).withEyeHeight(b?0.42F:1.62F));case 17->FluidMovement.acknowledgeDimensions(p);case 18->FluidMovement.acknowledgeWidth(p);case 19->{Vec3 target=p.position.add(0.1F,0.0625F,-0.1F);Box solved=p.boundingBox.offset(new Vec3(0.1F,0.0625F,-0.1F));p.setResolvedPosition(target,solved,false);}case 20->p.boundingBox=Box.EMPTY;case 21->p.applyDimensions(FluidMovement.horizontalPose(p.fluidPrediction,p.dimensions,b,c));case 22->result=p.hasElytraEquipped();case 23->{p.fluidPrediction.captureInputState(b,c);p.getFlagTracker().restoreFlags(update);}case 24->p.applyDimensions(EntityDimensions.fixed(0.58F,b?1.49F:1.8F).withEyeHeight(b?1.26F:1.62F));}}catch(Exception e){error=e.getClass().getSimpleName();}
                    row.put("result",result);row.put("error",error);row.put("pose",pose(p));row.put("state",NavroItemUseTrackerFixture.state(p,tracker,new ItemUseTracker.Snapshot[4]));out.write(gson.toJson(row));out.newLine();
                }
            }
        }
        System.out.println("Original movement metadata/pose/input/use handshake: "+(sessions*steps)+" continuous operations.");
    }
}
