package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;

import java.io.*;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;





public final class NativeDoorSelection {
    public static final int DOOR_DIRECTION_BIT = 0x03,
            DOOR_OPEN_BIT = 0x04,
            DOOR_TOP_BIT = 0x08,
            DOOR_HINGE_BIT = 0x10;
    private static final boolean[][] STURDY = load();

    public static boolean supported(int id) {
        return id >= 0 && id < STURDY.length && STURDY[id] != null;
    }

    public static boolean sturdy(int id, int rawMetadata) {
        if (!supported(id) || rawMetadata < 0 || rawMetadata >= 8192) {
            throw new IllegalArgumentException("Unsupported native door selection");
        }
        return STURDY[id][rawMetadata];
    }

    private static boolean[][] load() {
        var stream = NativeDoorSelection.class.getResourceAsStream("/navro/door-selection.bin.gz");
        if (stream == null) throw new IllegalStateException("Missing native door selection facts");
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            if (in.readInt() != 0x4e445031) throw new IOException("Invalid door selection header");
            int count = in.readInt();
            if (count < 1 || count > 2048) throw new IOException("Invalid door registry size");
            var table = new boolean[2048][];
            for (int i = 0; i < count; i++) {
                int id = in.readInt();
                if (id < 0 || id >= 2048 || table[id] != null)
                    throw new IOException("Invalid door registry ID");
                table[id] = new boolean[8192];
                for (int meta = 0; meta < 8192; meta++) table[id][meta] = in.readBoolean();
            }
            if (in.read() != -1) throw new IOException("Trailing door selection data");
            return table;
        } catch (IOException e) {
            throw new IllegalStateException("Invalid native door selection facts", e);
        }
    }

    private final int x, y, z, damage;
    private final IntFunction<NativeSelectionProperties.Facts> neighbor;

    private NativeDoorSelection(
            int x,
            int y,
            int z,
            int damage,
            IntFunction<NativeSelectionProperties.Facts> neighbor) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.damage = damage;
        this.neighbor = neighbor;
    }

    public static Box outline(
            int x,
            int y,
            int z,
            int damage,
            IntFunction<NativeSelectionProperties.Facts> neighbor) {
        var b = new NativeDoorSelection(x, y, z, damage, neighbor).recalculateBoundingBox();
        return Box.fromNativeBounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private int getDamage() {
        return damage;
    }

    private NativeState up() {
        return new NativeState(neighbor.apply(1).damage());
    }

    private NativeState down() {
        return new NativeState(neighbor.apply(-1).damage());
    }

    private record NativeState(int getDamage) {}

    private static final class NativeBounds {
        double minX, minY, minZ, maxX, maxY, maxZ;

        NativeBounds(double a, double b, double c, double d, double e, double f) {
            setBounds(a, b, c, d, e, f);
        }

        void setBounds(double a, double b, double c, double d, double e, double f) {
            minX = a;
            minY = b;
            minZ = c;
            maxX = d;
            maxY = e;
            maxZ = f;
        }
    }

    private int getFullDamage() {
        int up;
        int down;
        if (isTop()) {
            down = this.down().getDamage();
            up = this.getDamage();
        } else {
            down = this.getDamage();
            up = this.up().getDamage();
        }

        return down & DOOR_DIRECTION_BIT
                | (isTop() ? DOOR_TOP_BIT : 0)
                | (this.isRightHinged() ? DOOR_HINGE_BIT : 0);
    }

    protected NativeBounds recalculateBoundingBox() {

        double f = 0.1875;
        int damage = this.getFullDamage();

        NativeBounds bb =
                new NativeBounds(this.x, this.y, this.z, this.x + 1, this.y + 2, this.z + 1);

        int j = damage & DOOR_DIRECTION_BIT;
        boolean isOpen = ((damage & DOOR_OPEN_BIT) > 0);
        boolean isRight = this.isRightHinged();

        if (j == 0) {
            if (isOpen) {
                if (!isRight) {
                    bb.setBounds(this.x, this.y, this.z, this.x + 1, this.y + 1, this.z + f);
                } else {
                    bb.setBounds(
                            this.x, this.y, this.z + 1 - f, this.x + 1, this.y + 1, this.z + 1);
                }
            } else {
                bb.setBounds(this.x, this.y, this.z, this.x + f, this.y + 1, this.z + 1);
            }
        } else if (j == 1) {
            if (isOpen) {
                if (!isRight) {
                    bb.setBounds(
                            this.x + 1 - f, this.y, this.z, this.x + 1, this.y + 1, this.z + 1);
                } else {
                    bb.setBounds(this.x, this.y, this.z, this.x + f, this.y + 1, this.z + 1);
                }
            } else {
                bb.setBounds(this.x, this.y, this.z, this.x + 1, this.y + 1, this.z + f);
            }
        } else if (j == 2) {
            if (isOpen) {
                if (!isRight) {
                    bb.setBounds(
                            this.x, this.y, this.z + 1 - f, this.x + 1, this.y + 1, this.z + 1);
                } else {
                    bb.setBounds(this.x, this.y, this.z, this.x + 1, this.y + 1, this.z + f);
                }
            } else {
                bb.setBounds(this.x + 1 - f, this.y, this.z, this.x + 1, this.y + 1, this.z + 1);
            }
        } else if (j == 3) {
            if (isOpen) {
                if (!isRight) {
                    bb.setBounds(this.x, this.y, this.z, this.x + f, this.y + 1, this.z + 1);
                } else {
                    bb.setBounds(
                            this.x + 1 - f, this.y, this.z, this.x + 1, this.y + 1, this.z + 1);
                }
            } else {
                bb.setBounds(this.x, this.y, this.z + 1 - f, this.x + 1, this.y + 1, this.z + 1);
            }
        }

        return bb;
    }

    public boolean isTop() {
        return isTop(this.getDamage());
    }

    public boolean isTop(int meta) {
        return (meta & DOOR_TOP_BIT) != 0;
    }

    public boolean isRightHinged() {
        if (isTop()) {
            return (this.getDamage() & DOOR_HINGE_BIT) > 0;
        }
        return (this.up().getDamage() & DOOR_HINGE_BIT) > 0;
    }
}
