package ac.navro.proxy.prediction.engine.data;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.prediction.engine.data.VectorType;
public final class Vector implements ac.navro.proxy.prediction.PredictionSample.Possibility {
 public static final Vector NONE=new Vector(VectorType.NORMAL,Vec3.ZERO);
 private final VectorType type;private final Vec3 velocity;private boolean twice;
 public Vector(VectorType type,Vec3 velocity){this.type=type;this.velocity=velocity;}
 public VectorType getType(){return type;}public Vec3 getVelocity(){return velocity;}
 public boolean isTwice(){return twice;}public void setTwice(boolean value){twice=value;}
}
