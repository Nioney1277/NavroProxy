package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public final class CollisionQuery {
    private static final float MAX_MOVE_LENGTH_SQUARED = 256.0F;
    private static final float MAX_MOVE_LENGTH = 16.0F;
    private static final float EXTRA_DOWN = 0.200000003F;
    private static final float EXTRA_UP = 0.08F;
    private static final float SNEAK_SUPPORT_DISTANCE_SCALE = 1.01F;

    private CollisionQuery() {}

    public static Vec3 clamp(Vec3 movement) {
        float lengthSquared = movement.x * movement.x + movement.y * movement.y + movement.z * movement.z;
        if (!(lengthSquared > MAX_MOVE_LENGTH_SQUARED)) return movement.clone();
        float length = (float) Math.sqrt(lengthSquared);
        return new Vec3(movement.x / length * MAX_MOVE_LENGTH,
                movement.y / length * MAX_MOVE_LENGTH,
                movement.z / length * MAX_MOVE_LENGTH);
    }

    public static Box buildCollisionQuery(Box current, Vec3 delta, float maxAutoStep) {
        Box movementSweep = sweep(current, delta);
        Box supportSweep = buildHorizontalSupportSweep(current, delta, maxAutoStep);
        Box autoStepSweep = buildAutoStepSweep(current, delta, maxAutoStep);
        float minX = Math.min(movementSweep.minX, supportSweep.minX);
        float minY = Math.min(movementSweep.minY, supportSweep.minY);
        float minZ = Math.min(movementSweep.minZ, supportSweep.minZ);
        float maxX = Math.max(movementSweep.maxX, supportSweep.maxX);
        float maxY = Math.max(movementSweep.maxY, supportSweep.maxY);
        float maxZ = Math.max(movementSweep.maxZ, supportSweep.maxZ);
        minX = Math.min(minX, autoStepSweep.minX); minY = Math.min(minY, autoStepSweep.minY); minZ = Math.min(minZ, autoStepSweep.minZ);
        maxX = Math.max(maxX, autoStepSweep.maxX); maxY = Math.max(maxY, autoStepSweep.maxY); maxZ = Math.max(maxZ, autoStepSweep.maxZ);
        float paddedMinY = current.minY + (-EXTRA_DOWN - Math.abs(delta.y));
        float paddedMaxY = current.maxY + EXTRA_UP;
        minY = Math.min(minY, paddedMinY); maxY = Math.max(maxY, paddedMaxY);
        minX = Math.min(minX, current.minX); minZ = Math.min(minZ, current.minZ);
        maxX = Math.max(maxX, current.maxX); maxZ = Math.max(maxZ, current.maxZ);
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Box sweep(Box box, Vec3 delta) {
        float minX=box.minX,minY=box.minY,minZ=box.minZ,maxX=box.maxX,maxY=box.maxY,maxZ=box.maxZ;
        if(delta.x<0)minX+=delta.x; else if(delta.x>0)maxX+=delta.x;
        if(delta.y<0)minY+=delta.y; else if(delta.y>0)maxY+=delta.y;
        if(delta.z<0)minZ+=delta.z; else if(delta.z>0)maxZ+=delta.z;
        return new Box(minX,minY,minZ,maxX,maxY,maxZ);
    }

    private static Box buildHorizontalSupportSweep(Box current, Vec3 delta, float maxAutoStep) {
        float xMid=(current.minX+current.maxX)*.5F,xMin=current.minX+.025F,xMax=current.maxX-.025F;
        if(xMin>xMax)xMin=xMax=xMid;
        float zMid=(current.minZ+current.maxZ)*.5F,zMin=current.minZ+.025F,zMax=current.maxZ-.025F;
        if(zMin>zMax)zMin=zMax=zMid;
        float supportDistance=maxAutoStep*SNEAK_SUPPORT_DISTANCE_SCALE,minY=current.minY-supportDistance,maxY=current.maxY-supportDistance;
        if(delta.x<0) xMin+=delta.x; else if(delta.x>0)xMax+=delta.x;
        if(delta.z<0) zMin+=delta.z; else if(delta.z>0)zMax+=delta.z;
        return new Box(xMin,minY,zMin,xMax,maxY,zMax);
    }

    private static Box buildAutoStepSweep(Box current, Vec3 delta, float maxAutoStep) {
        float minX=current.minX,maxX=current.maxX,minZ=current.minZ,maxZ=current.maxZ;
        if(delta.x<0)minX+=delta.x; else if(delta.x>0)maxX+=delta.x;
        if(delta.z<0)minZ+=delta.z; else if(delta.z>0)maxZ+=delta.z;
        float movedMinY=delta.y<0?current.minY+delta.y:current.minY,movedMaxY=delta.y>0?current.maxY+delta.y:current.maxY;
        float stepMinY=maxAutoStep<0?current.minY+maxAutoStep:current.minY,stepMaxY=maxAutoStep>0?current.maxY+maxAutoStep:current.maxY;
        float stepThenMoveY=maxAutoStep+delta.y,combinedMinY=stepThenMoveY<0?current.minY+stepThenMoveY:current.minY,combinedMaxY=stepThenMoveY>0?current.maxY+stepThenMoveY:current.maxY;
        return new Box(minX,Math.min(Math.min(movedMinY,stepMinY),combinedMinY),minZ,maxX,Math.max(Math.max(movedMaxY,stepMaxY),combinedMaxY),maxZ);
    }
}
