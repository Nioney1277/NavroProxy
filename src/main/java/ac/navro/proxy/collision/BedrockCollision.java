package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;





public final class BedrockCollision {
    private BedrockCollision() { }
    public record Actor(float minY, float fallDistance, boolean descending,
                        boolean descendScaffolding, boolean leatherBoots) { }
    private record Key(String name, org.cloudburstmc.nbt.NbtMap properties) { }
    private record Shape(int kind, List<Box> boxes) { }
    private static final Map<Integer,Map<Key,Shape>> TABLES = new HashMap<>();

    private static Key key(NetworkBlockState state) {
        return new Key(state.identifier(),state.stateTag().getCompound("states"));
    }
    private static synchronized Map<Key,Shape> table(int protocol) {
        int palette=NetworkBlockStateRegistry.paletteProtocol(protocol);
        return TABLES.computeIfAbsent(palette, BedrockCollision::load);
    }
    private static Map<Key,Shape> load(int palette) {
        if(palette<0)return Map.of();
        var resource=BedrockCollision.class.getResourceAsStream("/navro/bedrock-collisions-"+palette+".bin.gz");
        if(resource==null)throw new IllegalStateException("Missing Bedrock collision overrides: "+palette);
        var states=NetworkBlockStateRegistry.forProtocol(palette,false);
        var result=new HashMap<Key,Shape>();
        try(var in=new DataInputStream(new GZIPInputStream(resource))){
            if(in.readInt()!=0x4e424331)throw new IOException("Invalid Bedrock collision table");
            int count=in.readInt();
            for(int i=0;i<count;i++){
                var state=states.resolve(in.readInt());int kind=in.readUnsignedByte(),n=in.readUnsignedShort();
                var boxes=new ArrayList<Box>(n);
                for(int j=0;j<n;j++)boxes.add(new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
                if(state==null)throw new IOException("Override state missing from palette");
                if(kind!=0)result.put(key(state),new Shape(kind,List.copyOf(boxes)));
            }
            if(in.read()!=-1)throw new IOException("Trailing override data");
        }catch(IOException e){throw new IllegalStateException("Cannot read Bedrock collision overrides",e);}
        return Map.copyOf(result);
    }


    public static List<Box> getCollisionBox(CompensatedWorld world, int x,int y,int z,
                                           NetworkBlockState state, Actor actor) {
        if(state==null||state.stateTag()==null)return null;
        Shape shape=table(world.protocolVersion()).get(key(state));
        if(shape==null)return null;
        return switch(shape.kind()){
            case 1 -> shape.boxes();
            case 2 -> door(world,x,y,z,state);
            case 3 -> actor!=null&&actor.minY()>=y+1F-Box.COLLISION_EPSILON&&!actor.descendScaffolding()
                    ? List.of(new Box(0,.875F,0,1,1,1)):List.of();
            case 4 -> powder(y,actor);
            default -> null;
        };
    }
    private static List<Box> powder(int y,Actor a){
        if(a==null||a.minY()<y+1F-Math.ulp(1F)||a.descending())return List.of();
        if(a.fallDistance()>2.5F)return List.of(new Box(0,0,0,1,.9F,1));
        return a.leatherBoots()?List.of(new Box(0,0,0,1,1,1)):List.of();
    }
    private static List<Box> door(CompensatedWorld w,int x,int y,int z,NetworkBlockState state){
        boolean top=state.booleanProperty("upper_block_bit",(state.blockData()&8)!=0);
        var other=w.networkBlockState(w.getRawBlockAt(x,y+(top?-1:1),z,0));
        boolean paired=other!=null&&state.identifier().equals(other.identifier());
        var lower=top&&paired?other:state;var upper=!top&&paired?other:state;
        String cardinal=lower.stringProperty("minecraft:cardinal_direction",lower.stringProperty("cardinal_direction",""));
        int direction=switch(cardinal){case "north"->3;case "east"->0;case "south"->1;case "west"->2;
            default->(lower.intProperty("direction",lower.blockData()&3)+1)&3;};
        boolean open=lower.booleanProperty("open_bit",(lower.blockData()&4)!=0);
        boolean right=upper.booleanProperty("door_hinge_bit",(upper.blockData()&1)!=0);

        int side=switch(direction){case 2->!open?0:right?3:1;case 3->!open?1:right?0:2;
            case 0->!open?2:right?1:3;default->!open?3:right?2:0;};
        return List.of(switch(side){case 0->new Box(0,0,0,1,1,.1825F);
            case 1->new Box(.8175F,0,0,1,1,1);case 2->new Box(0,0,.8175F,1,1,1);
            default->new Box(0,0,0,.1825F,1,1);});
    }
}
