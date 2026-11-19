package ac.navro.proxy.data.block;

import ac.navro.proxy.validator.inventory.ItemTransactionValidator;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionPropertiesReferenceTest {
    @Test void allNativePositionalStatesMatchActualWaterLavaClassAndUseWithoutItemResults()throws Exception {
        try(var in=new DataInputStream(getClass().getResourceAsStream("/native-block-interactions.bin"))) {
            assertEquals(0x4e495231,in.readInt());int count=in.readInt();assertEquals(2048,count);
            for(int id=0;id<count;id++) {
                long checksum=0xcbf29ce484222325L;
                for(int meta=0;meta<=BlockStateEncoding.DATA_MASK;meta++)checksum=(checksum^NativeInteractionProperties.forBlock(id,meta).flags())*0x100000001b3L;
                assertEquals(in.readLong(),checksum,"native interaction id="+id);
            }
            count=in.readInt();assertEquals(25,count);
            for(int i=0;i<count;i++){int id=in.readInt(),meta=in.readInt(),flags=in.readUnsignedByte();assertEquals(flags,NativeInteractionProperties.forBlock(id,meta).flags());}
            assertEquals(-1,in.read());
        }
    }
    @Test void typeFlagsAreNotInferredFromNamesAndUnexportedNativeCustomFactoriesAreRejected() {
        assertTrue(NativeInteractionProperties.forBlock(8,0).water());assertTrue(NativeInteractionProperties.forBlock(9,8191).water());
        assertFalse(NativeInteractionProperties.forBlock(10,0).water());assertTrue(NativeInteractionProperties.forBlock(10,0).lava());assertTrue(NativeInteractionProperties.forBlock(11,0).lava());
        assertTrue(ItemTransactionValidator.canUseWithoutItem(NetworkBlockState.legacy(64<<13)));assertFalse(ItemTransactionValidator.canUseWithoutItem(null));
        assertFalse(ItemTransactionValidator.canUseWithoutItem(NetworkBlockState.legacy(1<<13)));
        assertThrows(IllegalArgumentException.class,()->NativeInteractionProperties.forBlock(10000,0));assertThrows(IllegalArgumentException.class,()->NativeInteractionProperties.forBlock(1,8192));assertThrows(IllegalArgumentException.class,()->NativeInteractionProperties.forBlock(1,-1));
    }
}
