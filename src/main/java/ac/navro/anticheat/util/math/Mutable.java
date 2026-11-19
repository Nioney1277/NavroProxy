package ac.navro.anticheat.util.math;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;
public final class Mutable {
 private int x,y,z;
 public Mutable set(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
}
