package ac.navro.proxy.data.block;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class UseItemOnPropertiesReferenceTest {
    static long hash(long h,NativeUseItemOnProperties.Properties p){h=(h^p.flags())*0x100000001b3L;h=(h^p.bellAxis())*0x100000001b3L;h=(h^p.attachment())*0x100000001b3L;return (h^p.vaultState())*0x100000001b3L;}
    @Test void allNativePositionalMetadataMatchesActualSpecialInteractionProperties()throws Exception {
        try(var in=new DataInputStream(getClass().getResourceAsStream("/native-block-use-item-on.bin"))) {
            assertEquals(0x4e555231,in.readInt());int count=in.readInt();assertEquals(2048,count);
            for(int id=0;id<count;id++){long hash=0xcbf29ce484222325L;for(int meta=0;meta<=BlockStateEncoding.DATA_MASK;meta++)hash=hash(hash,NativeUseItemOnProperties.forBlock(id,meta));assertEquals(in.readLong(),hash,"native item-on-block id="+id);}
            count=in.readInt();assertEquals(25,count);for(int i=0;i<count;i++){int id=in.readInt(),meta=in.readInt();var expected=new NativeUseItemOnProperties.Properties(in.readUnsignedShort(),in.readByte(),in.readByte(),in.readUnsignedByte());assertEquals(expected,NativeUseItemOnProperties.forBlock(id,meta));}assertEquals(-1,in.read());
        }
    }
    @Test void unexportedNativeCustomFactoriesAndOutOfRangeMetadataRemainExplicitErrors() {
        assertTrue(NativeUseItemOnProperties.forBlock(461,0).bell());assertFalse(NativeUseItemOnProperties.forBlock(2047,0).sign());
        assertThrows(IllegalArgumentException.class,()->NativeUseItemOnProperties.forBlock(10000,0));assertThrows(IllegalArgumentException.class,()->NativeUseItemOnProperties.forBlock(1,-1));assertThrows(IllegalArgumentException.class,()->NativeUseItemOnProperties.forBlock(1,8192));
    }
}
