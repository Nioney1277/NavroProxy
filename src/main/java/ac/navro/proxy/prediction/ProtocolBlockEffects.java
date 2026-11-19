package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;

import java.util.ArrayList;
import java.util.List;


public final class ProtocolBlockEffects {
    private static final float DOWN_ACCELERATION = -0.03F;
    private static final float ABOVE_DOWN_CAP = -0.9F;
    private static final float ABOVE_UP_ACCELERATION = 0.1F;
    private static final float ABOVE_UP_CAP = 1.8F;
    private static final float INSIDE_DOWN_CAP = -0.3F;
    private static final float INSIDE_UP_ACCELERATION = 0.06F;
    private static final float INSIDE_UP_CAP = 0.7F;

    private ProtocolBlockEffects() {}

    public static List<BubbleContact> bubbleContacts(CompensatedWorld world, Box actor, int dimension) {
        List<BubbleContact> contacts = new ArrayList<>();
        int minX=(int)Math.floor(actor.minX), minY=(int)Math.floor(actor.minY), minZ=(int)Math.floor(actor.minZ);
        int maxX=(int)Math.floor(Math.nextDown(actor.maxX)), maxY=(int)Math.floor(Math.nextDown(actor.maxY)), maxZ=(int)Math.floor(Math.nextDown(actor.maxZ));
        for(int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
            if (!"minecraft:bubble_column".equals(world.blockIdentifierAt(x,y,z,dimension,0))) continue;
            boolean dragDown = world.blockBooleanPropertyAt(x,y,z,dimension,0,"drag_down",
                    world.blockBooleanPropertyAt(x,y,z,dimension,0,"drag_down_bit",false));
            ContactState state = world.isAirAt(x,y+1,z,dimension) ? ContactState.ABOVE : ContactState.INSIDE;
            contacts.add(new BubbleContact(x,y,z,dragDown,state));
        }
        return List.copyOf(contacts);
    }

    public static Vec3 applyBubble(Vec3 velocity, List<BubbleContact> contacts) {
        Vec3 result = velocity.clone();
        for (BubbleContact contact : contacts) {
            float candidate;
            float cap;
            if (contact.dragDown()) {
                candidate = result.y + DOWN_ACCELERATION;
                cap = contact.state() == ContactState.ABOVE ? ABOVE_DOWN_CAP : INSIDE_DOWN_CAP;
                result.y = cap > candidate ? cap : candidate;
            } else {
                candidate = result.y + (contact.state() == ContactState.ABOVE
                        ? ABOVE_UP_ACCELERATION : INSIDE_UP_ACCELERATION);
                cap = contact.state() == ContactState.ABOVE ? ABOVE_UP_CAP : INSIDE_UP_CAP;
                result.y = cap < candidate ? cap : candidate;
            }
        }
        return result;
    }

    public static HoneyResult applyHoney(Vec3 velocity, Vec3 position, float width,
                                         CompensatedWorld world, Box actor, int dimension) {
        Vec3 result = velocity.clone();
        boolean resetFallDistance = false;
        for (int x=(int)Math.floor(actor.minX); x<=(int)Math.floor(Math.nextDown(actor.maxX)); x++)
            for (int y=(int)Math.floor(actor.minY); y<=(int)Math.floor(Math.nextDown(actor.maxY)); y++)
                for (int z=(int)Math.floor(actor.minZ); z<=(int)Math.floor(Math.nextDown(actor.maxZ)); z++)
                    if ("minecraft:honey_block".equals(world.blockIdentifierAt(x,y,z,dimension,0))) {
                        result.x *= 0.4F;
                        result.y = -0.12F > result.y ? -0.12F : result.y;
                        result.z *= 0.4F;
                        if (position.y <= y + 0.9375F && result.y < 0) {
                            float sideLimit = width * 0.5F + 0.4312500059604645F;
                            resetFallDistance = Math.abs(x + 0.5F - position.x) > sideLimit
                                    || Math.abs(z + 0.5F - position.z) > sideLimit;
                        }
                    }
        return new HoneyResult(result, resetFallDistance);
    }

    public record BubbleContact(int x, int y, int z, boolean dragDown, ContactState state) {}
    public record HoneyResult(Vec3 velocity, boolean resetFallDistance) {}
    public enum ContactState { ABOVE, INSIDE }
}
