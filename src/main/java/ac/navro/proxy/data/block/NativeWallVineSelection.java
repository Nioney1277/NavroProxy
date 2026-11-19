package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;


public final class NativeWallVineSelection {
    private static final int IDS = 2048;
    private static final int METADATA = 8192;
    private static final int WALL = 1;
    private static final int VINE = 2;
    private static final Table TABLE = load();


    public record State(int id, int metadata) {}

    public record Selection(Box outline, boolean sturdy) {}

    private record Traits(int flags, int face) {}

    private record TraitRow(int[] ends, Traits[] values) {}

    private record SturdyRow(int[] ends, int[] masks) {}

    private record Table(TraitRow[] neighbors, int[] kinds, SturdyRow[] sturdy) {}

    private NativeWallVineSelection() {}

    public static boolean supported(int id) {
        return id >= 0 && id < IDS && TABLE.kinds[id] != 0;
    }

    public static boolean wall(int id) {
        return supported(id) && TABLE.kinds[id] == WALL;
    }

    public static Selection selection(
            int id, int metadata, int x, int y, int z, IntFunction<State> neighbors) {
        if (!supported(id) || metadata < 0 || metadata >= METADATA) {
            throw new IllegalArgumentException("Unsupported native wall/vine selection");
        }
        int pattern;
        Box outline;
        if (TABLE.kinds[id] == WALL) {
            boolean north = wallConnects(neighbors.apply(0), 0);
            boolean south = wallConnects(neighbors.apply(1), 1);
            boolean west = wallConnects(neighbors.apply(2), 2);
            boolean east = wallConnects(neighbors.apply(3), 3);
            pattern = (north ? 1 : 0) | (south ? 2 : 0) | (west ? 4 : 0) | (east ? 8 : 0);
            outline = wallBounds(x, y, z, north, south, west, east);
        } else {
            int damage = NativeSelectionProperties.get(id, metadata).damage();


            boolean upperSolid = damage == 0 && solid(neighbors.apply(4));
            pattern = upperSolid ? 1 : 0;
            outline = vineBounds(x, y, z, damage, upperSolid);
        }
        var row = TABLE.sturdy[id];
        boolean sturdy = (row.masks[index(row.ends, metadata)] & (1 << pattern)) != 0;
        return new Selection(outline, sturdy);
    }

    private static Traits traits(State state) {
        if (state.id < 0 || state.id >= IDS || state.metadata < 0 || state.metadata >= METADATA) {
            throw new NativeConnectedSelection.UnknownProperty("unknown_native_wall_neighbor");
        }
        var row = TABLE.neighbors[state.id];
        return row.values[index(row.ends, state.metadata)];
    }

    private static boolean solid(State state) {
        return NativeConnectedSelection.neighbor(state.id, state.metadata).isSolid();
    }

    private static boolean wallConnects(State state, int side) {
        var nativeProperties = NativeConnectedSelection.neighbor(state.id, state.metadata);
        if (nativeProperties.id() == 20) return true;
        Traits traits = traits(state);
        if ((traits.flags & (1 | 2 | 4)) != 0) return true;
        if ((traits.flags & 8) != 0) {
            boolean faceAlongX = traits.face == 4 || traits.face == 5;
            boolean neighborAlongX = side == 2 || side == 3;
            return faceAlongX != neighborAlongX;
        }
        int face = side == 0 ? 2 : side == 1 ? 3 : side == 2 ? 4 : 5;
        if ((traits.flags & 16) != 0) return (traits.face ^ 1) == face;
        if ((traits.flags & 32) != 0) return (traits.flags & 64) != 0 && traits.face == face;
        return nativeProperties.isSolid() && !nativeProperties.isTransparent();
    }

    private static Box wallBounds(
            int x, int y, int z, boolean north, boolean south, boolean west, boolean east) {
        double minZ = north ? 0 : 0.25;
        double maxZ = south ? 1 : 0.75;
        double minX = west ? 0 : 0.25;
        double maxX = east ? 1 : 0.75;
        if (north && south && !west && !east) {
            minX = 0.3125;
            maxX = 0.6875;
        } else if (!north && !south && west && east) {
            minZ = 0.3125;
            maxZ = 0.6875;
        }
        return Box.fromNativeBounds(x + minX, y, z + minZ, x + maxX, y + 1.5, z + maxZ);
    }

