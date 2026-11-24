package ac.navro.proxy.data.inventory;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemPlacementPropertiesReferenceTest {
    private static void sample(DataOutputStream out,NativeItemPlacementProperties.Properties value)throws Exception {
        out.writeBoolean(value.canBePlaced());out.writeBoolean(value.hasMappedBlock());out.writeInt(value.blockId());out.writeInt(value.defaultBlockId());out.writeInt(value.defaultLegacyFullId());
    }
    @Test void everyExportedNativeFactoryAndAllSixteenBitDamageInputsMatchTheRealNativeGetters()throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");var numbers=new HashSet<Integer>();var names=new HashSet<String>();
        try(var in=new DataInputStream(getClass().getResourceAsStream("/native-item-placement.bin"));
            var hashed=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            assertEquals(0x4E495231,in.readInt());assertEquals(65536,in.readInt());int count=in.readInt();assertEquals(2950,count);
            for(int i=0;i<count;i++) {
                boolean numeric=in.readBoolean();int id=numeric?in.readInt():0;String name=numeric?null:in.readUTF();
                if(numeric)assertTrue(numbers.add(id));else assertTrue(names.add(name));
                for(int damage=0;damage<=65535;damage++) {
                    var value=numeric?NativeItemPlacementProperties.forNumeric(id,damage):NativeItemPlacementProperties.forIdentifier(name,damage);
                    hashed.writeInt(damage);sample(hashed,value);
                }
                assertArrayEquals(in.readNBytes(32),digest.digest(),"native item factory="+(numeric?id:name));
            }
            assertEquals(-1,in.read());assertEquals(1135,numbers.size());assertEquals(1815,names.size());
            try(var extra=new DataInputStream(getClass().getResourceAsStream("/native-item-placement-extra.bin"))) {
                assertEquals(0x4e495232,extra.readInt());assertEquals(65536,extra.readInt());int added=extra.readInt();assertEquals(399,added);
                for(int i=0;i<added;i++) {
                    boolean numeric=extra.readBoolean();int id=numeric?extra.readInt():0;String name=numeric?null:extra.readUTF();
                    if(numeric)assertTrue(numbers.add(id));else assertTrue(names.add(name));
                    for(int damage=0;damage<=65535;damage++) {
                        var value=numeric?NativeItemPlacementProperties.forNumeric(id,damage):NativeItemPlacementProperties.forIdentifier(name,damage);
                        hashed.writeInt(damage);sample(hashed,value);
                    }
                    assertArrayEquals(extra.readNBytes(32),digest.digest(),"extra native factory="+(numeric?id:name));
                }
                assertEquals(-1,extra.read());
            }
            assertEquals(numbers,NativeItemPlacementProperties.numericIds());assertEquals(names,NativeItemPlacementProperties.identifiers());
        }
    }
    @Test void numericAndIdentifierConstructionRemainDistinctAndPlacementUsesTheOriginalDefaultMetaZero() {
        var dye=NativeItemPlacementProperties.forNumeric(351,3);assertTrue(dye.canBePlaced());assertEquals(127,dye.blockId());
        assertFalse(NativeItemPlacementProperties.forNumeric(351,0).hasMappedBlock());
        assertFalse(NativeItemPlacementProperties.forIdentifier("minecraft:brown_dye",0).hasMappedBlock());
        assertFalse(NativeItemPlacementProperties.forIdentifier("minecraft:ink_sac",3).hasMappedBlock());
        var planks=NativeItemPlacementProperties.forIdentifier("minecraft:spruce_planks",1);
        assertEquals(5,planks.blockId());assertEquals(5<<13,planks.defaultLegacyFullId());
        var seed=NativeItemPlacementProperties.forNumeric(295,0);assertTrue(seed.canBePlaced());assertEquals(59,seed.defaultBlockId());
        assertTrue(NativeItemPlacementProperties.forIdentifier("minecraft:torchflower_seeds",0).canBePlaced());
        var air=NativeItemPlacementProperties.forNumeric(0,0);assertFalse(air.canBePlaced());assertTrue(air.hasMappedBlock());assertEquals(0,air.blockId());
        var bucket=NativeItemPlacementProperties.forNumeric(325,8);assertFalse(bucket.canBePlaced());assertFalse(bucket.hasMappedBlock());
    }
    @Test void missingNativeFactoriesAndUnrepresentedDamageFailExplicitlyInsteadOfInventingMappings() {
        assertThrows(IllegalArgumentException.class,()->NativeItemPlacementProperties.forNumeric(Integer.MAX_VALUE,0));
        assertThrows(IllegalArgumentException.class,()->NativeItemPlacementProperties.forIdentifier("example:unregistered",0));
        assertThrows(IllegalArgumentException.class,()->NativeItemPlacementProperties.forNumeric(1,-1));
        assertThrows(IllegalArgumentException.class,()->NativeItemPlacementProperties.forIdentifier("minecraft:stone",65536));
    }
}
