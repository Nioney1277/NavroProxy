package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.util.List;

import static ac.navro.proxy.data.block.NetworkBlockComponentsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockTransformationTest {
    private static int readLittleEndian(DataInputStream input) throws java.io.IOException {
        return Integer.reverseBytes(input.readInt());
    }

    @Test void nativeMatricesAndCollisionBoundsMatchForAllRotationsAndScaleModes() throws Exception {
        try (var input = new DataInputStream(getClass().getResourceAsStream("/navro/block-transform-matrices.bin"))) {
            for (int sample = 0; sample < 448; sample++) {
                int mode = readLittleEndian(input);
                int x = readLittleEndian(input), y = readLittleEndian(input), z = readLittleEndian(input);
                var transform = NbtMap.builder().putInt("RX", x).putInt("RY", y).putInt("RZ", z);
                if (mode > 0) {
                    transform.putFloat("TX", .125F).putFloat("TY", -.25F).putFloat("TZ", .375F)
                            .putFloat("SX", .5F).putFloat("SY", 1.25F).putFloat("SZ", 1.5F)
                            .putFloat("RXP", .125F).putFloat("RYP", -.25F).putFloat("RZP", .375F)
                            .putFloat("SXP", -.125F).putFloat("SYP", .25F).putFloat("SZP", -.375F);
                }
                if (mode == 2) transform.putFloat("SX", -.5F).putFloat("SZ", -1.5F);
                if (mode == 3) transform.putFloat("SX", 0F).putFloat("SY", 0F).putFloat("SZ", 0F);
                if (mode == 4) transform.putFloat("SX", .3F).putFloat("SY", .7F).putFloat("SZ", 1.1F);
                var tag = transform.build();
                float[] actual = BlockTransformation.matrix(tag);
                assertNotNull(actual);
                for (int element = 0; element < 16; element++) {
                    float expected = Float.intBitsToFloat(readLittleEndian(input));
                    assertTrue(expected == actual[element], "mode=" + mode + " rotation=" + x + "," + y + "," + z
                            + " element=" + element + " expected=" + expected + " actual=" + actual[element]);
                }
                float[] source = new float[6], expectedBounds = new float[6];
                for (int index = 0; index < 6; index++) source[index] = Float.intBitsToFloat(readLittleEndian(input)) + .5F;
                for (int index = 0; index < 6; index++) expectedBounds[index] = Float.intBitsToFloat(readLittleEndian(input)) + .5F;
                var sourceBox = new Box(source[0], source[1], source[2], source[3], source[4], source[5]);
                var expectedBox = new Box(expectedBounds[0], expectedBounds[1], expectedBounds[2],
                        expectedBounds[3], expectedBounds[4], expectedBounds[5]);
                var boxes = BlockTransformation.apply(tag, List.of(sourceBox));
                equalBoxes(expectedBox.isValid() ? List.of(expectedBox) : List.of(), boxes);
            }
            assertEquals(-1, input.read());
        }
    }

    @Test void scaleAndTranslationApplyAroundTheBlockCenterToEveryFragment() {
        var transform = NbtMap.builder().putFloat("SX", .5F).putFloat("SY", .5F)
                .putFloat("SZ", .5F).putFloat("TX", 2F).putFloat("TY", .5F).build();
        var components = new NetworkBlockComponents(List.of(definition("test:transformed",
                components(collision(box(0, 0, 0, 4, 16, 16), box(12, 0, 0, 16, 8, 16))).toBuilder()
                        .putCompound("minecraft:transformation", transform).build())));
        equalBoxes(List.of(new Box(2.25F, .75F, .25F, 2.375F, 1.25F, .75F),
                        new Box(2.625F, .75F, .25F, 2.75F, 1, .75F)),
                components.collision(observedState(state("test:transformed", NbtMap.EMPTY))));
    }

    @Test void malformedTransformsStayUnknownAndNegativeScaleKeepsOrderedBounds() {
        var box = List.of(new Box(0, 0, 0, .25F, 1, 1));
        equalBoxes(List.of(new Box(.75F, 0, 0, 1, 1, 1)),
                BlockTransformation.apply(NbtMap.builder().putFloat("SX", -1F).build(), box));
        assertEquals(List.of(), BlockTransformation.apply(NbtMap.builder().putFloat("SX", 0F).build(), box));
        for (var tag : List.of(NbtMap.builder().putFloat("RX", .5F).build(),
                NbtMap.builder().putFloat("SX", Float.NaN).build(),
                NbtMap.builder().putString("TX", "1").build(),
                NbtMap.builder().putInt("unknown", 0).build())) {
            assertNull(BlockTransformation.apply(tag, box));
        }
    }
}
