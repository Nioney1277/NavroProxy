package ac.navro.proxy.data.block;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementPropertiesReferenceTest {
    @Test void allNativeReplacementMetadataMatchesTheBundledMotIncludingUnknownStates()throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=new DataInputStream(getClass().getResourceAsStream("/native-block-replaceability.bin"));
            var hashed=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            assertEquals(0x4E525231,in.readInt());assertEquals(BlockStateEncoding.DATA_BITS,in.readInt());int count=in.readInt();assertEquals(2048,count);
            for(int id=0;id<count;id++) {
                for(int metadata=0;metadata<=BlockStateEncoding.DATA_MASK;metadata++) {
                    hashed.writeInt(metadata);hashed.writeBoolean(NativePlacementProperties.canBeReplaced(id,metadata));
                }
                assertArrayEquals(in.readNBytes(32),digest.digest(),"native replacement id="+id);
            }
            count=in.readInt();assertEquals(30,count);
            for(int i=0;i<count;i++) {
                int id=in.readInt(),metadata=in.readInt();assertEquals(in.readBoolean(),NativePlacementProperties.canBeReplaced(id,metadata));
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void snowLayersPlantsAndInvalidMetadataUseNativeResultsRatherThanNames() {
        assertTrue(NativePlacementProperties.canBeReplaced(78,6));assertFalse(NativePlacementProperties.canBeReplaced(78,7));
        assertTrue(NativePlacementProperties.canBeReplaced(78,8));
        assertTrue(NativePlacementProperties.canBeReplaced(175,2));assertTrue(NativePlacementProperties.canBeReplaced(175,3));
        assertTrue(NativePlacementProperties.canBeReplaced(175,10));assertFalse(NativePlacementProperties.canBeReplaced(175,0));
        assertTrue(NativePlacementProperties.canBeReplaced(32,0));assertFalse(NativePlacementProperties.canBeReplaced(32,1));
        assertTrue(NativePlacementProperties.canBeReplaced(0,8191));assertFalse(NativePlacementProperties.canBeReplaced(2047,0));
        assertThrows(IllegalArgumentException.class,()->NativePlacementProperties.canBeReplaced(1,-1));
        assertThrows(IllegalArgumentException.class,()->NativePlacementProperties.canBeReplaced(1,8192));
        assertThrows(IllegalArgumentException.class,()->NativePlacementProperties.canBeReplaced(10000,0));
    }
}
