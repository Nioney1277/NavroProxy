package ac.navro.proxy.check.impl.place;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.data.block.NativeSelectionProperties;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

/** Utility contract: lossless decoding/translation of independently exported native bounds. */
class NativeSelectionReferenceTest {
    @Test
    void selectionTableDecodingUtilityPreservesNativeFactsAndUnsortedBounds() throws Exception {
        int known = 0;
        int unknown = 0;
        int invalid = 0;
        try (var in =
                new DataInputStream(
                        new GZIPInputStream(
                                getClass()
                                        .getResourceAsStream(
                                                "/native-selection-properties.bin.gz")))) {
            assertEquals(0x4e535231, in.readInt());
            assertEquals(16384, in.readInt());
            for (int row = 0; row < 16384; row++) {
                int id = in.readInt();
                int metadata = in.readInt();
                boolean expectedKnown = in.readBoolean();
                int expectedId = in.readInt();
                int damage = in.readInt();
                int expectedDamage = damage == -1 ? metadata : damage;
                boolean expectedSlab = in.readBoolean();
                boolean expectedSturdy = in.readBoolean();
                boolean present = in.readBoolean();
                double[] expectedBounds = new double[present ? 6 : 0];
                for (int axis = 0; axis < expectedBounds.length; axis++) {
                    expectedBounds[axis] = in.readDouble();
                }

                var actual = NativeSelectionProperties.get(id, metadata);
                assertEquals(expectedKnown, actual.known(), "id=" + id + " meta=" + metadata);
                assertEquals(expectedId, actual.id());
                assertEquals(expectedDamage, actual.damage());
                assertEquals(expectedSlab, actual.slab());
                assertEquals(expectedSturdy, actual.sturdy());
                assertEquals(present, actual.present());
                assertArrayEquals(expectedBounds, actual.bounds());
                if (!expectedKnown) {
                    unknown++;
                    var error =
                            assertThrows(
                                    IllegalStateException.class, () -> actual.outline(0, 0, 0));
                    assertEquals(
                            "Native selection needs unavailable world state", error.getMessage());
                    continue;
                }
                known++;
                if (!present) {
                    assertNull(actual.outline(1, -64, 7));
                    continue;
                }
                var outline = actual.outline(1, -64, 7);
                float[] result = {
                    outline.minX,
                    outline.minY,
                    outline.minZ,
                    outline.maxX,
                    outline.maxY,
                    outline.maxZ
                };
                int[] translation = {1, -64, 7, 1, -64, 7};
                for (int axis = 0; axis < 6; axis++) {
                    // Translation and double-to-float conversion are the utility contract.
                    assertEquals(
                            Float.floatToRawIntBits(
                                    (float) (expectedBounds[axis] + translation[axis])),
                            Float.floatToRawIntBits(result[axis]),
                            "id=" + id + " axis=" + axis);
                }
                if (expectedBounds[0] >= expectedBounds[3]
                        || expectedBounds[1] >= expectedBounds[4]
                        || expectedBounds[2] >= expectedBounds[5]) {
                    invalid++;
                    assertFalse(outline.isValid(), "Native invalid bounds must not be normalized");
                }
            }
            assertEquals(-1, in.read());
        }
        assertTrue(known > 0);
        assertTrue(unknown > 0);
        assertTrue(invalid > 0, "Fixture must exercise native invalid bounds");
    }
}
