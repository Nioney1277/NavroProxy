package ac.navro.proxy.util.block;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.proxy.data.inventory.NativePredictionItems;
import ac.navro.proxy.data.vanilla.GameType;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import java.util.Locale;


public final class BlockReachUtil {
    private static final String BLOCK_INTERACTION_RANGE="minecraft:block_interaction_range";
    private static final String LEGACY_BLOCK_INTERACTION_RANGE="block_interaction_range";
    private BlockReachUtil() { }
    public static boolean inVehicle(WirePlayerState player) { return player.vehicleData!=null; }
    public static double maxReach(WirePlayerState player) {
        double attribute=attributeValue(player,BLOCK_INTERACTION_RANGE);
        if(Double.isFinite(attribute)&&attribute>0.0D)return attribute;
        return player.gameType()==GameType.CREATIVE?13.0D:7.0D;
    }
    public static double distanceSquared(WirePlayerState player,Vector3i position) {
        var feet=player.position();
        if(feet==null||position==null)return Double.POSITIVE_INFINITY;
        double minimum=Double.POSITIVE_INFINITY;
        for(float eyeHeight:possibleEyeHeights(player)) {
            double eyeX=feet.x,eyeY=feet.y+eyeHeight,eyeZ=feet.z;
            double closestX=clamp(eyeX,position.getX(),position.getX()+1.0D);
            double closestY=clamp(eyeY,position.getY(),position.getY()+1.0D);
            double closestZ=clamp(eyeZ,position.getZ(),position.getZ()+1.0D);
            double dx=eyeX-closestX,dy=eyeY-closestY,dz=eyeZ-closestZ;
            minimum=Math.min(minimum,dx*dx+dy*dy+dz*dz);
        }
        return minimum;
    }
    public static boolean isFar(WirePlayerState player,Vector3i position) {
        return distanceSquared(player,position)>maxReach(player)*maxReach(player);
    }
    public static String describe(WirePlayerState player,Vector3i position) {
        return String.format(Locale.ROOT,"distance=%.3f,max=%.3f,pos=%d,%d,%d",
                Math.sqrt(distanceSquared(player,position)),maxReach(player),position.getX(),position.getY(),position.getZ());
    }
    public static String distanceDetail(String detail) {
        int start=detail.indexOf("distance=");if(start<0)return "";
        start+="distance=".length();int end=detail.indexOf(',',start);
        return String.format(Locale.ROOT,"distance=%.2f",Double.parseDouble(detail.substring(start,end<0?detail.length():end)));
    }
    public static boolean isPlaceable(int protocol,ItemData item) {
        if(item==null||item.getCount()<=0||NativePredictionItems.resolve(protocol,item).positiveNull())return false;
        var properties=NativeItemDecoder.placement(protocol,item);
        return properties.canBePlaced()&&properties.blockId()!=0&&properties.blockId()!=420;
    }
    private static double attributeValue(WirePlayerState player,String name) {
        var instance=player.attributes().get(name);
        if(instance==null&&BLOCK_INTERACTION_RANGE.equals(name))instance=player.attributes().get(LEGACY_BLOCK_INTERACTION_RANGE);
        return instance==null?Double.NaN:instance.getValue();
    }
    private static float[] possibleEyeHeights(WirePlayerState player) {
        float current=player.dimensions().eyeHeight();
        if(Math.abs(current-1.62F)<1.0E-4F)return new float[]{current,1.26F,.42F};
        if(Math.abs(current-1.26F)<1.0E-4F)return new float[]{current,1.62F,.42F};
        if(Math.abs(current-.42F)<1.0E-4F)return new float[]{current,1.62F,1.26F};
        return new float[]{current};
    }
    private static double clamp(double value,double min,double max) { return Math.max(min,Math.min(max,value)); }
}
