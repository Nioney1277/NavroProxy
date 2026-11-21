import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.data.block.NetworkBlockState;

import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.math.*;

import sun.misc.Unsafe;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;




public final class NukkitConnectedSelectionExporter {
    static final class MissingWorld extends RuntimeException {}

    public static final class World extends Level {
        boolean missing, mixed;
        int mask, x, y, z, neighborId, neighborMeta;

        public World() {
            super(null, "fixture", "", (Class<? extends LevelProvider>) null);
        }

        private Block at(int x, int y, int z, int layer) {
            if (missing) throw new MissingWorld();
            int side =
                    x == this.x && y == this.y && z == this.z - 1
                            ? 0
                            : x == this.x && y == this.y && z == this.z + 1
                                    ? 1
                                    : x == this.x - 1 && y == this.y && z == this.z
                                            ? 2
                                            : x == this.x + 1 && y == this.y && z == this.z
                                                    ? 3
                                                    : -1;
            int id = side >= 0 && (mask & (1 << side)) != 0 ? 1 : 0;
            int meta = 0;
            if (mixed && side == 0) {
                id = neighborId;
                meta = neighborMeta;
            }
            return Block.get(id, meta, this, x, y, z, layer);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return at(x, y, z, 0);
        }

        @Override
        public Block getBlock(int x, int y, int z, int layer) {
            return at(x, y, z, layer);
        }

        @Override
        public Block getBlock(int x, int y, int z, int layer, boolean load) {
            return at(x, y, z, layer);
        }
    }

