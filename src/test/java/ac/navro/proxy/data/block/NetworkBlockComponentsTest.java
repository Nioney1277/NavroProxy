package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.util.Hash;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkBlockComponentsTest {
    static NbtMap box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return NbtMap.builder().putFloat("minX", minX).putFloat("minY", minY).putFloat("minZ", minZ)
                .putFloat("maxX", maxX).putFloat("maxY", maxY).putFloat("maxZ", maxZ).build();
    }

    static NbtMap collision(NbtMap... boxes) {
        return NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND, List.of(boxes)).build();
    }

    static NbtMap components(NbtMap collision) {
        return NbtMap.builder().putCompound("minecraft:collision_box", collision).build();
    }

    static BlockPropertyData definition(String name, NbtMap components, NbtMap... permutations) {
        return new BlockPropertyData(name, NbtMap.builder().putCompound("components", components)
                .putList("permutations", NbtType.COMPOUND, List.of(permutations)).build());
    }

    static NbtMap state(String name, NbtMap properties) {
        return NbtMap.builder().putString("name", name).putCompound("states", properties).build();
    }

    static NetworkBlockState observedState(NbtMap tag) {
        return new NetworkBlockState(Hash.hashBlock(tag), -1, tag);
    }

    static void equalBoxes(List<Box> expected, List<Box> actual) {
        assertNotNull(actual);
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) assertTrue(expected.get(index).equals(actual.get(index)),
                "expected=" + expected.get(index) + " actual=" + actual.get(index));
    }

    @Test void multiBoxesRetainEveryFragmentAndConvertNetworkUnitsExactly() {
        var registry = new NetworkBlockComponents(List.of(definition("test:shape",
                components(collision(box(0, 0, 0, 4, 16, 16), box(12, 0, 0, 16, 8, 16))))));
        equalBoxes(List.of(new Box(0, 0, 0, .25F, 1, 1), new Box(.75F, 0, 0, 1, .5F, 1)),
                registry.collision(observedState(state("test:shape", NbtMap.EMPTY))));
    }

    @Test void oldOriginSizeAndModernMinMaxRepresentTheSamePhysicalBox() {
        var old = NbtMap.builder().putBoolean("enabled", true)
                .putList("origin", NbtType.FLOAT, -6F, 2F, -4F).putList("size", NbtType.FLOAT, 12F, 8F, 8F).build();
        var modern = collision(box(2, 2, 4, 14, 10, 12));
        var registry = new NetworkBlockComponents(List.of(definition("test:old", components(old)), definition("test:new", components(modern))));
        var expected = List.of(new Box(.125F, .125F, .25F, .875F, .625F, .75F));
        equalBoxes(expected, registry.collision(observedState(state("test:old", NbtMap.EMPTY))));
        equalBoxes(expected, registry.collision(observedState(state("test:new", NbtMap.EMPTY))));
    }

    @Test void disabledCollisionIsKnownEmptyWhileMissingOrMalformedGeometryStaysUnknown() {
        var registry = new NetworkBlockComponents(List.of(
                definition("test:disabled", components(NbtMap.builder().putBoolean("enabled", false).build())),
                definition("test:missing", NbtMap.EMPTY),
                definition("test:malformed", components(collision(box(4, 0, 0, 2, 16, 16)))),
                definition("test:nonfinite", components(collision(box(0, Float.NaN, 0, 16, 16, 16))))));
        assertEquals(List.of(), registry.collision(observedState(state("test:disabled", NbtMap.EMPTY))));
        for (String name : List.of("test:missing", "test:malformed", "test:nonfinite")) {
            assertNull(registry.collision(observedState(state(name, NbtMap.EMPTY))), name);
        }
    }

    @Test void orderedMatchingPermutationsReplaceTheWholeCollisionComponent() {
        var half = components(collision(box(0, 0, 0, 16, 8, 16)));
        var disabled = components(NbtMap.builder().putBoolean("enabled", false).build());
        var first = NbtMap.builder().putString("condition", "q.block_state('test:height') >= 1")
                .putCompound("components", half).build();
        var last = NbtMap.builder().putString("condition", "query.block_state('test:height') == 2 && !q.block_state('test:solid')")
                .putCompound("components", disabled).build();
        var registry = new NetworkBlockComponents(List.of(definition("test:variant", components(collision(box(0, 0, 0, 16, 16, 16))), first, last)));
        for (int value = 0; value < 3; value++) {
            var shape = registry.collision(observedState(state("test:variant", NbtMap.builder()
                    .putInt("test:height", value).putByte("test:solid", (byte) 0).build())));
            if (value == 2) assertTrue(shape.isEmpty());
            else equalBoxes(List.of(new Box(0, 0, 0, 1, value == 0 ? 1 : .5F, 1)), shape);
        }
    }

    @Test void unsupportedRelevantConditionsAndTransformsCannotFallBackToTheBaseShape() {
        var unsupported = NbtMap.builder().putString("condition", "q.is_sneaking")
                .putCompound("components", components(collision(box(0, 0, 0, 16, 8, 16)))).build();
        var transformed = components(collision(box(0, 0, 0, 16, 16, 16))).toBuilder()
                .putCompound("minecraft:transformation", NbtMap.builder().putFloat("RY", .5F).build()).build();
        var registry = new NetworkBlockComponents(List.of(
                definition("test:dynamic", components(collision(box(0, 0, 0, 16, 16, 16))), unsupported),
                definition("test:transformed", transformed)));
        assertNull(registry.collision(observedState(state("test:dynamic", NbtMap.EMPTY))));
        assertNull(registry.collision(observedState(state("test:transformed", NbtMap.EMPTY))));
    }

    @Test void irrelevantUnknownConditionsDoNotInvalidateIndependentCollisionDeclarations() {
        var visual = NbtMap.builder().putString("condition", "q.is_sneaking")
                .putCompound("components", NbtMap.builder().putString("minecraft:display_name", "visual").build()).build();
        var registry = new NetworkBlockComponents(List.of(definition("test:visual", components(collision(box(0, 0, 0, 16, 8, 16))), visual)));
        equalBoxes(List.of(new Box(0, 0, 0, 1, .5F, 1)), registry.collision(observedState(state("test:visual", NbtMap.EMPTY))));
    }

    @Test void supportedStaticConditionExpressionsUseOnlyExactStateValues() {
        var states = NbtMap.builder().putInt("test:level", 2).putByte("test:solid", (byte) 1).putString("test:kind", "north").build();
        for (String expression : List.of("q.block_state('test:level') == 2", "(q.block_state('test:level') < 3 && q.block_state('test:solid')) || 0",
                "q.block_property('test:kind') == 'north'", "!false && true", "query.block_state('test:level') >= 2e0",
                "q.block_state('test:level') * 3 + 1 == 7", "8 / -q.block_state('test:level') == -4",
                "1 + 2 * 3 == 7", "(1 + 2) * 3 == 9", "8 / 2 * 2 == 8",
                "-(q.block_state('test:level') + 1) == -3")) {
            assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, states), expression);
        }
        assertEquals(Boolean.FALSE, StateCondition.evaluate("q.block_state('test:level') != 2", states));
        for (String expression : List.of("q.block_state('absent') == 0", "q.is_sneaking", "math.random(0, 1)", "true junk",
                "(".repeat(128) + "1" + ")".repeat(128))) assertNull(StateCondition.evaluate(expression, states), expression);
    }

    @Test void hashedDefinitionsExpandTypedStatesWithoutGuessingNumericRuntimeOrder() {
        var properties = NbtMap.builder().putCompound("components", components(collision(box(0, 0, 0, 16, 8, 16))))
                .putList("properties", NbtType.COMPOUND, List.of(
                        NbtMap.builder().putString("name", "test:solid").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build(),
                        NbtMap.builder().putString("name", "test:kind").putList("enum", NbtType.STRING, "north", "south").build())).build();
        var start = new StartGamePacket();
        start.setBlockNetworkIdsHashed(true);
        start.getBlockProperties().add(new BlockPropertyData("test:hashed", properties));
        var registry = NetworkBlockStateRegistry.fromStartGame(2193, start);
        for (byte solid : new byte[]{0, 1}) for (String kind : List.of("north", "south")) {
            var state = state("test:hashed", NbtMap.builder().putByte("test:solid", solid).putString("test:kind", kind).build());
            var observed = registry.resolve(Hash.hashBlock(state));
            assertNotNull(observed);
            assertEquals(state, observed.stateTag());
            equalBoxes(List.of(new Box(0, 0, 0, 1, .5F, 1)), registry.declaredCollision(observed));
        }
        start.setBlockNetworkIdsHashed(false);
        assertThrows(IllegalArgumentException.class, () -> NetworkBlockStateRegistry.fromStartGame(2193, start));
    }

    @Test void explicitRuntimePaletteKeepsItsActualOrderAndDeclarationsRemainConnectionScoped() {
        var state = state("test:scoped", NbtMap.EMPTY);
        var air = state("minecraft:air", NbtMap.EMPTY);
        var start = new StartGamePacket();
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND, state, air));
        start.getBlockProperties().add(definition("test:scoped", components(collision(box(0, 0, 0, 16, 8, 16)))));
        var registry = NetworkBlockStateRegistry.fromStartGame(2193, start);
        equalBoxes(List.of(new Box(0, 0, 0, 1, .5F, 1)), registry.declaredCollision(registry.resolve(0)));
        start.getBlockProperties().clear();
        var other = NetworkBlockStateRegistry.fromStartGame(2193, start);
        assertFalse(other.hasComponents(other.resolve(0)));
        assertTrue(registry.hasComponents(registry.resolve(0)));
        assertEquals("minecraft:air", registry.resolve(1).identifier());
    }

    @Test void receivedFrictionAndItsStaticPermutationsDriveTheMovementView() {
        var base = components(collision(box(0, 0, 0, 16, 16, 16))).toBuilder()
                .putCompound("minecraft:friction", NbtMap.builder().putFloat("value", .02F).build()).build();
        var condition = NbtMap.builder().putString("condition", "q.block_state('test:sticky') == 1")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:friction", NbtMap.builder().putFloat("value", .4F).build()).build()).build();
        var start = new StartGamePacket();
        start.setBlockNetworkIdsHashed(true);
        var definition = definition("test:friction", base, condition);
        start.getBlockProperties().add(new BlockPropertyData(definition.getName(), definition.getProperties().toBuilder()
                .putList("properties", NbtType.COMPOUND, List.of(NbtMap.builder().putString("name", "test:sticky")
                        .putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build())).build()));
        var registry = NetworkBlockStateRegistry.fromStartGame(2193, start);
        for (byte sticky : new byte[]{0, 1}) {
            var tag = state("test:friction", NbtMap.builder().putByte("test:sticky", sticky).build());
            var observed = registry.resolve(Hash.hashBlock(tag));
            var movement = new NavroBlockState(observed);
            assertFalse(movement.isAir());
            assertEquals(sticky == 0 ? .98F : .6F, movement.getFriction());
            assertSame(observed, registry.resolve(Hash.hashBlock(tag)));
        }
    }

    @Test void explicitVanillaStatesRetainTheirKnownNativeIdentityWithoutUsingTheTransmittedRuntimeId() {
        var source = NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(value -> value.is("minecraft:blue_ice") && value.legacyFullId() >= 0).findFirst().orElseThrow();
        var start = new StartGamePacket();
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND, source.stateTag(), state("minecraft:air", NbtMap.EMPTY)));
        var registry = NetworkBlockStateRegistry.fromStartGame(2193, start);
        var mapped = registry.resolve(0);
        assertEquals(source.legacyFullId(), mapped.legacyFullId());
        assertEquals(.989F, new NavroBlockState(mapped).getFriction());
        assertNotEquals(source.networkId(), mapped.networkId());
    }

    @Test void collisionHeightLimitsFollowTheNegotiatedProtocolAndInvalidBoundsStayUnknown() {
        var definitions = List.of(definition("test:tall", components(collision(box(0, 0, 0, 16, 24, 16)))));
        var state = observedState(state("test:tall", NbtMap.EMPTY));
        assertNull(new NetworkBlockComponents(definitions, 776).collision(state));
        equalBoxes(List.of(new Box(0, 0, 0, 1, 1.5F, 1)), new NetworkBlockComponents(definitions, 898).collision(state));
        var invalid = new NetworkBlockComponents(List.of(definition("test:invalid", components(collision(box(-1, 0, 0, 16, 16, 16))))));
        assertNull(invalid.collision(observedState(state("test:invalid", NbtMap.EMPTY))));
    }
    @Test void laterCertainOverridesResolveOnlyTheComponentsTheyReplace() {
        var full = components(collision(box(0, 0, 0, 16, 16, 16))).toBuilder()
                .putCompound("minecraft:friction", NbtMap.builder().putFloat("value", .4F).build()).build();
        var uncertain = NbtMap.builder().putString("condition", "q.is_sneaking")
                .putCompound("components", full).build();
        var half = components(collision(box(0, 0, 0, 16, 8, 16)));
        var restoreCollision = NbtMap.builder().putString("condition", "q.block_state('test:fixed') == 1")
                .putCompound("components", half).build();
        var restoreFriction = NbtMap.builder().putString("condition", "true")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:friction",
                        NbtMap.builder().putFloat("value", .02F).build()).build()).build();
        var registry = new NetworkBlockComponents(List.of(
                definition("test:collision", full, uncertain, restoreCollision),
                definition("test:both", full, uncertain, restoreCollision, restoreFriction),
                definition("test:uncertainLast", full, restoreCollision, restoreFriction, uncertain)));
        var properties = NbtMap.builder().putByte("test:fixed", (byte) 1).build();
        var collisionOnly = observedState(state("test:collision", properties));
        equalBoxes(List.of(new Box(0, 0, 0, 1, .5F, 1)), registry.collision(collisionOnly));
        assertNull(registry.slipperiness(collisionOnly));
        var both = observedState(state("test:both", properties));
        equalBoxes(List.of(new Box(0, 0, 0, 1, .5F, 1)), registry.collision(both));
        assertEquals(.98F, registry.slipperiness(both));
        var unresolved = observedState(state("test:uncertainLast", properties));
        assertNull(registry.collision(unresolved));
        assertNull(registry.slipperiness(unresolved));
        assertNull(registry.collision(observedState(state("test:both",
                NbtMap.builder().putByte("test:fixed", (byte) 0).build()))));
    }

    @Test void restoringCollisionDoesNotResolveAnUncertainTransformation() {
        var full = components(collision(box(0, 0, 0, 16, 16, 16)));
        var uncertain = NbtMap.builder().putString("condition", "q.is_sneaking")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:transformation",
                        NbtMap.builder().putInt("RY", 1).build()).build()).build();
        var restoreCollision = NbtMap.builder().putString("condition", "true")
                .putCompound("components", full).build();
        var restoreTransform = NbtMap.builder().putString("condition", "true")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:transformation",
                        NbtMap.builder().putInt("RY", 0).build()).build()).build();
        var registry = new NetworkBlockComponents(List.of(
                definition("test:uncertain", full, uncertain, restoreCollision),
                definition("test:restored", full, uncertain, restoreCollision, restoreTransform)));
        assertNull(registry.collision(observedState(state("test:uncertain", NbtMap.EMPTY))));
        equalBoxes(List.of(new Box(0, 0, 0, 1, 1, 1)),
                registry.collision(observedState(state("test:restored", NbtMap.EMPTY))));
    }

    @Test void knownEmptyCollisionDoesNotRequireResolvingTransformation() {
        var uncertainTransform = NbtMap.builder().putString("condition", "q.is_sneaking")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:transformation",
                        NbtMap.builder().putInt("RY", 1).build()).build()).build();
        var registry = new NetworkBlockComponents(List.of(
                definition("test:disabled", components(NbtMap.builder().putBoolean("enabled", false).build()), uncertainTransform),
                definition("test:zeroVolume", components(collision(box(0, 0, 0, 16, 0, 16))), uncertainTransform)));
        assertEquals(List.of(), registry.collision(observedState(state("test:disabled", NbtMap.EMPTY))));
        assertEquals(List.of(), registry.collision(observedState(state("test:zeroVolume", NbtMap.EMPTY))));
    }

    @Test void conditionVersionUsesTheDeclaredRulesAndNegotiatedEnumNumbering() {
        var states = NbtMap.builder().putInt("test:level", 2).build();
        assertEquals(Boolean.FALSE, StateCondition.evaluate("1 || 0 && 0", states, 1, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("1 || 0 && 0", states, 9, 2193));
        assertEquals(Boolean.FALSE, StateCondition.evaluate("1 || 0 && 0", states, 6, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("1 || 0 && 0", states, 6, 2225));
        assertNull(StateCondition.evaluate("1 || 0 && 0", states, null, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("(1 || 0) && 1", states, null, 2193));
        String negative = "8 / -q.block_state('test:level') == -4";
        assertNull(StateCondition.evaluate(negative, states, 7, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate(negative, states, 8, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate(negative, states, 7, 2225));
        for (String expression : List.of("1 / 0", "3e38 * 2", "'north' + 1", "1 < 2 == 1")) {
            assertNull(StateCondition.evaluate(expression, states));
        }
    }

    @Test void declaredMolangVersionSelectsTheCorrectPermutationThroughTheRegistry() {
        var variant = NbtMap.builder().putString("condition", "1 || 0 && 0").putCompound("components",
                components(collision(box(0, 0, 0, 16, 8, 16)))).build();
        var base = definition("test:versioned", components(collision(box(0, 0, 0, 16, 16, 16))), variant);
        var tag = state("test:versioned", NbtMap.EMPTY);
        for (int version : new int[]{1, 9}) {
            var start = new StartGamePacket();
            start.setBlockNetworkIdsHashed(true);
            start.getBlockProperties().add(new BlockPropertyData(base.getName(), base.getProperties().toBuilder()
                    .putInt("molangVersion", version).build()));
            var registry = NetworkBlockStateRegistry.fromStartGame(2193, start);
            equalBoxes(List.of(new Box(0, 0, 0, 1, version == 1 ? 1 : .5F, 1)),
                    registry.declaredCollision(registry.resolve(Hash.hashBlock(tag))));
        }
    }

}
