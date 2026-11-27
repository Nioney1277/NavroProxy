package ac.navro.proxy.data.block;

import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.util.block.BlockUtil;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BedrockHardnessTest {
    private static NetworkBlockState state(int protocol, String name) {
        return NetworkBlockStateRegistry.forProtocol(protocol,false).states().values().stream()
                .filter(s -> s.is(name)).findFirst().orElseThrow();
    }

    @Test void everyStateHasFiniteNativeHardnessAndKnownBlocksMatchBds() {
        var registry=NetworkBlockStateRegistry.forProtocol(2225,false);
        for (var state:registry.states().values()) {
            assertNotNull(state.bedrockProperties());
            assertTrue(Float.isFinite(state.bedrockProperties().hardness()));
            assertTrue(state.bedrockProperties().hardness()>=-1);
        }
        Map.of("minecraft:stone",1.5F,"minecraft:obsidian",35F,
                "minecraft:bedrock",-1F,"minecraft:dirt",.5F,"minecraft:air",-1F)
                .forEach((name,hardness)->assertEquals(hardness.floatValue(),state(2225,name).bedrockProperties().hardness(),name));
    }

    @Test void physicalHardnessWinsWithoutReplacingBackendIdentityFacts() {
        var stone=state(2225,"minecraft:stone");
        var nativeFacts=new NativeBlockProperties.Properties(7,-1,true,true,true);
        var view=new BlockBreakState(stone,nativeFacts);
        assertEquals(1.5,view.hardness());
        assertSame(nativeFacts,view.nativeState());
        assertTrue(BlockUtil.determineCanBreak(GameType.SURVIVAL,view));
        var unmapped=new BlockBreakState(stone,new NativeBlockProperties.Properties(0,0,false,false,false));
        assertFalse(unmapped.isAir());
        assertTrue(BlockUtil.determineCanBreak(GameType.SURVIVAL,unmapped));
    }

    @Test void bedrockUsesCreativeExceptionButAirAndLiquidsNeverBecomeBreakable() {
        var bedrock=state(2225,"minecraft:bedrock");
        assertFalse(BlockUtil.determineCanBreak(GameType.SURVIVAL,bedrock));
        assertFalse(BlockUtil.determineCanBreak(GameType.CREATIVE_VIEWER,bedrock));
        assertTrue(BlockUtil.determineCanBreak(GameType.CREATIVE,bedrock));
        for (String name:new String[]{"minecraft:air","minecraft:water","minecraft:flowing_water","minecraft:lava"})
            for (var mode:GameType.values())assertFalse(BlockUtil.determineCanBreak(mode,state(2225,name)),name+mode);
    }

    @Test void olderProtocolsAndStatesWithoutNativeExportKeepTheirHardness() {
        var old=state(2193,"minecraft:stone");
        var backend=new NativeBlockProperties.Properties(1,6.25,false,false,false);
        var view=new BlockBreakState(old,backend);
        assertNull(old.bedrockProperties());assertEquals(6.25,view.hardness());
        assertTrue(BlockUtil.determineCanBreak(GameType.SURVIVAL,view));
        assertEquals(NativeBlockProperties.forState(old).hardness(),
                new BlockBreakState(old,NativeBlockProperties.forState(old)).hardness());
    }
}
