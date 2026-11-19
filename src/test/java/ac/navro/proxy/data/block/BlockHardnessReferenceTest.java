package ac.navro.proxy.data.block;

import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.util.block.BlockUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class BlockHardnessReferenceTest {
    private static final GameType[] MODES = Arrays.copyOf(GameType.values(), GameType.values().length + 1);

    private DataInputStream fixture() throws IOException {
        var in = new DataInputStream(getClass().getResourceAsStream("/navro-block-hardness.bin"));
        assertEquals(0x4E484631, in.readInt());assertEquals(BlockStateEncoding.DATA_BITS, in.readInt());
        assertEquals(MODES.length, in.readInt());assertEquals(2048, in.readInt());return in;
    }
    private static int flags(NativeBlockProperties.Properties state) {
        return (state.itemFrame() ? 1 : 0) | (state.door() ? 2 : 0) | (state.upperDoor() ? 4 : 0);
    }
    private static int accepted(NativeBlockProperties.Properties state) {
        int accepted = 0;
        for (int i = 0; i < MODES.length; i++) if (BlockUtil.determineCanBreak(MODES[i], state)) accepted |= 1 << i;
        return accepted;
    }
    private static void sample(DataOutputStream out, NativeBlockProperties.Properties state) throws IOException {
        out.writeInt(state.blockId());out.writeLong(Double.doubleToRawLongBits(state.hardness()));
        out.writeByte(flags(state));out.writeByte(accepted(state));
    }
    private static void sample(DataInputStream in, NativeBlockProperties.Properties state) throws IOException {
        assertEquals(in.readInt(),state.blockId());assertEquals(in.readLong(),Double.doubleToRawLongBits(state.hardness()));
        assertEquals(in.readUnsignedByte(),flags(state));assertEquals(in.readUnsignedByte(),accepted(state));
    }
    @Test void everyNativeIdAndMetadataAndAllGameModesMatchOriginalNavro() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = fixture();var hashed = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            for (int id = 0; id < 2048; id++) {
                assertEquals(in.readLong(),Double.doubleToRawLongBits(NativeBlockProperties.registryHardness(id)),"static id="+id);
                for (int data = 0; data <= BlockStateEncoding.DATA_MASK; data++) {
                    hashed.writeInt(data);sample(hashed,NativeBlockProperties.forBlock(id,data));
                }
                assertArrayEquals(in.readNBytes(32),digest.digest(),"native state properties / breakability id="+id);
            }
            int count = in.readInt();assertEquals(30,count);
            for (int i=0;i<count;i++) sample(in,NativeBlockProperties.forBlock(in.readInt(),in.readInt()));
            count=in.readInt();assertEquals(10,count);
            for (int i=0;i<count;i++) {
                double hardness = in.readDouble();
                sample(in,new NativeBlockProperties.Properties(1,hardness,false,false,false));
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void nativeMetadataFallbackIsPreservedInsteadOfUsingTheStaticHardnessArray() {
        assertEquals(0.6,NativeBlockProperties.registryHardness(2));
        assertEquals(0.6,NativeBlockProperties.forBlock(2,0).hardness());
        assertEquals(10,NativeBlockProperties.forBlock(2,1).hardness());
        assertEquals(-1,NativeBlockProperties.forBlock(90,0).hardness());
        assertEquals(10,NativeBlockProperties.forBlock(90,1).hardness());
        assertFalse(BlockUtil.determineCanBreak(GameType.SURVIVAL,NativeBlockProperties.forBlock(90,0)));
        assertTrue(BlockUtil.determineCanBreak(GameType.SURVIVAL,NativeBlockProperties.forBlock(90,1)));
        assertEquals(0,NativeBlockProperties.registryHardness(2047));
        assertEquals(10,NativeBlockProperties.forBlock(2047,0).hardness());
        var wrongName = new NetworkBlockState(1,BlockStateEncoding.fullId(7,0),NbtMap.builder().putString("name","minecraft:stone").build());
        assertEquals(-1,NativeBlockProperties.forState(wrongName).hardness());
        assertFalse(BlockUtil.determineCanBreak(GameType.SURVIVAL,wrongName));
        assertTrue(BlockUtil.determineCanBreak(GameType.CREATIVE,wrongName));
    }
    @Test void nativeAirAndLiquidExclusionsApplyEvenInCreativeAndPreserveFloatNarrowing() {
        for (int id:new int[]{0,8,9,10,11}) for(GameType mode:MODES)
            assertFalse(BlockUtil.determineCanBreak(mode,NativeBlockProperties.forBlock(id,0)));
        for(GameType mode:MODES) {
            boolean creative=mode==GameType.CREATIVE;
            assertEquals(creative,BlockUtil.determineCanBreak(mode,new NativeBlockProperties.Properties(1,Math.nextUp(-1D),false,false,false)));
            assertEquals(creative,BlockUtil.determineCanBreak(mode,new NativeBlockProperties.Properties(1,Math.nextDown(-1D),false,false,false)));
            assertTrue(BlockUtil.determineCanBreak(mode,new NativeBlockProperties.Properties(1,Double.NaN,false,false,false)));
        }
    }
    @Test void nativeModeOrdinalsAndUnsupportedInputsAreExplicit() {
        String[] names={"SURVIVAL","CREATIVE","ADVENTURE","SURVIVAL_VIEWER","CREATIVE_VIEWER","DEFAULT","SPECTATOR"};
        for (int i=0;i<names.length;i++)assertEquals(names[i],GameType.from(i).name());
        assertThrows(ArrayIndexOutOfBoundsException.class,()->GameType.from(-1));
        assertThrows(ArrayIndexOutOfBoundsException.class,()->GameType.from(7));
        assertThrows(ArrayIndexOutOfBoundsException.class,()->NativeBlockProperties.registryHardness(-1));
        assertThrows(ArrayIndexOutOfBoundsException.class,()->NativeBlockProperties.registryHardness(2048));
        assertThrows(IllegalArgumentException.class,()->NativeBlockProperties.forBlock(1,-1));
        assertThrows(IllegalArgumentException.class,()->NativeBlockProperties.forBlock(1,8192));
        assertThrows(IllegalArgumentException.class,()->NativeBlockProperties.forBlock(10000,0));
    }
}
