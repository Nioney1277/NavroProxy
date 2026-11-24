package ac.navro.proxy.check.impl.place;

import static org.junit.jupiter.api.Assertions.*;

import ac.navro.proxy.data.block.NativeSelectionProperties;
import ac.navro.proxy.data.block.NativeWallVineSelection;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

/** Full production selection component versus native bounding-box and Navro sturdy executions. */
class WallVineSelectionReferenceTest {
    @Test
    void wallAndVineSelectionMatchNavroNativeGettersAcrossMetadataAndNeighbors() throws Exception {
        int walls = 0, vines = 0, invalid = 0;
        try (var in =
                new DataInputStream(
                        new GZIPInputStream(
                                getClass()
                                        .getResourceAsStream(
                                                "/native-wall-vine-selection.bin.gz")))) {
            assertEquals(0x4e575231, in.readInt());
            int rows = in.readInt();
            assertTrue(rows > 0);
            for (int row = 0; row < rows; row++) {
                int id = in.readInt(),
                        meta = in.readInt(),
                        mask = in.readInt(),
                        side = in.readInt();
                int neighborId = in.readInt(),
                        neighborMeta = in.readInt(),
                        aboveId = in.readInt(),
                        aboveMeta = in.readInt();
                int expectedId = in.readInt(), expectedMeta = in.readInt();
                boolean sturdy = in.readBoolean(), present = in.readBoolean();
                double[] expected = new double[present ? 6 : 0];
                for (int axis = 0; axis < expected.length; axis++) expected[axis] = in.readDouble();
                var actual =
                        NativeWallVineSelection.selection(
                                id,
                                meta,
                                17,
                                65,
                                -9,
                                which -> {
                                    if (which == 4)
                                        return new NativeWallVineSelection.State(
                                                aboveId, aboveMeta);
                                    if (which == side)
                                        return new NativeWallVineSelection.State(
                                                neighborId, neighborMeta);
                                    return new NativeWallVineSelection.State(
                                            (mask & (1 << which)) != 0 ? 1 : 0, 0);
                                });
                var facts = NativeSelectionProperties.get(id, meta);
                assertEquals(expectedId, facts.id());
                assertEquals(expectedMeta, facts.damage());
                if (sturdy != actual.sturdy())
                    fail("sturdy id=" + id + " meta=" + meta + " row=" + row);
                if (!present) {
                    assertNull(actual.outline());
                    continue;
                }
                var b = actual.outline();
                float[] bounds = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
                for (int axis = 0; axis < 6; axis++) {
                    if (Float.floatToRawIntBits((float) expected[axis])
                            != Float.floatToRawIntBits(bounds[axis]))
                        fail(
                                "id="
                                        + id
                                        + " meta="
                                        + meta
                                        + " neighbor="
                                        + neighborId
                                        + ":"
                                        + neighborMeta
                                        + " side="
                                        + side
                                        + " axis="
                                        + axis
                                        + " expected="
                                        + expected[axis]
                                        + " actual="
                                        + bounds[axis]);
                }
                if (NativeWallVineSelection.wall(id)) walls++;
                else vines++;
                if (!b.isValid()) invalid++;
            }
            assertEquals(-1, in.read());
        }
        assertTrue(walls > 0);
        assertTrue(vines > 0);
        assertTrue(invalid > 0);
    }

    @Test
    void nonzeroVineMetadataDoesNotReadUnavailableUpperBlockOrNormalizeInvalidBounds() {
        // This deliberately retains fixed-core behavior: metadata bit 4 alone has no side box.
        var unsupportedSide =
                NativeWallVineSelection.selection(
                        106,
                        4,
                        0,
                        0,
                        0,
                        side -> {
                            throw new AssertionError(
                                    "Nonzero native vine metadata must not read above");
                        });
        assertFalse(unsupportedSide.outline().isValid());
        var west =
                NativeWallVineSelection.selection(
                        106,
                        2,
                        0,
                        0,
                        0,
                        side -> {
                            throw new AssertionError("Side-attached vine must not read above");
                        });
        assertEquals(0F, west.outline().minX);
        assertEquals(0.0625F, west.outline().maxX);
        assertEquals(0F, west.outline().minY);
        assertEquals(1F, west.outline().maxY);
    }
}
