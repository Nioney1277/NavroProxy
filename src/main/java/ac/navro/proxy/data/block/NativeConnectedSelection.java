package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;


public final class NativeConnectedSelection {
    private static final int BLOCK_IDS = 2048;
    private static final int METADATA_VALUES = 8192;
    private static final int FENCE = 1;
    private static final int NETHER_FENCE = 2;
    private static final int THIN = 3;
    private static final Table TABLE = load();

    public static final class UnknownProperty extends RuntimeException {
        UnknownProperty(String property) {
            super(property);
        }
    }


    public record Neighbor(int id, int flags) {
        private boolean fence() {
            return (flags & 1) != 0;
        }

        private boolean netherFence() {
            return (flags & 2) != 0;
        }

        private boolean gate() {
            return (flags & 4) != 0;
        }

        private boolean wall() {
            return (flags & 8) != 0;
        }

        public int getId() {
            return id;
        }

        public boolean isSolid() {
            if ((flags & 16) == 0) {
                throw new UnknownProperty("native_neighbor_solidity_requires_world_state");
            }
            return (flags & 32) != 0;
        }

        public boolean isTransparent() {
            if ((flags & 64) == 0) {
                throw new UnknownProperty("native_neighbor_transparency_requires_world_state");
            }
            return (flags & 128) != 0;
        }
    }

    public record Selection(Box outline, boolean sturdy) {}

    private record NeighborRow(int[] ends, Neighbor[] values) {}

    private record SturdyRow(int[] ends, int[] masks) {}

    private record Table(NeighborRow[] neighbors, int[] kinds, SturdyRow[] sturdy) {}

    private NativeConnectedSelection() {}

    public static boolean supported(int id) {
        return id >= 0 && id < TABLE.kinds.length && TABLE.kinds[id] != 0;
    }

    public static Neighbor neighbor(int id, int metadata) {
        if (id < 0 || id >= BLOCK_IDS || metadata < 0 || metadata >= METADATA_VALUES) {
            throw new UnknownProperty("unknown_native_connection_neighbor");
        }
        NeighborRow row = TABLE.neighbors[id];
        return row.values[rangeIndex(row.ends, metadata)];
    }


    public static Selection selection(
            int id, int metadata, int x, int y, int z, IntFunction<Neighbor> neighbors) {
        if (!supported(id) || metadata < 0 || metadata >= METADATA_VALUES) {
            throw new IllegalArgumentException("Unsupported native connected selection");
        }

        int kind = TABLE.kinds[id];
        int nativeId = NativeSelectionProperties.get(id, metadata).id();
        boolean north = connects(kind, nativeId, neighbors.apply(0));
        boolean south = connects(kind, nativeId, neighbors.apply(1));
        boolean west = connects(kind, nativeId, neighbors.apply(2));
        boolean east = connects(kind, nativeId, neighbors.apply(3));
        int mask = (north ? 1 : 0) | (south ? 2 : 0) | (west ? 4 : 0) | (east ? 8 : 0);

        Box outline =
                kind == THIN
                        ? thinBounds(x, y, z, north, south, west, east)
                        : fenceBounds(x, y, z, north, south, west, east);
        SturdyRow row = TABLE.sturdy[id];
        boolean sturdy = (row.masks[rangeIndex(row.ends, metadata)] & (1 << mask)) != 0;
        return new Selection(outline, sturdy);
    }

    private static boolean connects(int kind, int ownId, Neighbor neighbor) {


        if (kind == FENCE) {
            return neighbor.fence()
                    || neighbor.gate()
                    || neighbor.isSolid() && !neighbor.isTransparent();
        }
        if (kind == NETHER_FENCE) {
            return neighbor.netherFence()
                    || neighbor.gate()
                    || neighbor.isSolid() && !neighbor.isTransparent();
        }
        return neighbor.wall()
                || neighbor.isSolid()
                || neighbor.id() == ownId
                || neighbor.id() == 102
                || neighbor.id() == 20;
    }

