package ac.navro.proxy.data.block;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;




public final class NativeBlockProperties {
    private static final Table TABLE = load();

    private NativeBlockProperties() { }

    public record Properties(int blockId, double hardness, boolean itemFrame, boolean door, boolean upperDoor) { }

    public static Properties forState(NetworkBlockState state) {
        return forBlock(state.blockId(), state.blockData());
    }


    public static Properties forBlock(int blockId, int metadata) {
        if (metadata < 0 || metadata > BlockStateEncoding.DATA_MASK)
            throw new IllegalArgumentException("Expected native 13-bit block metadata");
        if (blockId >= TABLE.customBlockStart)
            throw new IllegalArgumentException("Custom block properties require the server's registered native implementation");
        if (blockId < 0 || blockId >= TABLE.rows.length)
            return new Properties(blockId, TABLE.unknown.hardness(), false, false, false);
        Row row = TABLE.rows[blockId];
        int index = Arrays.binarySearch(row.ends, metadata);
        return row.values[index >= 0 ? index : -index - 1];
    }


    public static double registryHardness(int blockId) {
        return TABLE.registryHardness[blockId];
    }

    private record Row(int[] ends, Properties[] values) { }
    private record Table(double[] registryHardness, Row[] rows, int customBlockStart, Properties unknown) { }

    private static Properties readProperties(DataInputStream in) throws IOException {
        int id = in.readInt();
        double hardness = Double.longBitsToDouble(in.readLong());
        int flags = in.readUnsignedByte();
        if ((flags & ~7) != 0 || (flags & 4) != 0 && (flags & 2) == 0)
            throw new IOException("Invalid native block flags");
        return new Properties(id, hardness, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
    }

    private static Table load() {
        InputStream resource = NativeBlockProperties.class.getResourceAsStream("/navro/block-properties.bin.gz");
        if (resource == null) throw new IllegalStateException("Missing native block properties");
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            if (in.readInt() != 0x4E425031 || in.readInt() != BlockStateEncoding.DATA_BITS)
                throw new IOException("Incompatible native block properties");
            int count = in.readInt();
            int customBlockStart = in.readInt();
            if (count != 2048 || customBlockStart != 10000)
                throw new IOException("Incompatible native block registry");
            Properties unknown = readProperties(in);
            double[] registryHardness = new double[count];
            Row[] rows = new Row[count];
            for (int id = 0; id < count; id++) {
                registryHardness[id] = Double.longBitsToDouble(in.readLong());
                int size = in.readInt();
                if (size < 1 || size > BlockStateEncoding.DATA_MASK + 1)
                    throw new IOException("Invalid native metadata row length");
                int[] ends = new int[size];
                Properties[] values = new Properties[size];
                for (int i = 0; i < size; i++) {
                    ends[i] = in.readUnsignedShort();
                    if (ends[i] > BlockStateEncoding.DATA_MASK || i > 0 && ends[i] <= ends[i - 1])
                        throw new IOException("Invalid native metadata range");
                    values[i] = readProperties(in);
                }
                if (ends[size - 1] != BlockStateEncoding.DATA_MASK)
                    throw new IOException("Incomplete native metadata coverage");
                rows[id] = new Row(ends, values);
            }
            if (in.read() != -1) throw new IOException("Trailing native block properties");
            return new Table(registryHardness, rows, customBlockStart, unknown);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native block properties", e);
        }
    }
}
