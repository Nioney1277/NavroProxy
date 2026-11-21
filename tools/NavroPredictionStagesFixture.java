import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.data.ItemUseTracker;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.prediction.*;
import ac.navro.anticheat.prediction.fluid.FluidPredictionState;
import ac.navro.anticheat.prediction.ticker.impl.PlayerTicker;
import ac.navro.anticheat.prediction.engine.impl.GroundAndAirPredictionEngine;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.item.*;
import cn.nukkit.potion.Effect;
import cn.nukkit.network.protocol.types.AuthInputAction;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroPredictionStagesFixture {
 static void set(Object o,Class<?> c,String n,Object v)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static void vec(DataOutputStream out,Vec3 v)throws Exception{out.writeFloat(v.x);out.writeFloat(v.y);out.writeFloat(v.z);}
 static class World extends CompensatedWorldImpl{
  int id;
  World(NavroPlayer p){super(p);}
  @Override public boolean isChunkLoaded(int x,int z){return true;}
  @Override public NavroBlockState getBlockState(int x,int y,int z,int layer){return getBlockState(new BlockVector3(x,y,z),layer);}
  @Override public NavroBlockState getBlockState(BlockVector3 pos,int layer){return new NavroBlockState(Block.get(id,0),pos,layer);}
 }
 public static void main(String[] args)throws Exception{
  var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);
  set(null,cn.nukkit.Server.class,"instance",u.allocateInstance(cn.nukkit.Server.class));Block.init();cn.nukkit.item.RuntimeItems.init();Item.init();Effect.init();cn.nukkit.entity.Attribute.init();cn.nukkit.item.enchantment.Enchantment.init();
  Random random=new Random(0x505245444943544cL);int count=4096;
  try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
   out.writeInt(0x4e505331);out.writeInt(count);
   for(int i=0;i<count;i++){
    var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);
    set(p,PlayerData.class,"flagTracker",new FlagTracker());set(p,PlayerData.class,"fluidPrediction",new FluidPredictionState());
    set(p,PlayerData.class,"activeEffects",new HashMap<>());set(p,PlayerData.class,"attributes",new HashMap<>());
    set(p,NavroPlayer.class,"compensatedInventory",new CompensatedInventory(p));var tracker=new ItemUseTracker(p);set(p,NavroPlayer.class,"itemUseTracker",tracker);
    var w=new World(p);set(p,NavroPlayer.class,"compensatedWorld",w);
    p.initializeMovementSpeedAttribute();p.position=new Vec3(8,64,8);p.boundingBox=new ac.navro.anticheat.util.math.Box(7.7,64,7.7,8.3,65.8,8.3);
    boolean sprint=random.nextBoolean(),swim=random.nextBoolean(),sneak=random.nextBoolean(),crawl=random.nextBoolean(),water=random.nextBoolean();
    p.setServerSprinting(sprint);p.setClientSwimming(swim);p.snapshotPredictionMovementState();p.getFlagTracker().set(1,sneak);p.getFlagTracker().set(114,crawl);
    p.fluidPrediction.inputActive=true;p.fluidPrediction.wasCrawling=crawl;p.fluidPrediction.waterTravel=water;
    p.ticksSinceCanSlowdown=random.nextInt(6);p.doingInventoryAction=random.nextBoolean();p.yaw=random.nextFloat()*720-360;
    p.setInputData(new HashSet<>());
    if(random.nextBoolean())p.getInputData().add(AuthInputAction.STOP_SNEAKING);
    float factor=i%3==0?.122499995F:1F;set(tracker,ItemUseTracker.class,"predictionItemSlowdownFactor",factor);
    Vec3 raw=new Vec3(random.nextFloat()*2-1,0,random.nextFloat()*2-1),velocity=new Vec3(random.nextFloat()-.5F,random.nextFloat()-.5F,random.nextFloat()-.5F);
    out.writeBoolean(sprint);out.writeBoolean(swim);out.writeBoolean(sneak);out.writeBoolean(crawl);out.writeBoolean(water);out.writeInt(p.ticksSinceCanSlowdown);out.writeBoolean(p.doingInventoryAction);out.writeBoolean(p.getInputData().contains(AuthInputAction.STOP_SNEAKING));out.writeFloat(factor);out.writeFloat(p.yaw);vec(out,raw);vec(out,velocity);
    vec(out,PlayerTicker.scaleMovementInput(p,raw,false));vec(out,PlayerTicker.scaleMovementInput(p,raw,true));out.writeInt(p.ticksSinceCanSlowdown);
    p.input=raw;p.onGround=random.nextBoolean();w.id=new int[]{BlockID.STONE,BlockID.ICE,BlockID.PACKED_ICE,BlockID.BLUE_ICE,BlockID.SLIME_BLOCK,BlockID.HONEY_BLOCK,BlockID.SOUL_SAND}[i%7];p.velocity=velocity.clone();
    out.writeBoolean(p.onGround);out.writeInt(w.id);
    var engine=new GroundAndAirPredictionEngine(p);vec(out,engine.travel(velocity));engine.finalizeMovement();vec(out,p.velocity);
    var correction=new StickyBlockPhysics.GravityCorrection(random.nextFloat()-.8F,random.nextFloat()-.4F);float gravity=-(random.nextInt(3)+1)*.02F;
    out.writeFloat(correction.requestedY());out.writeFloat(correction.resolvedY());out.writeFloat(gravity);out.writeFloat(StickyBlockPhysics.resolveGravityDelta(correction,gravity));
    float pitch=random.nextFloat()*180-90;int level=1+random.nextInt(3);boolean inWater=random.nextBoolean(),head=random.nextBoolean();
    out.writeFloat(pitch);out.writeInt(level);out.writeBoolean(inWater);out.writeBoolean(head);vec(out,RiptidePhysics.impulse(velocity,pitch,p.yaw,level,p.onGround,inWater,head));
   }
  }
  System.out.println("Original prediction stages: "+count+" samples, trials/commits, exact ground travel and gravity, restitution gravity and riptide.");
 }
}