    static Class<?> declaring(Class<?> cls, String method, Class<?>... args) throws Exception {
        while (cls != null) {
            try {
                return cls.getDeclaredMethod(method, args).getDeclaringClass();
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchMethodException(method);
    }

    static int kind(Block b) throws Exception {
        Class<?> shape = declaring(b.getClass(), "recalculateBoundingBox"), connect;
        if (shape == BlockFence.class) {
            connect = b.getClass().getMethod("canConnect", Block.class).getDeclaringClass();
            return connect == BlockFence.class ? 1 : connect == BlockFenceNetherBrick.class ? 2 : 0;
        }
        if (shape == BlockThin.class) {
            connect = b.getClass().getMethod("canConnect", Block.class).getDeclaringClass();
            return connect == BlockThin.class ? 3 : 0;
        }
        return 0;
    }

    record Facts(int id, int flags) {}

    record Run(int end, Facts value) {}

    record MaskRun(int end, int mask) {}

    static Facts facts(Block b) {
        int f =
                (b instanceof BlockFence ? 1 : 0)
                        | (b instanceof BlockFenceNetherBrick ? 2 : 0)
                        | (b instanceof BlockFenceGate ? 4 : 0)
                        | (b instanceof BlockWall ? 8 : 0);
        try {
            boolean v = b.isSolid();
            f |= 16;
            if (v) f |= 32;
        } catch (MissingWorld | cn.nukkit.utils.LevelException ignored) {
        }
        try {
            boolean v = b.isTransparent();
            f |= 64;
            if (v) f |= 128;
        } catch (MissingWorld | cn.nukkit.utils.LevelException ignored) {
        }
        return new Facts(b.getId(), f);
    }

    static boolean connects(Block b, Block neighbor) {
        return b instanceof BlockFence fence
                ? fence.canConnect(neighbor)
                : ((BlockThin) b).canConnect(neighbor);
    }

    static boolean sturdy(Block b, int raw) {
        return new NavroBlockState(b, NetworkBlockState.legacy(raw), new BlockVector3(), 0)
                .isFaceSturdy(null);
    }

    static DataOutputStream output(String path) throws Exception {
        return new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(path))));
    }

    static void clear(Object[] caches) {
        for (Object cache : caches) if (cache instanceof Map<?, ?> m) m.clear();
    }

    public static void main(String[] args) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var u = (Unsafe) f.get(null);
        f = Server.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, u.allocateInstance(Server.class));
        Block.init();
        var world = (World) u.allocateInstance(World.class);
        f = Block.class.getDeclaredField("statePrototypes");
        f.setAccessible(true);
        Object[] caches = (Object[]) f.get(null);
        var supported = new LinkedHashMap<Integer, Integer>();
        for (int id = 0; id < 2048; id++) {
            int k = kind(Block.get(id, 0, world, 0, 0, 0, 0));
            if (k != 0) supported.put(id, k);
        }
        boolean rulesOnly = args.length > 3 && args[3].equals("rules-only");
        if (!rulesOnly)
            try (var out = output(args[0])) {
                out.writeInt(0x4e435031);
                out.writeInt(2048);
                out.writeInt(8192);
                world.missing = true;
                for (int id = 0; id < 2048; id++) {
                    var runs = new ArrayList<Run>();
                    Facts prev = null;
                    for (int meta = 0; meta < 8192; meta++) {
                        var value = facts(Block.get(id, meta, world, 0, 0, 0, 0));
                        if (prev != null && !prev.equals(value)) runs.add(new Run(meta - 1, prev));
                        prev = value;
                    }
                    runs.add(new Run(8191, prev));
                    out.writeInt(runs.size());
                    for (var run : runs) {
                        out.writeShort(run.end);
                        out.writeInt(run.value.id);
                        out.writeByte(run.value.flags);
                    }
                    clear(caches);
                    if ((id + 1) % 256 == 0)
                        System.out.println("neighbor facts " + (id + 1) + "/2048");
                }
                world.missing = false;
                out.writeInt(supported.size());
                for (var entry : supported.entrySet()) {
                    int id = entry.getKey();
                    out.writeInt(id);
                    out.writeByte(entry.getValue());
                    var runs = new ArrayList<MaskRun>();
                    int prev = -1;
                    for (int meta = 0; meta < 8192; meta++) {
                        int bits = 0;
                        for (int mask = 0; mask < 16; mask++) {
                            world.mask = mask;
                            var b = Block.get(id, meta, world, 0, 0, 0, 0);
                            if (sturdy(b, (id << 13) | meta)) bits |= 1 << mask;
                        }
                        if (prev >= 0 && prev != bits) runs.add(new MaskRun(meta - 1, prev));
                        prev = bits;
                    }
                    runs.add(new MaskRun(8191, prev));
                    out.writeInt(runs.size());
                    for (var run : runs) {
                        out.writeShort(run.end);
                        out.writeShort(run.mask);
                    }
                    clear(caches);
                    System.out.println("sturdy masks id=" + id);
                }
            }
        int[] metas = {0, 1, 7, 15, 16, 31, 255, 8191};
        int geometry = supported.size() * metas.length * 16;
        if (!rulesOnly)
            try (var out = output(args[1])) {
                out.writeInt(0x4e434731);
                out.writeInt(geometry);
                world.missing = false;
                world.x = 17;
                world.y = 65;
                world.z = -9;
                for (int id : supported.keySet())
                    for (int meta : metas)
                        for (int mask = 0; mask < 16; mask++) {
                            world.mask = mask;
                            var b = Block.get(id, meta, world, 17, 65, -9, 0);
                            var box = b.getBoundingBox();
                            out.writeInt(id);
                            out.writeInt(meta);
                            out.writeInt(mask);
                            out.writeInt(b.getId());
                            out.writeInt(b.getDamage());
                            out.writeBoolean(sturdy(b, (id << 13) | meta));
                            out.writeDouble(box.getMinX());
                            out.writeDouble(box.getMinY());
                            out.writeDouble(box.getMinZ());
                            out.writeDouble(box.getMaxX());
                            out.writeDouble(box.getMaxY());
                            out.writeDouble(box.getMaxZ());
                        }
            }
        int connections = supported.size() * 3 * 2048 * metas.length;



        world.mask = 0;
        world.mixed = false;
        try (var out = output(args[2])) {
            out.writeInt(0x4e434332);
            out.writeInt(connections);
            world.missing = true;
            world.x = 0;
            world.y = 0;
            world.z = 0;
            for (int id : supported.keySet()) {
                for (int own : new int[] {0, 15, 8191})
                    for (int neighbor = 0; neighbor < 2048; neighbor++)
                        for (int meta : metas) {
                            var b = Block.get(id, own, world, 0, 0, 0, 0);
                            var other = Block.get(neighbor, meta, world, 0, 0, 0, 0);
                            boolean known = true, value = false;
                            try {
                                value = connects(b, other);
                            } catch (MissingWorld | cn.nukkit.utils.LevelException error) {
                                known = false;
                            }
                            out.writeInt(id);
                            out.writeInt(own);
                            out.writeInt(neighbor);
                            out.writeInt(meta);
                            out.writeBoolean(known);
                            out.writeBoolean(value);
                            if (known) {
                                world.missing = false;
                                world.mixed = true;
                                world.neighborId = neighbor;
                                world.neighborMeta = meta;
                                var box = b.getBoundingBox();
                                out.writeDouble(box.getMinX());
                                out.writeDouble(box.getMinY());
                                out.writeDouble(box.getMinZ());
                                out.writeDouble(box.getMaxX());
                                out.writeDouble(box.getMaxY());
                                out.writeDouble(box.getMaxZ());
                                out.writeBoolean(sturdy(b, (id << 13) | own));
                                world.missing = true;
                            }
                        }
                clear(caches);
            }
        }
        System.out.println(
                "supported=" + supported + " geometry=" + geometry + " connections=" + connections);
    }
}
