package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.collision.CollisionPhysicsState;
import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.data.block.NavroBlockState;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.BedrockBlockProperties;
import ac.navro.proxy.prediction.fluid.FluidBlockCollision;
import java.util.ArrayList;
import java.util.List;


final class VehicleEnvironment {
    final SessionState session;
    VehicleEnvironment(SessionState session) { this.session=session; }
    NetworkBlockState block(int x,int y,int z,int layer) {
        return session.world().networkBlockState(session.world().getRawBlockAt(x,y,z,layer));
    }
    NetworkBlockState block(Vec3 p) { return block(floor(p.x),floor(p.y),floor(p.z),0); }
    NetworkBlockState liquid(int x,int y,int z) {
        var overlay=block(x,y,z,1);
        return kind(overlay)!=FluidState.Type.EMPTY ? overlay : block(x,y,z,0);
    }
    static FluidState.Type kind(NetworkBlockState block) {
        if(block==null)return FluidState.Type.EMPTY;
        return switch(block.identifier()) {
            case "minecraft:water","minecraft:flowing_water","minecraft:bubble_column" -> FluidState.Type.WATER;
            case "minecraft:lava","minecraft:flowing_lava" -> FluidState.Type.LAVA;
            default -> FluidState.Type.EMPTY;
        };
    }
    static float surface(NetworkBlockState block,int y) {
        int depth=block.intProperty("liquid_depth",0);
        float unit=(depth<8?depth+1:1)/9F;
        return (float)(y+1)-(unit-1F/9F);
    }
    boolean wet(Box body,FluidState.Type type) {

        Box query=type==FluidState.Type.WATER?body.contract(.001F,.401F,.001F):body.contract(.1F,.4F,.1F);
        return hasLiquid(query,type);
    }
    boolean hasLiquid(Box query,FluidState.Type type) {
        for(var p:cells(query))if(kind(liquid(p[0],p[1],p[2]))==type)return true;
        return false;
    }
    FluidState.Type medium(Box box) {
        if(wet(box,FluidState.Type.LAVA))return FluidState.Type.LAVA;
        return wet(box,FluidState.Type.WATER)?FluidState.Type.WATER:FluidState.Type.EMPTY;
    }
    float friction(NetworkBlockState block) {
        return block==null||block.is("minecraft:air")? .9F:new NavroBlockState(block).getFriction();
    }
    boolean preventsJump(Vec3 feet,boolean ground) {
        if(!ground)return false;
        var at=block(feet);
        if(prevents(at))return true;
        return (feet.y<=floor(feet.y)||property(at,BedrockBlockProperties.CHECK_BELOW))
                && prevents(block(floor(feet.x),floor(feet.y)-1,floor(feet.z),0));
    }
    private static boolean prevents(NetworkBlockState state) {
        if(state==null)return false;
        if(state.bedrockProperties()!=null)return property(state,BedrockBlockProperties.PREVENT_JUMPING);
        return state.is("minecraft:honey_block");
    }
    private static boolean property(NetworkBlockState state,long flag) {
        return state!=null&&state.bedrockProperties()!=null&&state.bedrockProperties().has(flag);
    }
    boolean climbing(Vec3 feet) {
        var state=block(feet);return state!=null&&new NavroBlockState(state).hasClimbableProperty();
    }
    boolean scaffolding(Box body) {
        for(var p:cells(body.withMaxY(body.minY+.01F))) {
            var state=block(p[0],p[1],p[2],0);
            if(state!=null&&state.is("minecraft:scaffolding"))return true;
        }
        return false;
    }
    NavroBlockState support(Box body) {
        Box query=body.offset(0,-.2F,0);
        var entries=session.world().collisionEntries(query,session.world().dimension(),new CollisionPhysicsState());
        var selected=entries.stream().filter(e->e.shape().intersects(query)&&e.block()!=null)
                .max(java.util.Comparator.comparingDouble(e->e.shape().maxY)).orElse(null);
        return new NavroBlockState(selected!=null?selected.block():block(new Vec3(
                (body.minX+body.maxX)*.5F,body.minY-.2F,(body.minZ+body.maxZ)*.5F)));
    }
    Vec3 honey(Vec3 velocity,Box body) {
        int count=0;
        for(var p:cells(body.contract(.001F))) {
            var state=block(p[0],p[1],p[2],0);
            if(state!=null&&state.is("minecraft:honey_block"))count++;
        }
        if(count==0)return velocity;
        Vec3 result=velocity.clone();
        for(int i=0;i<count;i++)result=new Vec3(result.x*.4F,Math.max(result.y,-.12F),result.z*.4F);
        return result;
    }
    Vec3 honeySlide(Vec3 velocity,Box body) {
        if(velocity.y>=-.12F)return velocity;
        float radius=body.getLengthX()*.5F;
        for(var p:cells(body.contract(.001F))) {
            var state=block(p[0],p[1],p[2],0);
            if(state!=null&&state.is("minecraft:honey_block")&&body.minY<=p[1]+.9375F
                    &&(Math.abs((body.minX+body.maxX)*.5F-p[0]-.5F)>radius+.43125F
                    ||Math.abs((body.minZ+body.maxZ)*.5F-p[2]-.5F)>radius+.43125F))
                return new Vec3(velocity.x*.4F,Math.max(velocity.y,-.12F),velocity.z*.4F);
        }
        return velocity;
    }
    Vec3 slowdown(Box body,boolean weaving) {
        boolean web=false,berry=false,snow=false;
        for(var p:cells(body.contract(.001F))) {
            var state=block(p[0],p[1],p[2],0);
            if(state==null)continue;
            web|=state.is("minecraft:web")||state.is("minecraft:cobweb");
            berry|=state.is("minecraft:sweet_berry_bush");snow|=state.is("minecraft:powder_snow");
        }
        Vec3 result=null;
        if(web)result=weaving&&!berry&&!snow?new Vec3(.5F,.25F,.5F):new Vec3(.25F,.05F,.25F);
        if(berry)result=min(result,new Vec3(.8F,.75F,.8F));
        if(snow)result=min(result,new Vec3(.9F,1.5F,.9F));
        return result;
    }
    private static Vec3 min(Vec3 a,Vec3 b) {return a==null?b:new Vec3(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z));}
    boolean clear(Box body) {
        var w=session.world();
        return w.hasChunksAt(floor(body.minX),floor(body.minZ),floor(body.maxX),floor(body.maxZ),w.dimension())
                &&w.collisionEntries(body,w.dimension(),new CollisionPhysicsState()).stream().noneMatch(e->e.shape().intersects(body))
                &&!hasLiquid(body,FluidState.Type.WATER)&&!hasLiquid(body,FluidState.Type.LAVA);
    }
    boolean liquidClimbOut(Box box,Vec3 previous,Vec3 next,Vec3 velocity,boolean horizontal,FluidState.Type priorMedium) {
        if(priorMedium==FluidState.Type.EMPTY||!horizontal)return false;
        float dy=velocity.y+.6F-next.y+previous.y;
        Box clearance=box.offset(velocity.x,dy,velocity.z);
        return clear(clearance);
    }
    Vec3 flow(Box body) { return flow(body,null); }
    Vec3 flow(Box body,BoatParameters boat) {
        var medium=medium(body);
        if(medium==FluidState.Type.EMPTY)return Vec3.ZERO.clone();
        Box query=medium==FluidState.Type.WATER?body.contract(.001F,.401F,.001F):body.contract(.1F,.4F,.1F);
        double x=0,y=0,z=0;boolean flowing=false;
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1}};
        for(var p:cells(query)) {
            var state=liquid(p[0],p[1],p[2]);
            if(kind(state)!=medium || boat!=null&&!boat.accepts(state.identifier()))continue;
            double[] f=flowAt(p[0],p[1],p[2],state);
            x+=f[0];y+=f[1];z+=f[2];
            flowing|=state.intProperty("liquid_depth",0)>0;
            for(var d:offsets) {
                boolean edge=d[0]<0?p[0]==floor(query.minX):d[0]>0?p[0]==floor(Math.nextDown(query.maxX))
                        :d[1]<0?p[2]==floor(query.minZ):p[2]==floor(Math.nextDown(query.maxZ));
                if(!edge)continue;
                var next=liquid(p[0]+d[0],p[1],p[2]+d[1]);
                flowing|=kind(next)==medium&&(boat==null||boat.accepts(next.identifier()))&&next.intProperty("liquid_depth",0)>0;
            }
        }
        double length=Math.sqrt(x*x+y*y+z*z);
        double push=medium==FluidState.Type.LAVA?.0035000001080334187D:.014D;
        return !flowing||length<.0001?Vec3.ZERO.clone():new Vec3(x/length*push,y/length*push,z/length*push);
    }
    private double[] flowAt(int x,int y,int z,NetworkBlockState state) {
        var props=state.stateTag().getCompound("states");
        if(props.containsKey("flow_x")||props.containsKey("flow_y")||props.containsKey("flow_z"))
            return normalize(number(props.get("flow_x")),number(props.get("flow_y")),number(props.get("flow_z")));
        int raw=state.intProperty("liquid_depth",0),depth=raw>=8?0:raw;
        double fx=0,fz=0;
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1}};
        for(var d:offsets) {
            var next=liquid(x+d[0],y,z+d[1]);int delta;
            if(kind(next)==kind(state)) {int v=next.intProperty("liquid_depth",0);delta=(v>=8?0:v)-depth;}
            else {
                if(blocksMotion(x+d[0],y,z+d[1]))continue;
                next=liquid(x+d[0],y-1,z+d[1]);if(kind(next)!=kind(state))continue;
                int v=next.intProperty("liquid_depth",0);delta=(v>=8?0:v)+8-depth;
            }
            fx+=d[0]*delta;fz+=d[1]*delta;
        }
        if(raw>=8)for(var d:offsets)if(solid(x+d[0],y,z+d[1])||solid(x+d[0],y+1,z+d[1])) {
            double[] flat=normalize(fx,0,fz);return normalize(flat[0],-6,flat[2]);
        }
        return normalize(fx,0,fz);
    }
    private static double number(Object x) {return x instanceof Number n?n.doubleValue():x==null?0:Double.parseDouble(x.toString());}
    private static double[] normalize(double x,double y,double z) {
        double n=Math.sqrt(x*x+y*y+z*z);return n<.0001?new double[3]:new double[]{x/n,y/n,z/n};
    }
    private boolean blocksMotion(int x,int y,int z) {
        var state=block(x,y,z,0);
        return state!=null&&FluidBlockCollision.blocksMotion(session.world(),state);
    }
    private boolean solid(int x,int y,int z) {
        var state=block(x,y,z,0);
        return state!=null&&FluidBlockCollision.isFaceSturdy(session.world(),state);
    }
    static List<int[]> cells(Box query) {
        var list=new ArrayList<int[]>();
        for(int x=floor(query.minX);x<=floor(Math.nextDown(query.maxX));x++)
            for(int y=floor(query.minY);y<=floor(Math.nextDown(query.maxY));y++)
                for(int z=floor(query.minZ);z<=floor(Math.nextDown(query.maxZ));z++)list.add(new int[]{x,y,z});
        return list;
    }
    static int floor(float x) {return (int)Math.floor(x);}
}
