package ac.navro.proxy.compensated.world;

import ac.navro.proxy.prediction.live.PredictionContext;


import ac.navro.proxy.data.block.NavroBlockState;
import ac.navro.proxy.prediction.StickyBlockPhysics;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.collision.*;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import org.cloudburstmc.math.vector.Vector3i;
import java.util.*;
public final class CompensatedWorldImpl implements ac.navro.proxy.prediction.PredictionSample.World {
 private final CompensatedWorld world;private PredictionContext context;
 public void bindContext(PredictionContext context){this.context=java.util.Objects.requireNonNull(context);}
 public CompensatedWorldImpl(CompensatedWorld world){this.world=world;}
 public boolean isChunkLoaded(int x,int z){return world.isChunkLoaded(x,z);}
 public boolean isChunkLoadedAt(float x,float z){
  int bx=(int)Math.floor(x),bz=(int)Math.floor(z);if(!isChunkLoaded(bx,bz))return false;
  if(context==null)return true;
  Box box=context.boundingBox;
  return world.areSectionsKnown(bx,bz,(int)Math.floor(Math.nextDown(box.minY)),(int)Math.floor(Math.nextDown(box.maxY)));
 }
 public int getMinY(){return world.getMinY();}
 public Map<Long,ac.navro.proxy.compensated.cache.entity.EntityCache> getEntities(){return context.session.entities();}
 public NavroBlockState getBlockState(Vector3i pos,int layer){return getBlockState(pos.getX(),pos.getY(),pos.getZ(),layer);}
 public NavroBlockState getBlockState(int x,int y,int z,int layer){
  NetworkBlockState state=world.networkBlockState(world.getRawBlockAt(x,y,z,layer));
  return new NavroBlockState(java.util.Objects.requireNonNull(state,"Prediction needs an initialized block palette"));
 }
 public java.util.List<CollisionShapeEntry> collectBlockCollisionEntries(Box query){return context.session.collisionWorld().blockEntries(query);}

 public boolean noCollision(Box query){
  var collision=context.session.collisionWorld();
  return collision.collectColliders(collision.entityCollisions(query,context.boundingBox),query).isEmpty();
 }
 private List<Box> blockShapes(Vector3i pos,Box actor){
  return world.collisionCellFragments(pos.getX(),pos.getY(),pos.getZ(),null,context.collisionPhysics).stream().map(CollisionShapeEntry::shape).toList();
 }
 public static Vector3i blockPosition(Vec3 v){return Vector3i.from((int)Math.floor(v.x),(int)Math.floor(v.y),(int)Math.floor(v.z));}
    public org.cloudburstmc.math.vector.Vector3i getStandingBlockPosition(Box actorBox) {
        final float queryY = actorBox.minY - 0.2F;
        final List<Box> candidates = new ArrayList<>();
        final int minX = (int) Math.floor(actorBox.minX);
        final int maxX = (int) Math.floor(actorBox.maxX + 1.0F);
        final int minZ = (int) Math.floor(actorBox.minZ);
        final int maxZ = (int) Math.floor(actorBox.maxZ + 1.0F);
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                final int x = chunkX << 4, z = chunkZ << 4;
                if (!isChunkLoaded(x, z)) candidates.add(new Box(x, -100000, z, x + 16, 100000, z + 16));
            }
        }
        if (getMinY() > queryY) {
            candidates.add(new Box(actorBox.minX, -Float.MAX_VALUE, actorBox.minZ,
                    actorBox.maxX, getMinY() - 40.0F, actorBox.maxZ));
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!isChunkLoaded(x, z)) continue;
                for (int y = (int) Math.ceil(queryY + 1.0F) - 1;
                        y >= (int) Math.floor(queryY - 1.0F); y--) {
                    final org.cloudburstmc.math.vector.Vector3i pos = org.cloudburstmc.math.vector.Vector3i.from(x, y, z);
                    final List<Box> fragments = blockShapes(pos, actorBox);
                    if (fragments.isEmpty()) continue;
                    Box shape = fragments.get(0);
                    for (int i = 1; i < fragments.size(); i++) shape = shape.union(fragments.get(i));
                    candidates.add(shape);
                }
            }
        }
        final Box selected = StickyBlockPhysics.selectStandingShape(actorBox, candidates);
        return selected == null ? blockPosition(context.position)
                : org.cloudburstmc.math.vector.Vector3i.from((int) Math.floor(selected.minX), (int) Math.floor(selected.minY),
                        (int) Math.floor(selected.minZ));
    }
}
