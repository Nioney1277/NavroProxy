package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;

import java.io.*;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;


public final class NativeSelectionProperties {
    public record Facts(
            boolean known,
            int id,
            int damage,
            boolean slab,
            boolean sturdy,
            boolean present,
            double[] bounds) {
        public Facts {
            bounds = bounds.clone();
        }

        @Override
        public double[] bounds() {
            return bounds.clone();
        }

        public Box outline(int x, int y, int z) {
            if (!known)
                throw new IllegalStateException("Native selection needs unavailable world state");
            return present
                    ? Box.fromNativeBounds(
                            x + bounds[0],
                            y + bounds[1],
                            z + bounds[2],
                            x + bounds[3],
                            y + bounds[4],
                            z + bounds[5])
                    : null;
        }
    }

    private record Row(int[] ends, Facts[] values) {}

    private static final int BLOCK_IDS = 2048;
    private static final int METADATA_VALUES = 8192;
    private static final Row[] TABLE = load();

    private NativeSelectionProperties() {}

    public static Facts get(int id, int damage) {
        if (id < 0 || id >= TABLE.length || damage < 0 || damage >= METADATA_VALUES)
            return new Facts(false, id, damage, false, false, false, new double[0]);
        var row = TABLE[id];
        int i = Arrays.binarySearch(row.ends, damage);
        var value = row.values[i >= 0 ? i : -i - 1];
        return value.damage() == -1
                ? new Facts(
                        value.known(),
                        value.id(),
                        damage,
                        value.slab(),
                        value.sturdy(),
                        value.present(),
                        value.bounds())
                : value;
    }

    private static Row[] load() {
        var resource =
                NativeSelectionProperties.class.getResourceAsStream(
                        "/navro/selection-properties.bin.gz");
        if (resource == null)
            throw new IllegalStateException("Missing native selection properties");
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            if (in.readInt() != 0x4e535031
                    || in.readInt() != BLOCK_IDS
                    || in.readInt() != METADATA_VALUES)
                throw new IOException("Invalid native selection header");
            var rows = new Row[BLOCK_IDS];
            for (int id = 0; id < rows.length; id++) {
                int size = in.readInt();
                if (size < 1 || size > METADATA_VALUES)
                    throw new IOException("Invalid selection ranges");
                int[] ends = new int[size];
                var values = new Facts[size];
                for (int i = 0; i < size; i++) {
                    ends[i] = in.readUnsignedShort();
                    if (ends[i] >= METADATA_VALUES || i > 0 && ends[i] <= ends[i - 1])
                        throw new IOException("Invalid selection range");
                    boolean known = in.readBoolean();
                    int nativeId = in.readInt(), damage = in.readInt();
                    boolean slab = in.readBoolean(),
                            sturdy = in.readBoolean(),
                            present = in.readBoolean();
                    var bounds = new double[present ? 6 : 0];
                    for (int j = 0; j < bounds.length; j++) bounds[j] = in.readDouble();
                    values[i] = new Facts(known, nativeId, damage, slab, sturdy, present, bounds);
                }
                if (ends[size - 1] != METADATA_VALUES - 1)
                    throw new IOException("Incomplete selection metadata coverage");
                rows[id] = new Row(ends, values);
            }
            if (in.read() != -1) throw new IOException("Trailing selection data");
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Invalid native selection properties", e);
        }
    }
}
