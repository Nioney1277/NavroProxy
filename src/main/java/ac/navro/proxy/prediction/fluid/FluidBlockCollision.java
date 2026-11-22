package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.*;
import ac.navro.proxy.data.block.NativeFluidBlockProperties.Properties;
import static ac.navro.proxy.data.block.NativeFluidBlockProperties.*;
import ac.navro.proxy.collision.BedrockCollision;
import ac.navro.proxy.collision.PlantCollision;
import org.cloudburstmc.math.vector.Vector3i;
import java.io.*;
import java.util.*;




public final class FluidBlockCollision {
    private FluidBlockCollision() { }
    static final class Context {
        final WorldAccess compensatedWorld;
        Context(CompensatedWorld world){compensatedWorld=new WorldAccess(world);}
    }
    static final class WorldAccess {
        final CompensatedWorld world;
        WorldAccess(CompensatedWorld world){this.world=world;}
        State getBlockState(int x,int y,int z,int layer){return state(world,world.networkBlockState(world.getRawBlockAt(x,y,z,layer)));}
    }
    static final class State {
        private final NetworkBlockState network;
        private final Properties nativeState;
        State(NetworkBlockState network,Properties nativeState){this.network=network;this.nativeState=nativeState;}
        NetworkBlockState getNetworkState(){return network;}
        Properties getBlock(){return nativeState;}
        boolean isFaceSturdy(Context player){return nativeState.has(STURDY);}
    }
    private static State state(CompensatedWorld world,NetworkBlockState network){
        Properties properties=NativeFluidBlockProperties.forState(world.protocolVersion(),network);
        if(properties==null)throw new IllegalStateException("Fluid collision needs the original native implementation for "+(network==null?"uninitialized palette":network.identifier()));
        return new State(network,properties);
    }
    public static boolean isFaceSturdy(CompensatedWorld world,NetworkBlockState network){return state(world,network).nativeState.has(STURDY);}

    public static List<Box> movementCollision(CompensatedWorld world,Vector3i pos,NetworkBlockState network){
        Properties properties=NativeFluidBlockProperties.forState(world.protocolVersion(),network);
        if(properties==null)return null;
        if(properties.has(PASS_THROUGH))return List.of();
        if(!properties.has(FENCE|PANE|STAIRS|WALL|CHORUS)
                && !network.identifier().endsWith("_fence") && !network.identifier().endsWith("_wall"))return null;
        return FluidDynamicCollision.getCollisionBox(new Context(world),pos,new State(network,properties));
    }
    public static boolean blocksMotion(CompensatedWorld world,NetworkBlockState network){
        State block=state(world,network);
        if(block.nativeState.has(PASS_THROUGH)||block.nativeState.has(BAMBOO_SAPLING))return false;
        List<Box> boxes=findCollision(new Context(world),block);
        if(boxes.isEmpty())return false;
        Box box=new Box(0,0,0,0,0,0);
        for(Box fragment:boxes)box=fragment.union(box);
        return box.getAverageSideLength()>=0.7291666666666666 || box.getLengthY()>=1.0;
    }
    private static List<Box> findCollision(Context player,State state){
        CompensatedWorld world=player.compensatedWorld.world;
        NetworkBlockState network=state.network;
        if(world.getMinY()-41==0)return List.of(new Box(0,0,0,1,1,1));
        if(NativePistonProperties.isMovingBlock(network))return List.of();
        if(state.nativeState.has(SHULKER)||network.identifierContains("shulker_box"))
            return world.getShulkerCollision(Vector3i.ZERO);
        if(state.nativeState.has(PASS_THROUGH))return List.of();
        List<Box> collisions=FluidDynamicCollision.getCollisionBox(player,Vector3i.ZERO,state);
        if(collisions==null){


            if(network.is("minecraft:bamboo"))collisions=PlantCollision.bamboo(Vector3i.ZERO,network);
            else if(network.is("minecraft:pointed_dripstone"))collisions=PlantCollision.pointedDripstone(Vector3i.ZERO,network);
            else collisions=BedrockCollision.resolveCollision(world,0,0,0,network,world.fluidCollisionActor(),
                    state.nativeState.bedrockKind(),state.nativeState.bedrockShape());
        }
        return collisions==null?state.nativeState.generic():collisions;
    }
    private static final class DynamicShapes {
        static final Map<Integer,List<Box>> SHAPES=load();
        private static Map<Integer,List<Box>> load(){
            try(var in=new DataInputStream(new BufferedInputStream(Objects.requireNonNull(
                    FluidBlockCollision.class.getResourceAsStream("/navro/geyser-collisions.bin"))))){
                if(in.readInt()!=0x424f4152||in.readInt()!=2)throw new IOException("Invalid original Geyser collisions");
                int n=in.readUnsignedShort();var shapes=new ArrayList<List<Box>>(n);
                for(int i=0;i<n;i++){
                    int count=in.readUnsignedShort();var boxes=new ArrayList<Box>(count);
                    for(int j=0;j<count;j++)boxes.add(new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
                    shapes.add(List.copyOf(boxes));
                }
                int staticCount=in.readInt();for(int i=0;i<staticCount;i++){in.readInt();in.readUnsignedShort();}
                int dynamicCount=in.readInt();var result=new HashMap<Integer,List<Box>>(dynamicCount);
                for(int i=0;i<dynamicCount;i++)result.put(in.readInt(),shapes.get(in.readUnsignedShort()));
                if(in.read()!=-1)throw new IOException("Trailing original Geyser collisions");
                return Map.copyOf(result);
            }catch(IOException e){throw new IllegalStateException("Cannot read original dynamic collision shapes",e);}
        }
    }
    static List<Box> dynamicShape(int type,int state){return DynamicShapes.SHAPES.get(type<<24|state);}
}