    private static Box vineBounds(int x, int y, int z, int damage, boolean upperSolid) {
        double f1 = 1, f2 = 1, f3 = 1, f4 = 0, f5 = 0, f6 = 0;
        boolean flag = damage > 0;
        if ((damage & 0x02) > 0) {
            f4 = Math.max(f4, 0.0625);
            f1 = 0;
            f2 = 0;
            f5 = 1;
            f3 = 0;
            f6 = 1;
            flag = true;
        }
        if ((damage & 0x08) > 0) {
            f1 = Math.min(f1, 0.9375);
            f4 = 1;
            f2 = 0;
            f5 = 1;
            f3 = 0;
            f6 = 1;
            flag = true;
        }
        if ((damage & 0x01) > 0) {
            f3 = Math.min(f3, 0.9375);
            f6 = 1;
            f1 = 0;
            f4 = 1;
            f2 = 0;
            f5 = 1;
            flag = true;
        }
        if (!flag && upperSolid) {
            f2 = Math.min(f2, 0.9375);
            f5 = 1;
            f1 = 0;
            f4 = 1;
            f3 = 0;
            f6 = 1;
        }
        return Box.fromNativeBounds(x + f1, y + f2, z + f3, x + f4, y + f5, z + f6);
    }

    private static int index(int[] ends, int metadata) {
        int result = Arrays.binarySearch(ends, metadata);
        return result >= 0 ? result : -result - 1;
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 1 || count > maximum) throw new IOException("Invalid wall/vine range count");
        return count;
    }

    private static void readEnd(DataInputStream in, int[] ends, int i) throws IOException {
        ends[i] = in.readUnsignedShort();
        if (ends[i] >= METADATA || i > 0 && ends[i] <= ends[i - 1]) {
            throw new IOException("Invalid wall/vine metadata range");
        }
    }

    private static void complete(int[] ends) throws IOException {
        if (ends[ends.length - 1] != METADATA - 1)
            throw new IOException("Incomplete wall/vine metadata");
    }

    private static Table load() {
        var stream =
                NativeWallVineSelection.class.getResourceAsStream(
                        "/navro/wall-vine-selection.bin.gz");
        if (stream == null)
            throw new IllegalStateException("Missing native wall/vine selection facts");
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            if (in.readInt() != 0x4e575631 || in.readInt() != IDS || in.readInt() != METADATA) {
                throw new IOException("Invalid wall/vine header");
            }
            var neighbors = new TraitRow[IDS];
            for (int id = 0; id < IDS; id++) {
                int size = count(in, METADATA);
                int[] ends = new int[size];
                var values = new Traits[size];
                for (int i = 0; i < size; i++) {
                    readEnd(in, ends, i);
                    values[i] = new Traits(in.readUnsignedByte(), in.readByte());
                }
                complete(ends);
                neighbors[id] = new TraitRow(ends, values);
            }
            var kinds = new int[IDS];
            var sturdy = new SturdyRow[IDS];
            int entries = count(in, IDS);
            for (int entry = 0; entry < entries; entry++) {
                int id = in.readInt(), kind = in.readUnsignedByte();
                if (id < 0 || id >= IDS || kinds[id] != 0 || kind < WALL || kind > VINE)
                    throw new IOException("Invalid wall/vine registry");
                kinds[id] = kind;
                int size = count(in, METADATA);
                int[] ends = new int[size], masks = new int[size];
                for (int i = 0; i < size; i++) {
                    readEnd(in, ends, i);
                    masks[i] = in.readUnsignedShort();
                }
                complete(ends);
                sturdy[id] = new SturdyRow(ends, masks);
            }
            if (in.read() != -1) throw new IOException("Trailing wall/vine data");
            return new Table(neighbors, kinds, sturdy);
        } catch (IOException error) {
            throw new IllegalStateException("Invalid native wall/vine selection facts", error);
        }
    }
}
