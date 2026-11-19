package ac.navro.anticheat.util.math;

import java.util.Optional;


public class Box implements Cloneable {
    public static final Box EMPTY = new Box(0, 0, 0, 0, 0, 0);
    public static final float EPSILON = 1.0E-7F;
    public static final float COLLISION_EPSILON = 1.0E-6F;
    public final float minX, minY, minZ, maxX, maxY, maxZ;

    public Box(float x1, float y1, float z1, float x2, float y2, float z2) { this(x1,y1,z1,x2,y2,z2,true); }
    private Box(float x1,float y1,float z1,float x2,float y2,float z2,boolean normalize) {
        if (normalize) { minX=Math.min(x1,x2); minY=Math.min(y1,y2); minZ=Math.min(z1,z2); maxX=Math.max(x1,x2); maxY=Math.max(y1,y2); maxZ=Math.max(z1,z2); }
        else { minX=x1; minY=y1; minZ=z1; maxX=x2; maxY=y2; maxZ=z2; }
    }
    public Box(double x1,double y1,double z1,double x2,double y2,double z2) { this((float)x1,(float)y1,(float)z1,(float)x2,(float)y2,(float)z2); }



    public static Box fromNativeBounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        return new Box((float)minX,(float)minY,(float)minZ,(float)maxX,(float)maxY,(float)maxZ,false);
    }
    public static Box invalid() { return new Box(Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,false); }
    public boolean isValid() { return minX<maxX && minY<maxY && minZ<maxZ; }
    public Vec3 toVec3f(float width) { return new Vec3(minX+width/2F,minY,maxZ-width/2F); }
    public Vec3 feetCenter() { return new Vec3((minX+maxX)*.5F,minY,(minZ+maxZ)*.5F); }
    public static Box of(Vec3 c,float dx,float dy,float dz) { return new Box(c.x-dx/2F,c.y-dy/2F,c.z-dz/2F,c.x+dx/2F,c.y+dy/2F,c.z+dz/2F); }
    public boolean equals(Box b) { return minX==b.minX&&minY==b.minY&&minZ==b.minZ&&maxX==b.maxX&&maxY==b.maxY&&maxZ==b.maxZ; }
    public float chooseMin(Direction.Axis axis) { return axis==Direction.Axis.X?minX:axis==Direction.Axis.Y?minY:minZ; }
    public float chooseMax(Direction.Axis axis) { return axis==Direction.Axis.X?maxX:axis==Direction.Axis.Y?maxY:maxZ; }
    public boolean isOverlapped(Direction.Axis axis,Box o) {
        return switch(axis) { case X -> o.maxY-minY>0&&maxY-o.minY>0&&o.maxZ-minZ>0&&maxZ-o.minZ>0; case Y -> o.maxX-minX>0&&maxX-o.minX>0&&o.maxZ-minZ>0&&maxZ-o.minZ>0; default -> o.maxX-minX>0&&maxX-o.minX>0&&o.maxY-minY>0&&maxY-o.minY>0; };
    }
    public float calculateMaxDistance(Direction.Axis axis,Box other,float maxDist) {
        if (!isOverlapped(axis,other)||maxDist==0) return maxDist;
        if (maxDist>0) { float d=chooseMin(axis)-other.chooseMax(axis); if(d>=-COLLISION_EPSILON) maxDist=Math.min(maxDist,d); }
        else { float d=chooseMax(axis)-other.chooseMin(axis); if(d<=COLLISION_EPSILON) maxDist=Math.max(maxDist,d); }
        return maxDist;
    }
    public Optional<Vec3> clip(Vec3 from,Vec3 to) { return clip(minX,minY,minZ,maxX,maxY,maxZ,from,to); }
    public static Optional<Vec3> clip(float d,float e,float f,float g,float h,float i,Vec3 a,Vec3 b) {
        float[] ds={1F}; float j=b.x-a.x,k=b.y-a.y,l=b.z-a.z;
        Direction direction=getDirection(d,e,f,g,h,i,a,ds,null,j,k,l);
        return direction==null?Optional.empty():Optional.of(a.add(ds[0]*j,ds[0]*k,ds[0]*l));
    }
    private static Direction getDirection(float d,float e,float f,float g,float h,float i,Vec3 v,float[] ds,Direction dir,float j,float k,float l) {
        if(j>EPSILON) dir=clipPoint(ds,dir,j,k,l,d,e,h,f,i,Direction.WEST,v.x,v.y,v.z); else if(j<-EPSILON) dir=clipPoint(ds,dir,j,k,l,g,e,h,f,i,Direction.EAST,v.x,v.y,v.z);
        if(k>EPSILON) dir=clipPoint(ds,dir,k,l,j,e,f,i,d,g,Direction.DOWN,v.y,v.z,v.x); else if(k<-EPSILON) dir=clipPoint(ds,dir,k,l,j,h,f,i,d,g,Direction.UP,v.y,v.z,v.x);
        if(l>EPSILON) dir=clipPoint(ds,dir,l,j,k,f,d,g,e,h,Direction.NORTH,v.z,v.x,v.y); else if(l<-EPSILON) dir=clipPoint(ds,dir,l,j,k,i,d,g,e,h,Direction.SOUTH,v.z,v.x,v.y);
        return dir;
    }
    private static Direction clipPoint(float[] ds,Direction dir,float d,float e,float f,float g,float h,float i,float j,float k,Direction d2,float l,float m,float n) {
        float o=(g-l)/d,p=m+o*e,q=n+o*f;
        if(0<o&&o<ds[0]&&h-EPSILON<p&&p<i+EPSILON&&j-EPSILON<q&&q<k+EPSILON){ds[0]=o;return d2;} return dir;
    }
    public Box withMinX(float v){return new Box(v,minY,minZ,maxX,maxY,maxZ);} public Box withMinY(float v){return new Box(minX,v,minZ,maxX,maxY,maxZ);} public Box withMinZ(float v){return new Box(minX,minY,v,maxX,maxY,maxZ);}
    public Box withMaxX(float v){return new Box(minX,minY,minZ,v,maxY,maxZ);} public Box withMaxY(float v){return new Box(minX,minY,minZ,maxX,v,maxZ);} public Box withMaxZ(float v){return new Box(minX,minY,minZ,maxX,maxY,v);}
    public Box shrink(float x,float y,float z){return new Box(x<0?minX-x:x>0?minX:minX,y<0?minY-y:y>0?minY:minY,z<0?minZ-z:z>0?minZ:minZ,x>0?maxX-x:maxX,y>0?maxY-y:maxY,z>0?maxZ-z:maxZ);}
    public Box stretch(Vec3 s){return stretch(s.x,s.y,s.z);} public Box stretch(float x,float y,float z){return new Box(x<0?minX+x:minX,y<0?minY+y:minY,z<0?minZ+z:minZ,x>0?maxX+x:maxX,y>0?maxY+y:maxY,z>0?maxZ+z:maxZ);}
    public Box expand(float x,float y,float z){return new Box(minX-x,minY-y,minZ-z,maxX+x,maxY+y,maxZ+z);} public Box expand(float v){return expand(v,v,v);}
    public Box intersection(Box b){return new Box(Math.max(minX,b.minX),Math.max(minY,b.minY),Math.max(minZ,b.minZ),Math.min(maxX,b.maxX),Math.min(maxY,b.maxY),Math.min(maxZ,b.maxZ));}
    public Box union(Box b){return new Box(Math.min(minX,b.minX),Math.min(minY,b.minY),Math.min(minZ,b.minZ),Math.max(maxX,b.maxX),Math.max(maxY,b.maxY),Math.max(maxZ,b.maxZ));}
    public Box offset(float x,float y,float z){return new Box(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);} public Box offset(double x,double y,double z){return offset((float)x,(float)y,(float)z);} public Box offset(Vec3 v){return offset(v.x,v.y,v.z);}
    public boolean intersects(Box b){return intersects(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);} public boolean intersects(float a,float b,float c,float d,float e,float f){return minX<d&&maxX>a&&minY<e&&maxY>b&&minZ<f&&maxZ>c;}
    public boolean intersects(Vec3 a,Vec3 b){return intersects(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z),Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));}
    public boolean contains(Vec3 p){return contains(p.x,p.y,p.z);} public boolean contains(float x,float y,float z){return x>=minX&&x<maxX&&y>=minY&&y<maxY&&z>=minZ&&z<maxZ;}
    public Box contract(float x,float y,float z){return expand(-x,-y,-z);} public Box contract(float v){return expand(-v);} public Vec3 getMinPos(){return new Vec3(minX,minY,minZ);} public Vec3 getMaxPos(){return new Vec3(maxX,maxY,maxZ);}
    public float getAverageSideLength(){return (getLengthX()+getLengthY()+getLengthZ())/3;} public float getLengthX(){return maxX-minX;} public float getLengthY(){return maxY-minY;} public float getLengthZ(){return maxZ-minZ;}
    public boolean isNaN(){return Float.isNaN(minX)||Float.isNaN(minY)||Float.isNaN(minZ)||Float.isNaN(maxX)||Float.isNaN(maxY)||Float.isNaN(maxZ);}
    @Override public String toString(){return "AABB["+minX+", "+minY+", "+minZ+"] -> ["+maxX+", "+maxY+", "+maxZ+"]";}
    @Override public Box clone(){return new Box(minX,minY,minZ,maxX,maxY,maxZ,isValid());}
}
