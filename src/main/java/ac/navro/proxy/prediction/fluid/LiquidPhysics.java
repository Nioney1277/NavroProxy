package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;

import java.util.ArrayList;
import java.util.List;


public final class LiquidPhysics {
    public static final float WATER_PUSH = 0.014F;
    public static final float LAVA_PUSH = 0.0035F;
    private static final float MIN_FLOW_LENGTH_SQUARED = 0.0001F * 0.0001F;
    private LiquidPhysics() {}

    public static Result sample(CompensatedWorld world, Box actor, boolean flying, int dimension) {
        Sample lava = sample(world, actor, FluidState.Type.LAVA, LAVA_PUSH, 0.1F, 0.4F, dimension);
        Sample water = sample(world, actor, FluidState.Type.WATER, WATER_PUSH, 0.001F, 0.401F, dimension);
        FluidState.Type selected = lava.touching() ? FluidState.Type.LAVA
                : water.touching() ? FluidState.Type.WATER : FluidState.Type.EMPTY;
        Vec3 push = flying ? Vec3.ZERO : selected == FluidState.Type.LAVA
                ? lava.appliedPush() : selected == FluidState.Type.WATER ? water.appliedPush() : Vec3.ZERO;
        return new Result(water, lava, selected, push);
    }

    private static Sample sample(CompensatedWorld world, Box actor, FluidState.Type type,
                                 float strength, float horizontal, float vertical, int dimension) {
        Box region = actor.expand(1);
        if (!world.hasChunksAt((int) Math.floor(region.minX), (int) Math.floor(region.minZ),
                (int) Math.ceil(region.maxX), (int) Math.ceil(region.maxZ), dimension)) return Sample.EMPTY;
        Box box = contracted(actor, horizontal, vertical);
        int minX=(int)Math.floor(box.minX),maxX=(int)Math.floor(box.maxX+1.0D),minY=(int)Math.floor(box.minY),maxY=(int)Math.floor(box.maxY+1.0D),minZ=(int)Math.floor(box.minZ),maxZ=(int)Math.floor(box.maxZ+1.0D);
        boolean touching=false,downward=false,positive=false; float highest=-Float.MAX_VALUE; List<int[]> cells=new ArrayList<>();
        for(int x=minX;x<maxX;x++)for(int y=minY;y<maxY;y++)for(int z=minZ;z<maxZ;z++){
            FluidState fluid=world.fluidAt(x,y,z,dimension); if(fluid.type()!=type)continue;
            touching=true; highest=Math.max(highest,y+1F); cells.add(new int[]{x,y,z});
            positive |= fluid.level()>0 || positive(world,type,x-1,y,z,dimension)||positive(world,type,x+1,y,z,dimension)||positive(world,type,x,y,z-1,dimension)||positive(world,type,x,y,z+1,dimension);
        }
        Vec3 accumulated=Vec3.ZERO;
        if(positive) for(int[] c:cells){Vec3 flow=world.flowAt(c[0],c[1],c[2],dimension);accumulated=accumulated.add(flow);downward|=flow.y<0;}
        float length=accumulated.lengthSquared(); Vec3 applied=touching&&length>=MIN_FLOW_LENGTH_SQUARED?accumulated.multiply(strength/(float)Math.sqrt(length)):Vec3.ZERO;
        return new Sample(touching,touching?highest:0,downward,accumulated,applied,box);
    }

    private static boolean positive(CompensatedWorld world, FluidState.Type type,int x,int y,int z,int dimension){return world.fluidAt(x,y,z,dimension).type()==type&&world.fluidAt(x,y,z,dimension).level()>0;}
    private static Box contracted(Box s,float h,float v){float minX=s.minX+h,maxX=s.maxX-h,minY=s.minY+v,maxY=s.maxY-v,minZ=s.minZ+h,maxZ=s.maxZ-h;if(minX>maxX)minX=maxX=(s.minX+s.maxX)*.5F;if(minY>maxY)minY=maxY=(s.minY+s.maxY)*.5F;if(minZ>maxZ)minZ=maxZ=(s.minZ+s.maxZ)*.5F;return new Box(minX,minY,minZ,maxX,maxY,maxZ);}

    public record Sample(boolean touching,float surfaceHeight,boolean downwardFlow,Vec3 accumulatedFlow,Vec3 appliedPush,Box queryBox){static final Sample EMPTY=new Sample(false,0,false,Vec3.ZERO,Vec3.ZERO,Box.EMPTY);}
    public record Result(Sample water,Sample lava,FluidState.Type selectedType,Vec3 selectedPush){static final Result EMPTY=new Result(Sample.EMPTY,Sample.EMPTY,FluidState.Type.EMPTY,Vec3.ZERO);}
}
