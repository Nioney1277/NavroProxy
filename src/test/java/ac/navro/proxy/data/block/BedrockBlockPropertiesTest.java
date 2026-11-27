package ac.navro.proxy.data.block;

import ac.navro.proxy.util.Hash;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockPropertiesTest {
    private static NetworkBlockState named(int protocol, String name) {
        return NetworkBlockStateRegistry.forProtocol(protocol, false).states().values().stream()
                .filter(s -> s.is(name)).findFirst().orElseThrow();
    }

    @Test void everyRuntimeAndHashedStateResolvesTheSameNativeFacts() {
        var runtime = NetworkBlockStateRegistry.forProtocol(2225, false);
        var hashed = NetworkBlockStateRegistry.forProtocol(2225, true);
        var names = new HashSet<String>();
        var identities = new HashSet<NbtMap>();
        for (var state : runtime.states().values()) {
            var facts = state.bedrockProperties();
            assertNotNull(facts, state.identifier() + state.stateTag().getCompound("states"));
            assertSame(facts, hashed.resolve(Hash.hashBlock(state.stateTag())).bedrockProperties());
            assertTrue(Float.isFinite(facts.friction()));
            names.add(state.identifier());
            identities.add(NbtMap.builder().putString("name",state.identifier())
                    .putCompound("states",state.stateTag().getCompound("states")).build());
        }
        assertEquals(23960, identities.size());
        assertEquals(1480, names.size());
    }

    @Test void nativeFrictionAndMaterialRemainDistinctFromCollisionSupport() {
        var expected = Map.of("minecraft:air",.9F,"minecraft:stone",.6F,
                "minecraft:ice",.98F,"minecraft:blue_ice",.989F,
                "minecraft:honey_block",.8F,"minecraft:slime",.8F);
        expected.forEach((name, friction) -> assertEquals(friction.floatValue(),
                new NavroBlockState(named(2225,name)).getFriction(),name));
        var honey=named(2225,"minecraft:honey_block").bedrockProperties();
        assertEquals(0x2040020000L,honey.propertyMask());
        assertTrue(honey.has(BedrockBlockProperties.PREVENT_JUMPING));
        assertTrue(honey.blocksMotion()); assertTrue(honey.solid());
        assertTrue(named(2225,"minecraft:ice").bedrockProperties().solid());
        assertFalse(named(2225,"minecraft:ladder").bedrockProperties().blocksMotion());
        assertTrue(named(2225,"minecraft:water").bedrockProperties().liquid());
        assertFalse(named(2225,"minecraft:water").bedrockProperties().solid());
    }

    @Test void reorderedPaletteUsesTypedIdentityInsteadOfRuntimeIndexOrVersionTag() {
        var honey=named(2225,"minecraft:honey_block").stateTag().toBuilder().putInt("version",1).build();
        var stone=named(2225,"minecraft:stone").stateTag();
        var wrongType=NbtMap.builder().putString("name","minecraft:water").putCompound("states",
                NbtMap.builder().putByte("liquid_depth",(byte)0).build()).build();
        var start=new StartGamePacket();
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND,honey,stone,wrongType));
        var registry=NetworkBlockStateRegistry.fromStartGame(2225,start);
        assertEquals(.8F,registry.resolve(0).bedrockProperties().friction());
        assertEquals(.6F,registry.resolve(1).bedrockProperties().friction());
        assertNull(registry.resolve(2).bedrockProperties());
    }

    @Test void otherProtocolsAndLegacyStatesKeepTheirExistingFallback() {
        assertNull(named(2193,"minecraft:ice").bedrockProperties());
        assertEquals(.98F,new NavroBlockState(named(2193,"minecraft:ice")).getFriction());
        assertEquals(.6F,new NavroBlockState(named(2193,"minecraft:air")).getFriction());
        assertNull(NetworkBlockState.legacy(BlockStateEncoding.fullId(1,0)).bedrockProperties());
    }

    @Test void declaredComponentsTakePriorityAndDoNotLeakNativePropertiesToCustomStates() {
        var stone=named(2225,"minecraft:stone").stateTag();
        var custom=NbtMap.builder().putString("name","test:custom").putCompound("states",NbtMap.EMPTY).build();
        var start=new StartGamePacket();
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND,stone,custom));
        var friction=NbtMap.builder().putCompound("components",NbtMap.builder()
                .putCompound("minecraft:friction",NbtMap.builder().putFloat("value",.1F).build()).build()).build();
        start.getBlockProperties().add(new BlockPropertyData("minecraft:stone",friction));
        start.getBlockProperties().add(new BlockPropertyData("test:custom",friction));
        var declared=NetworkBlockStateRegistry.fromStartGame(2225,start);
        assertEquals(1F-.1F,new NavroBlockState(declared.resolve(0)).getFriction());
        assertEquals(1F-.1F,new NavroBlockState(declared.resolve(1)).getFriction());
        assertNull(declared.resolve(0).bedrockProperties());
        assertNull(declared.resolve(1).bedrockProperties());
        start.getBlockProperties().clear();
        var plain=NetworkBlockStateRegistry.fromStartGame(2225,start);
        assertEquals(.6F,new NavroBlockState(plain.resolve(0)).getFriction());
        assertNotNull(plain.resolve(0).bedrockProperties());
        assertSame(declared.resolve(0),declared.resolve(0));
    }
}
