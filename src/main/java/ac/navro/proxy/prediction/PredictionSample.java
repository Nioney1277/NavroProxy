package ac.navro.proxy.prediction;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.proxy.collision.ProtocolEntityTolerance;
import ac.navro.proxy.prediction.engine.data.VectorType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import java.util.*;


public class PredictionSample {
    public interface World {
        boolean isChunkLoadedAt(float x,float z);
        List<CollisionShapeEntry> collectBlockCollisionEntries(Box query);
    }
    public interface Teleports {boolean isTeleporting();void rewind(long tick);}
    public interface UsingItem {boolean hasPredictionItemSlowdown();}
    public interface Possibility {VectorType getType(); default Vec3 getVelocity(){throw new IllegalStateException("Missing velocity candidate");}}
    @FunctionalInterface public interface PhaseObserver {void hit(PredictionSample sample,String reason,float offset,CollisionShapeEntry entry);}
    public PhaseObserver phaseObserver=(sample,reason,offset,entry)->{};
    public void observePhase(String reason,float offset,CollisionShapeEntry entry){try{phaseObserver.hit(this,reason,offset,entry);}catch(Throwable ignored){}}
    public final World compensatedWorld;
    public final Teleports teleports;
    public final UsingItem itemUse;
    public float maxOffset,alertThreshold;
    public Set<String> disabledChecks=Set.of();
    public long tick;
    public int tickSinceBlockResync,sinceLoadingScreen;
    public boolean insideUnloadedChunk,onGround,verticalCollision;
    public Object vehicleData;
    public Vec3 position=Vec3.ZERO,unvalidatedPosition=Vec3.ZERO,prevUnvalidatedPosition=Vec3.ZERO;
    public Vec3 beforeCollision=Vec3.ZERO,afterCollision=Vec3.ZERO,unvalidatedTickEnd=Vec3.ZERO,velocity=Vec3.ZERO;
    public Box predictionStartBoundingBox=Box.EMPTY,boundingBox=Box.EMPTY;
    public EntityDimensions dimensions=EntityDimensions.changing(0.6F,1.8F);
    public List<CollisionShapeEntry> movementCollisionEntries=List.of();
    public Possibility bestPossibility;
    public ProtocolEntityTolerance.Tolerance entityTolerance;
    public Set<PlayerAuthInputData> inputData=Set.of();
    public PredictionSample(World world,Teleports teleports,UsingItem itemUse){this.compensatedWorld=world;this.teleports=teleports;this.itemUse=itemUse;}
    public float getMaxOffset(){return maxOffset;}
    public Teleports getTeleportUtil(){return teleports;}
    public UsingItem getItemUseTracker(){return itemUse;}
    public Set<PlayerAuthInputData> getInputData(){return inputData;}
    public Box boxAtProjectedPosition(Vec3 target){return boundingBox.isValid()?boundingBox.offset(target.subtract(position)):dimensions.getBoxAt(target);}
}
