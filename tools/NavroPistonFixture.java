package ac.navro.anticheat.data.block;

import ac.navro.anticheat.collision.DynamicCollision;
import ac.navro.anticheat.collision.block.PistonArmBlock;
import ac.navro.anticheat.collision.block.PistonBlock;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.util.math.Box;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroPistonFixture {
    private static final class World extends CompensatedWorldImpl {
        CompoundTag tag;
        int x,y,z,reads;
        World(NavroPlayer player) { super(player); }
        @Override public CompoundTag getBlockEntityTag(int x,int y,int z) {
            if(x!=this.x||y!=this.y||z!=this.z)throw new AssertionError("wrong base lookup");
            reads++;return tag;
        }
    }
    private static Unsafe unsafe() throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (Unsafe)f.get(null);
    }
    private static void box(DataOutputStream out,Box b) throws Exception {
        out.writeFloat(b.minX);out.writeFloat(b.minY);out.writeFloat(b.minZ);
        out.writeFloat(b.maxX);out.writeFloat(b.maxY);out.writeFloat(b.maxZ);
    }
    private static void tag(DataOutputStream out,CompoundTag tag) throws Exception {
        if(tag==null){out.writeInt(-1);return;}
        byte[] bytes=NBTIO.write(tag,ByteOrder.BIG_ENDIAN,false);out.writeInt(bytes.length);out.write(bytes);
    }
    private static CompoundTag directionProperties(int i) {
        var props=new CompoundTag("states");
        switch(i%13) {
            case 0->{ }
            case 1->props.putInt("facing_direction",i%9-1);
            case 2->props.putString("facing_direction",String.valueOf(i%9-1));
            case 3->props.putFloat("facing_direction",(i%9-1)+0.5F);
            case 4->props.putString("minecraft:cardinal_direction","NORTH").putInt("facing_direction",5);
            case 5->props.putString("cardinal_direction","east");
            case 6->props.putString("minecraft:cardinal_direction","south").putString("cardinal_direction","west");
            case 7->props.putString("minecraft:cardinal_direction","up").putString("cardinal_direction","west");
            case 8->props.putString("minecraft:cardinal_direction","invalid").putInt("facing_direction",4);
            case 9->props.putString("facing_direction","bad");
            case 10->props.putByte("facing_direction",1);
            case 11->props.putString("cardinal_direction","WEST").putByte("facing_direction",0);
            default->props.putString("minecraft:cardinal_direction","").putString("cardinal_direction","south");
        }
        return props;
    }
    private static CompoundTag actorTag(int i) {
        var tag=new CompoundTag();
        float[] progress={-1,-0.0F,0,Float.MIN_VALUE,0.24999F,0.25F,Math.nextUp(0.25F),0.250001F,
                0.2500011F,0.5F,1,Math.nextUp(1F),2,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY};
        float value=progress[(i/15)%progress.length];
        switch(i%15) {
            case 0-> {return null;}
            case 1-> {return tag.putFloat("Progress",value);}
            case 2->tag.putByte("State",0);
            case 3->tag.putByte("State",1).putFloat("LastProgress",value).putFloat("Progress",1-value);
            case 4->tag.putByte("State",3).putFloat("Progress",value).putFloat("LastProgress",1-value);
            case 5->tag.putByte("State",2).putFloat("Progress",value);
            case 6->tag.putByte("State",1).putFloat("Progress",value);
            case 7->tag.putByte("State",3).putFloat("LastProgress",value);
            case 8->tag.putInt("State",257).putFloat("LastProgress",value);
            case 9->tag.putLong("State",3).putDouble("Progress",value);
            case 10->tag.putFloat("State",1.9F).putInt("LastProgress",(int)value);
            case 11->tag.putShort("State",1).putLong("LastProgress",(long)value);
            case 12->tag.putByte("State",3).putString("Progress","0.5");
            case 13->tag.putString("State","1").putFloat("LastProgress",value);
            default->tag.putByte("State",1).putByte("LastProgress",(byte)(i%2));
        }
        return tag;
    }
    public static void main(String[] args) throws Exception {
        var player=(NavroPlayer)unsafe().allocateInstance(NavroPlayer.class);
        var world=new World(player);
        var field=NavroPlayer.class.getDeclaredField("compensatedWorld");field.setAccessible(true);field.set(player,world);
        var adapter=DynamicCollision.class.getDeclaredMethod("pistonFacingDirection",NetworkBlockState.class,BlockFace.class);
        adapter.setAccessible(true);
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E504932);out.writeInt(Block.DATA_BITS);
            for(int id:new int[]{BlockID.STICKY_PISTON,BlockID.PISTON,BlockID.PISTON_HEAD,BlockID.MOVING_BLOCK,BlockID.PISTON_HEAD_STICKY})out.writeInt(id);
            out.writeInt(Block.DATA_MASK+1);
            for(int meta=0;meta<=Block.DATA_MASK;meta++) {
                out.writeInt(new BlockPistonHead(meta).getBlockFace().getIndex());
                out.writeInt(new BlockPistonHeadSticky(meta).getBlockFace().getIndex());
            }
            int directions=2600;out.writeInt(directions);
            for(int i=0;i<directions;i++) {
                int meta=(i*127)&Block.DATA_MASK;
                Block legacy=switch(i%3){case 0->new BlockPistonHead(meta);case 1->new BlockPistonHeadSticky(meta);default->new BlockStone();};
                var stateTag=new CompoundTag().putString("name","minecraft:piston_arm_collision").putCompound("states",directionProperties(i));
                var state=new NetworkBlockState(0,legacy.getFullId(),stateTag);
                BlockFace fallback=legacy instanceof BlockPistonHead h?h.getBlockFace():BlockFace.DOWN;
                var expected=(BlockFace)adapter.invoke(null,state,fallback);
                out.writeInt(legacy.getFullId());tag(out,stateTag);out.writeInt(expected.getIndex());
            }
            int geometry=3360;out.writeInt(geometry);
            int[][] positions={{1,64,1},{-17,-64,31},{Integer.MAX_VALUE,319,Integer.MIN_VALUE},{16777217,0,-16777217}};
            for(int i=0;i<geometry;i++) {
                int direction=i%7-1;BlockFace face=direction<0?null:BlockFace.values()[direction];
                int[] p=positions[(i/7)%positions.length];var pos=new BlockVector3(p[0],p[1],p[2]);
                boolean hasPlayer=i%29!=0,hasPosition=i%31!=0;
                world.tag=actorTag(i/7);world.reads=0;
                if(face!=null){world.x=pos.x-face.getXOffset();world.y=pos.y-face.getYOffset();world.z=pos.z-face.getZOffset();}
                out.writeInt(direction);out.writeBoolean(hasPlayer);out.writeBoolean(hasPosition);
                out.writeInt(pos.x);out.writeInt(pos.y);out.writeInt(pos.z);tag(out,world.tag);
                try {
                    var shapes=PistonArmBlock.getCollisionShape(hasPlayer?player:null,hasPosition?pos:null,null,face);
                    out.writeBoolean(true);out.writeInt(shapes.size());
                    for(var shape:shapes){box(out,shape);box(out,shape.offset(pos.x,pos.y,pos.z));}
                }catch(RuntimeException e){out.writeBoolean(false);out.writeUTF(e.getClass().getSimpleName());}
                out.writeInt(world.reads);
            }
            String[] names={"minecraft:piston","minecraft:sticky_piston","minecraft:other",""};
            out.writeInt(names.length);
            for(String name:names){
                var stateTag=new CompoundTag().putString("name",name).putCompound("states",new CompoundTag("states"));
                var state=new NetworkBlockState(0,BlockID.PISTON<<Block.DATA_BITS,stateTag);
                var piston=PistonBlock.from(state,new BlockPistonSticky());
                tag(out,stateTag);out.writeInt(piston.type().value());
                var shapes=piston.getCollisionShape();out.writeInt(shapes.size());for(var b:shapes)box(out,b);
            }
            out.writeInt(PistonBlock.from(null,null).type().value());
        }
        System.out.println("Original piston reference: 8192 metadata pairs, 2600 directions, 3360 geometry cases, 4 base types.");
    }
}