    private static Box fenceBounds(
            int x, int y, int z, boolean north, boolean south, boolean west, boolean east) {
        double minZ = north ? 0 : 0.375;
        double maxZ = south ? 1 : 0.625;
        double minX = west ? 0 : 0.375;
        double maxX = east ? 1 : 0.625;
        return Box.fromNativeBounds(x + minX, y, z + minZ, x + maxX, y + 1.5, z + maxZ);
    }

    private static Box thinBounds(
            int x, int y, int z, boolean north, boolean south, boolean west, boolean east) {
        double minX = 0.4375;
        double maxX = 0.5625;
        double minZ = 0.4375;
        double maxZ = 0.5625;
        if (west && east) {
            minX = 0;
            maxX = 1;
        } else if (west) {
            minX = 0;
        } else if (east) {
            maxX = 1;
        }
        if (north && south) {
            minZ = 0;
            maxZ = 1;
        } else if (north) {
            minZ = 0;
        } else if (south) {
            maxZ = 1;
        }
        return Box.fromNativeBounds(x + minX, y, z + minZ, x + maxX, y + 1, z + maxZ);
    }

    private static int rangeIndex(int[] ends, int metadata) {
        int index = Arrays.binarySearch(ends, metadata);
        return index >= 0 ? index : -index - 1;
    }

    private static int[] readRangeEnds(DataInputStream in, int size) throws IOException {
        if (size < 1 || size > METADATA_VALUES) {
            throw new IOException("Invalid connected selection range count");
        }
        return new int[size];
    }

    private static void readRangeEnd(DataInputStream in, int[] ends, int index) throws IOException {
        int end = in.readUnsignedShort();
        if (end >= METADATA_VALUES || index > 0 && end <= ends[index - 1]) {
            throw new IOException("Invalid connected selection range");
        }
        ends[index] = end;
    }

    private static void requireComplete(int[] ends) throws IOException {
        if (ends[ends.length - 1] != METADATA_VALUES - 1) {
            throw new IOException("Incomplete connected metadata");
        }
    }

    private static Table load() {
        var stream =
                NativeConnectedSelection.class.getResourceAsStream(
                        "/navro/connected-selection.bin.gz");
        if (stream == null)
            throw new IllegalStateException("Missing native connected selection facts");

        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            if (in.readInt() != 0x4e435031
                    || in.readInt() != BLOCK_IDS
                    || in.readInt() != METADATA_VALUES) {
                throw new IOException("Invalid connected selection header");
            }
            NeighborRow[] neighbors = new NeighborRow[BLOCK_IDS];
            int[] kinds = new int[BLOCK_IDS];
            SturdyRow[] sturdy = new SturdyRow[BLOCK_IDS];
            for (int id = 0; id < BLOCK_IDS; id++) {
                int size = in.readInt();
                int[] ends = readRangeEnds(in, size);
                Neighbor[] values = new Neighbor[size];
                for (int index = 0; index < size; index++) {
                    readRangeEnd(in, ends, index);
                    values[index] = new Neighbor(in.readInt(), in.readUnsignedByte());
                }
                requireComplete(ends);
                neighbors[id] = new NeighborRow(ends, values);
            }
            int count = in.readInt();
            if (count < 1 || count > BLOCK_IDS)
                throw new IOException("Invalid connected registry size");
            for (int index = 0; index < count; index++) {
                int id = in.readInt();
                int kind = in.readUnsignedByte();
                if (id < 0 || id >= BLOCK_IDS || kinds[id] != 0 || kind < FENCE || kind > THIN) {
                    throw new IOException("Invalid connected registry entry");
                }
                kinds[id] = kind;
                int size = in.readInt();
                int[] ends = readRangeEnds(in, size);
                int[] masks = new int[size];
                for (int range = 0; range < size; range++) {
                    readRangeEnd(in, ends, range);
                    masks[range] = in.readUnsignedShort();
                }
                requireComplete(ends);
                sturdy[id] = new SturdyRow(ends, masks);
            }
            if (in.read() != -1) throw new IOException("Trailing connected selection data");
            return new Table(neighbors, kinds, sturdy);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid native connected selection facts", exception);
        }
    }
}
