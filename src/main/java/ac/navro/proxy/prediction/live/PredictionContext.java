package ac.navro.proxy.prediction.live;
import ac.navro.proxy.data.input.PredictionData;
import ac.navro.proxy.prediction.fluid.FluidPredictionState;


import ac.navro.proxy.compensated.world.CompensatedWorldImpl;
import ac.navro.proxy.prediction.ClimbableBlockPhysics;
import ac.navro.proxy.prediction.GroundTravelPhysics;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.prediction.SprintJumpPhysics;
import ac.navro.proxy.prediction.StickyBlockPhysics;
import ac.navro.proxy.prediction.engine.data.Vector;
import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.anticheat.util.math.*;
import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.vanilla.StatusEffect;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.compensated.CompensatedInventory;
import ac.navro.proxy.collision.CollisionPhysicsState;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.math.vector.Vector3i;
import java.util.*;

public final class PredictionContext extends PredictionSample {
 public final SessionState session;public final WirePlayerState wire;
 public final CompensatedWorldImpl compensatedWorld;
 public final CompensatedInventory compensatedInventory;
 public final CollisionPhysicsState collisionPhysics;
 public final Flags flags=new Flags();
 public final Diagnostics movementSampler=new Diagnostics(),trapdoorSampler=movementSampler,swimDebug=movementSampler,itemUseDebug=movementSampler,lavaSampler=movementSampler,ceilingSampler=movementSampler,collisionSampler=movementSampler;
 public final FluidPredictionState fluidPrediction=new FluidPredictionState();
 public final UseState usingItem;
 public Vec3 input=Vec3.ZERO.clone(),stuckSpeedMultiplier=Vec3.ZERO.clone(),lastTickFinalVelocity=Vec3.ZERO.clone();
 public Vector uncertainVelocity,certainVelocity;
 public boolean horizontalCollision;
 public float yaw,pitch,prevYaw,prevPitch,sneakingAttributeModifier,fallDistance;
 public boolean doingInventoryAction,scaffoldingSneakDown,scaffoldingSneaking,scaffoldDescend,dirtySpinStop,dirtyRiptide,thisTickSpinAttack,thisTickOnGroundSpinAttack,riptideWasInWater,hasDepthStrider;
 public int ticksSinceCanSlowdown,scaffoldingSneakHoldTicks,climbJumpDelay,autoSpinAttackTicks,thisTickRiptideLevel,glideBoostTicks;
 public Object inBlockState;public Vector3i cachedOnPos;
 public InputMode inputMode;
 public StickyBlockPhysics.GravityCorrection bounceGravityCorrection;
 public RiptidePhysics.Snapshot riptideTickStartState;
 public org.cloudburstmc.protocol.bedrock.data.inventory.ItemData riptideItem=org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR;
 public PredictionData predictionResult=new PredictionData(Vec3.ZERO.clone(),Vec3.ZERO.clone(),Vec3.ZERO.clone());
 public PredictionContext(SessionState s){this(s,new CompensatedWorldImpl(s.world()),new UseState());}
 private PredictionContext(SessionState s,CompensatedWorldImpl w,UseState use){
  super(w,new PredictionTeleports(s),use);session=s;wire=s.player();compensatedWorld=w;w.bindContext(this);usingItem=use;compensatedInventory=s.inventory();collisionPhysics=wire.collisionPhysics;maxOffset=1.0E-4F;alertThreshold=0;
 }
 public void begin(){
  var previous=wire.previousInputRotation();var r=wire.rawInputRotation();
  prevYaw=previous==null?yaw:previous.y();prevPitch=previous==null?pitch:previous.x();
  yaw=r==null?0:r.y();pitch=r==null?0:r.x();
  position=wire.position().clone();boundingBox=wire.boundingBox().clone();dimensions=wire.dimensions();input=wire.input().clone();velocity=wire.velocity().clone();
  unvalidatedPosition=wire.unvalidatedPosition().clone();prevUnvalidatedPosition=wire.previousUnvalidatedPosition();unvalidatedTickEnd=wire.unvalidatedTickEnd();
  tick=wire.tick();tickSinceBlockResync=wire.tickSinceBlockResync;sinceLoadingScreen=wire.sinceLoadingScreen;inputData=wire.inputData();
  vehicleData=wire.vehicleData;doingInventoryAction=wire.doingInventoryAction;inputMode=wire.inputMode();sneakingAttributeModifier=wire.sneakingAttributeModifier;glideBoostTicks=wire.glideBoostTicks();
  onGround=wire.onGround();verticalCollision=wire.verticalCollision();horizontalCollision=wire.horizontalCollision();
  usingItem.snapshot(session.authInputPackets().itemUse().getItemUseTracker().getPredictionItemSlowdownFactor());
  flags.capture();fluidPrediction.load(session.fluidState());
 }
 public void syncWire(){wire.setResolvedPosition(position,boundingBox,false,vehicleData!=null);wire.setVelocity(velocity);wire.setAfterCollision(afterCollision);wire.setCollisionResult(horizontalCollision,verticalCollision,onGround);}
 public void setResolvedMovement(Box box,boolean previous){boundingBox=box.clone();position=box.feetCenter();syncWire();}
 public long aabbResizeRevision(){return wire.aabbResizeRevision();}
 public boolean noClip(){return wire.abilities().contains(Ability.NO_CLIP);}
 public boolean hasInput(PlayerAuthInputData action){return inputData.contains(action);}
 public Set<PlayerAuthInputData> getInputData(){return inputData;}
 public Flags getFlagTracker(){return flags;}
 public boolean isPredictionSprinting(){return wire.predictionSprinting();}
 public boolean isPredictionSwimming(){return wire.predictionSwimming();}
 public void setClientSwimming(boolean value){wire.setClientSwimming(value);}
 public void setPredictionSwimming(boolean value){wire.setPredictionSwimming(value);}
 public boolean hasEffect(int id){return wire.hasEffect(id);}public StatusEffect getEffect(int id){return wire.effect(id);}
 public boolean isInLava(){return tick!=1&&fluidPrediction.liquids.lava().touching();}
 public float getEffectiveGravity(){return getEffectiveGravity(velocity);}
 public float getEffectiveGravity(Vec3 v){return v.y<0&&hasEffect(NativeIds.Effect.SLOW_FALLING)?Math.min(0.08F,0.01F):0.08F;}
 private float attribute(String name,float initial){var a=wire.attributes().get(name);return a==null?initial:a.getValue();}
 public float getSpeed(){return attribute("minecraft:movement",0.1F);}
 public float getUnderwaterSpeed(){return attribute("minecraft:underwater_movement",0.02F);}
 public float getLavaSpeed(){return attribute("minecraft:lava_movement",0.02F);}
 public float getFrictionInfluencedSpeed(float f){return onGround?GroundTravelPhysics.movementSpeed(getSpeed(),f,compensatedWorld.getBlockState(GroundTravelPhysics.frictionPosition(position),0).nativeId()==NativeIds.BlockID.SOUL_SAND,CompensatedInventory.getEnchantments(nativeItem(compensatedInventory.armorContainer.get(3).getData())).containsKey(NativeIds.Enchantment.ID_SOUL_SPEED)):isPredictionSprinting()?0.026F:0.02F;}
 public org.cloudburstmc.protocol.bedrock.data.inventory.ItemData nativeItem(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData item){return session.authInputPackets().itemUse().nativeItem(item);}
 public boolean onClimbable(){return ClimbableBlockPhysics.isClimbing(this);}
 public float getJumpPower(){return 0.42F*getBlockJumpFactor()+getJumpBoostPower();}
 public float getJumpBoostPower(){return hasEffect(NativeIds.Effect.JUMP_BOOST)?0.1F*(getEffect(NativeIds.Effect.JUMP_BOOST).amplifier()+1.0F):0;}
 public boolean canStandOnSnow(){var b=compensatedInventory.armorContainer.get(3).getData();return b!=null&&session.authInputPackets().itemUse().nativeItemId(b)==NativeIds.ItemID.LEATHER_BOOTS;}
 public UseState getItemUseTracker(){return usingItem;}
 public void prepareClosestSpace(){syncWire();ac.navro.proxy.collision.MoveTowardsClosestSpace.prepare(wire,session.collisionWorld(),vehicleData!=null);velocity=wire.velocity().clone();}
 public void finishShulkerPush(){syncWire();ac.navro.proxy.collision.ShulkerPushPhysics.finish(wire,session.collisionWorld(),vehicleData!=null);position=wire.position().clone();boundingBox=wire.boundingBox().clone();afterCollision=wire.afterCollision();onGround=wire.onGround();verticalCollision=wire.verticalCollision();horizontalCollision=wire.horizontalCollision();}
 public float inputPositionDistance(Vec3 value){return wire.inputPositionDistance(value);}public Vec3 inputPositionResidual(Vec3 value){return wire.inputPositionResidual(value);}
 public boolean doesNotCollide(float x,float y,float z){Box b=boundingBox.offset(x,y,z);return compensatedWorld.noCollision(b)&&!containsFluid(b);}
 public boolean containsFluid(Box b){
  for(int x=(int)Math.floor(b.minX);x<(int)Math.ceil(b.maxX);x++)for(int y=(int)Math.floor(b.minY);y<(int)Math.ceil(b.maxY);y++)for(int z=(int)Math.floor(b.minZ);z<(int)Math.ceil(b.maxZ);z++)if(session.world().fluidAt(x,y,z,session.world().dimension()).isFluid())return true;
  return false;
 }
 public void stopRiptide(){dirtyRiptide=dirtySpinStop=false;autoSpinAttackTicks=0;riptideItem=org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR;thisTickRiptideLevel=0;thisTickSpinAttack=thisTickOnGroundSpinAttack=riptideWasInWater=false;flags.set("SPIN_ATTACK",false);wire.refreshPoseBoundingBox();}
 @Override public PredictionTeleports getTeleportUtil(){return (PredictionTeleports)teleports;}

