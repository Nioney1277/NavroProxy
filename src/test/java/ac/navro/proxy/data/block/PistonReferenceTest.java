package ac.navro.proxy.data.block;

import ac.navro.proxy.collision.block.PistonArmBlock;
import ac.navro.proxy.collision.block.PistonBlock;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PistonReferenceTest {
    private static final class ProbeWorld extends CompensatedWorld {
        NbtMap tag; Vector3i expected; int reads;
        @Override public NbtMap getBlockEntityTag(int x,int y,int z) {
            assertEquals(expected,Vector3i.from(x,y,z));reads++;return tag;
        }
    }
    private static NbtMap tag(DataInputStream in) throws Exception {
        int length=in.readInt();if(length<0)return null;
        try(var reader=NbtUtils.createReader(new ByteArrayInputStream(in.readNBytes(length)))) {
            return (NbtMap)reader.readTag();
        }
    }
    private static void bits(DataInputStream in,Box b,String detail) throws Exception {
        for(float value:new float[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})
            assertEquals(in.readInt(),Float.floatToRawIntBits(value),detail);
    }
    @Test void nativeMetadataDirectionAdaptersAndEveryGeometryFloatBitMatchOriginalNavro() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-pistons.bin.gz")))) {
            assertEquals(0x4E504932,in.readInt());assertEquals(BlockStateEncoding.DATA_BITS,in.readInt());
            for(int id:new int[]{NativePistonProperties.STICKY_PISTON,NativePistonProperties.PISTON,
                    NativePistonProperties.PISTON_HEAD,NativePistonProperties.MOVING_BLOCK,NativePistonProperties.PISTON_HEAD_STICKY})
                assertEquals(id,in.readInt());
            int metas=in.readInt();assertEquals(8192,metas);
            for(int meta=0;meta<metas;meta++)for(int id:new int[]{NativePistonProperties.PISTON_HEAD,NativePistonProperties.PISTON_HEAD_STICKY}) {
                var state=NetworkBlockState.legacy(BlockStateEncoding.fullId(id,meta));
                assertEquals(Direction.values()[in.readInt()],NativePistonProperties.headFacing(state),"meta="+meta);
            }
            int directions=in.readInt();assertEquals(2600,directions);
            for(int i=0;i<directions;i++) {
                int legacy=in.readInt();NbtMap stateTag=tag(in);
                var state=new NetworkBlockState(0,legacy,stateTag);
                assertEquals(Direction.values()[in.readInt()],NativePistonProperties.headFacing(state),"direction="+i);
            }
            int count=in.readInt();assertEquals(3360,count);
            for(int i=0;i<count;i++) {
                int direction=in.readInt();Direction face=direction<0?null:Direction.values()[direction];
                boolean hasWorld=in.readBoolean(),hasPosition=in.readBoolean();
                Vector3i position=Vector3i.from(in.readInt(),in.readInt(),in.readInt());
                var world=new ProbeWorld();world.tag=tag(in);
                if(face!=null)world.expected=Vector3i.from(position.getX()-face.getXOffset(),position.getY()-face.getYOffset(),position.getZ()-face.getZOffset());
                if(in.readBoolean()) {
                    var shapes=PistonArmBlock.getCollisionShape(hasWorld?world:null,hasPosition?position:null,null,face);
                    assertEquals(in.readInt(),shapes.size(),"case="+i);
                    for(Box b:shapes) {
                        bits(in,b,"local case="+i);
                        bits(in,b.offset(position.getX(),position.getY(),position.getZ()),"world case="+i);
                    }
                }else {
                    String error=in.readUTF();
                    var thrown=assertThrows(RuntimeException.class,()->PistonArmBlock.getCollisionShape(hasWorld?world:null,hasPosition?position:null,null,face));
                    assertEquals(error,thrown.getClass().getSimpleName(),"case="+i);
                }
                assertEquals(in.readInt(),world.reads,"reads case="+i);
            }
            int bases=in.readInt();assertEquals(4,bases);
            for(int i=0;i<bases;i++) {
                var piston=PistonBlock.from(new NetworkBlockState(0,0,tag(in)));
                assertEquals(in.readInt(),piston.type().value());
                var shapes=piston.getCollisionShape();assertEquals(in.readInt(),shapes.size());
                for(Box b:shapes)bits(in,b,"base="+i);
            }
            assertEquals(in.readInt(),PistonBlock.from(null).type().value());
            assertEquals(-1,in.read());
        }
    }
}
