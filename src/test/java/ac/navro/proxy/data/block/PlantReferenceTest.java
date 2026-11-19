package ac.navro.proxy.data.block;

import ac.navro.proxy.collision.PlantCollision;
import ac.navro.anticheat.util.math.Box;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PlantReferenceTest {
    private static void bits(DataInputStream in, Box b, String detail) throws Exception {
        for(float value:new float[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})
            assertEquals(in.readInt(),Float.floatToRawIntBits(value),detail);
    }
    @Test void allNativePlantMetadataValuesIncludingInvalidPropertiesMatchBundledMot() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/native-plant-metadata.bin.gz")))) {
            assertEquals(0x4E504D31,in.readInt());assertEquals(BlockStateEncoding.DATA_BITS,in.readInt());
            assertEquals(NativePlantProperties.BAMBOO,in.readInt());assertEquals(NativePlantProperties.POINTED_DRIPSTONE,in.readInt());
            int count=in.readInt();assertEquals(8192,count);
            for(int meta=0;meta<count;meta++) {
                var bamboo=NetworkBlockState.legacy(BlockStateEncoding.fullId(NativePlantProperties.BAMBOO,meta));
                var drip=NetworkBlockState.legacy(BlockStateEncoding.fullId(NativePlantProperties.POINTED_DRIPSTONE,meta));
                assertEquals(in.readBoolean(),NativePlantProperties.bambooThick(bamboo),"meta="+meta);
                assertEquals(in.readBoolean(),NativePlantProperties.dripstoneHanging(drip),"meta="+meta);
                if(in.readBoolean())assertEquals(in.readUTF(),NativePlantProperties.dripstoneThickness(drip),"meta="+meta);
                else {
                    String type=in.readUTF(),message=in.readUTF();
                    var error=assertThrows(IllegalArgumentException.class,()->NativePlantProperties.dripstoneThickness(drip));
                    assertEquals(type,error.getClass().getSimpleName());assertEquals(message,error.getMessage());
                }
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void everyLocalAndWorldShapeFloatBitMatchesOriginalNavroAcrossFullIntCoordinates() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-plants.bin.gz")))) {
            assertEquals(0x4E504C31,in.readInt());int count=in.readInt();assertEquals(4000,count);
            for(int i=0;i<count;i++) {
                boolean bamboo=in.readBoolean();var position=Vector3i.from(in.readInt(),in.readInt(),in.readInt());
                int legacy=in.readInt();byte[] bytes=in.readNBytes(in.readInt());NbtMap tag;
                try(var reader=NbtUtils.createReader(new ByteArrayInputStream(bytes))){tag=(NbtMap)reader.readTag();}
                var state=new NetworkBlockState(0,legacy,tag);
                var shapes=bamboo?PlantCollision.bamboo(position,state):PlantCollision.pointedDripstone(position,state);
                assertEquals(1,shapes.size());Box shape=shapes.get(0);
                bits(in,shape,"local case="+i);bits(in,shape.offset(position.getX(),position.getY(),position.getZ()),"world case="+i);
            }
            assertEquals(-1,in.read());
        }
    }
}