 public final class Flags {
  private final Map<String,Boolean> derived=new HashMap<>();
  public void capture(){derived.clear();}
  private EntityFlag flag(String name){return EntityFlag.valueOf("WALLCLIMBING".equals(name)?"WALL_CLIMBING":"SPIN_ATTACK".equals(name)?"DAMAGE_NEARBY_MOBS":name);}
  public boolean has(String name){return derived.containsKey(name)?derived.get(name):wire.predictionFlag(flag(name));}
  public boolean has(int bit){if(bit==71)return has("DESCEND_THROUGH_BLOCK");throw new IllegalArgumentException("Unbound prediction flag "+bit);}
  public void set(String name,boolean v){derived.put(name,v);wire.setPredictionFlag(flag(name),v);}
  public void set(int bit,boolean v){if(bit!=71)throw new IllegalArgumentException("Unbound prediction flag "+bit);set("DESCEND_THROUGH_BLOCK",v);}
  public boolean isFlying(){return wire.flying();}
 }
 public static final class UseState implements UsingItem {
  private float factor=1F;
  public void snapshot(float value){factor=value;}
  public boolean hasPredictionItemSlowdown(){return factor!=1F;}
  public float getPredictionItemSlowdownFactor(){return factor;}
 }
 public static final class Diagnostics {
  public void stage(String n,Object...a){}public void decision(String n,Object...a){}public void trial(Object...a){}public void edgeBackoff(Object...a){}
 }
 public static final class PredictionTeleports implements Teleports {
  private final SessionState session;PredictionTeleports(SessionState s){session=s;}
  public boolean isTeleporting(){return session.teleports().isTeleporting();}
  public void rewind(long tick){session.player().requestRewind(tick);}
  public List<Vec3> getTeleportStartVelocities(long tick){return session.teleportStartVelocities(tick);}
 }
    public Vec3 jump(Vec3 vec3) {
        if (this.getFlagTracker().isFlying()) return vec3;

        if (ClimbableBlockPhysics.isJumping(this)) {
            final Vec3 swimmingJump = FluidMovement.swimmingJump(vec3, this.fluidPrediction.wasInWater,
                    this.isPredictionSwimming(), this.fluidPrediction.headInWater,
                    this.fluidPrediction.swimAmount);
            if (swimmingJump != null) {

                return swimmingJump;
            }
        }
        final Vec3 blockJump = ClimbableBlockPhysics.applyBlockJump(this, vec3, this.onClimbable());
        if (blockJump != null) return blockJump;




        boolean autoJumping = this.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.AUTO_JUMPING_IN_WATER);
        boolean jumping = this.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMPING);

        boolean canJumpInWater = this.fluidPrediction.wasInWater, canJumpInLava = this.isInLava();
        if ((jumping || autoJumping) && (canJumpInWater || canJumpInLava)) {

            final Vec3 liquidJump = FluidMovement.liquidJump(vec3, canJumpInWater, canJumpInLava,
                    true, false);

            vec3 = liquidJump;

        } else if (this.climbJumpDelay == 0 && this.onGround
                && this.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_JUMPING)) {
            vec3 = this.jumpFromGround(vec3);
        }

        return vec3;
    }

    private Vec3 jumpFromGround(Vec3 vec3) {
        float f = this.getJumpPower();
        if (f <= 1.0E-5f) {
            return vec3;
        }
        vec3 = new Vec3(vec3.x, Math.max(f, vec3.y), vec3.z);
        if (this.isPredictionSprinting()) {
            vec3 = SprintJumpPhysics.apply(vec3, this.yaw);
        }

        return vec3;
    }

    public float getBlockJumpFactor() {
        float f = this.compensatedWorld.getBlockState(CompensatedWorldImpl.blockPosition(this.position), 0).getJumpFactor();
        float g = this.compensatedWorld.getBlockState(this.getBlockPosBelowThatAffectsMyMovement(), 0).getJumpFactor();
        return f == 1.0 ? g : f;
    }

    public org.cloudburstmc.math.vector.Vector3i getBlockPosBelowThatAffectsMyMovement() {
        return CompensatedWorldImpl.blockPosition(position.down(0.1F));
    }


}
