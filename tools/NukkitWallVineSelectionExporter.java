import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.data.block.NetworkBlockState;

import cn.nukkit.Server;
import cn.nukkit.block.*;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.math.BlockVector3;

import sun.misc.Unsafe;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitWallVineSelectionExporter {
    static final class MissingWorld extends RuntimeException {}

    public static final class World extends Level {
        boolean missing;
        int mask, varyingSide = -1, neighborId, neighborMeta, aboveId, aboveMeta;
        int x = 17, y = 65, z = -9;

        World() {
            super(null, "fixture", "", (Class<? extends LevelProvider>) null);
        }

        Block at(int x, int y, int z, int layer) {
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
            if (side >= 0 && side == varyingSide) {
                id = neighborId;
                meta = neighborMeta;
            }
            if (x == this.x && y == this.y + 1 && z == this.z) {
                id = aboveId;
                meta = aboveMeta;
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

    record Traits(int flags, int face) {}

    record TraitRun(int end, Traits value) {}

    record MaskRun(int end, int mask) {}

    static Class<?> declaring(Class<?> type, String method, Class<?>... args) throws Exception {
        while (type != null) {
            try {
                return type.getDeclaredMethod(method, args).getDeclaringClass();
            } catch (NoSuchMethodException absent) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(method);
    }

    static boolean wall(Block b) throws Exception {
        return declaring(b.getClass(), "recalculateBoundingBox") == BlockWall.class
                && b.getClass().getMethod("canConnect", Block.class).getDeclaringClass()
                        == BlockWall.class;
    }

    static boolean vine(Block b) throws Exception {
        return declaring(b.getClass(), "recalculateBoundingBox") == BlockVine.class;
    }

    static Traits traits(Block block) {
        int flags = 0, face = -1;
        if (block instanceof BlockThin) flags |= 1;
        if (block instanceof BlockGlassStained) flags |= 2;
        if (block instanceof BlockWall) flags |= 4;
        if (block instanceof BlockFenceGate gate) {
            flags |= 8;
            face = gate.getBlockFace().getIndex();
        }
        if (block instanceof BlockStairs stairs) {
            flags |= 16;
            face = stairs.getBlockFace().getIndex();
        }
        if (block instanceof BlockTrapdoor trapdoor) {
            flags |= 32;
            face = trapdoor.getBlockFace().getIndex();
            if (trapdoor.isOpen()) flags |= 64;
        }
        return new Traits(flags, face);
    }

    static boolean sturdy(Block b, int id, int meta) {
        return new NavroBlockState(
                        b,
                        NetworkBlockState.legacy((id << 13) | meta),
                        new BlockVector3((int) b.x, (int) b.y, (int) b.z),
                        0)
                .isFaceSturdy(null);
    }

    static void outcome(DataOutputStream out, Block b, int id, int meta) throws Exception {
        var bounds = b.getBoundingBox();
        out.writeInt(b.getId());
        out.writeInt(b.getDamage());
        out.writeBoolean(sturdy(b, id, meta));
        out.writeBoolean(bounds != null);
        if (bounds != null) {
            out.writeDouble(bounds.getMinX());
            out.writeDouble(bounds.getMinY());
            out.writeDouble(bounds.getMinZ());
            out.writeDouble(bounds.getMaxX());
            out.writeDouble(bounds.getMaxY());
            out.writeDouble(bounds.getMaxZ());
        }
    }

    static DataOutputStream output(Path path) throws IOException {
        return new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(path))));
    }

    static void clear(Object[] caches) {
        for (Object cache : caches) if (cache instanceof Map<?, ?> m) m.clear();
    }

    public static void main(String[] args) throws Exception {
        Path production = Path.of(args[0]), reference = Path.of(args[1]);
        var f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var unsafe = (Unsafe) f.get(null);
        f = Server.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, unsafe.allocateInstance(Server.class));
        Block.init();
        World world = (World) unsafe.allocateInstance(World.class);
        world.x = 17;
        world.y = 65;
        world.z = -9;
        world.varyingSide = -1;
        f = Block.class.getDeclaredField("statePrototypes");
        f.setAccessible(true);
        Object[] caches = (Object[]) f.get(null);
        var registry = new LinkedHashMap<Integer, Integer>();
        for (int id = 0; id < 2048; id++) {
            Block b = Block.get(id, 0, world, world.x, world.y, world.z, 0);
            if (wall(b)) registry.put(id, 1);
            else if (vine(b)) registry.put(id, 2);
        }
        try (var out = output(production)) {
            out.writeInt(0x4e575631);
            out.writeInt(2048);
            out.writeInt(8192);
            world.missing = true;
            for (int id = 0; id < 2048; id++) {
                var runs = new ArrayList<TraitRun>();
                Traits previous = null;
                for (int meta = 0; meta < 8192; meta++) {
                    Traits value = traits(Block.get(id, meta, world, 0, 0, 0, 0));
                    if (previous != null && !previous.equals(value))
                        runs.add(new TraitRun(meta - 1, previous));
                    previous = value;
                }
                runs.add(new TraitRun(8191, previous));
                out.writeInt(runs.size());
                for (var run : runs) {
                    out.writeShort(run.end);
                    out.writeByte(run.value.flags);
                    out.writeByte(run.value.face);
                }
                clear(caches);
            }
            world.missing = false;
            world.varyingSide = -1;
            out.writeInt(registry.size());
            for (var entry : registry.entrySet()) {
                int id = entry.getKey(), kind = entry.getValue();
                out.writeInt(id);
                out.writeByte(kind);
                var runs = new ArrayList<MaskRun>();
                int previous = -1;
                for (int meta = 0; meta < 8192; meta++) {
                    int bits = 0, patterns = kind == 1 ? 16 : 2;
                    for (int pattern = 0; pattern < patterns; pattern++) {
                        world.mask = kind == 1 ? pattern : 0;
                        world.aboveId = kind == 2 && pattern == 1 ? 1 : 0;
                        world.aboveMeta = 0;
                        Block b = Block.get(id, meta, world, world.x, world.y, world.z, 0);
                        if (sturdy(b, id, meta)) bits |= 1 << pattern;
                    }
                    if (previous >= 0 && previous != bits)
                        runs.add(new MaskRun(meta - 1, previous));
                    previous = bits;
                }
                runs.add(new MaskRun(8191, previous));
                out.writeInt(runs.size());
                for (var run : runs) {
                    out.writeShort(run.end);
                    out.writeShort(run.mask);
                }
                clear(caches);
                System.out.println("sturdy id=" + id + " kind=" + kind);
            }
        }
        int[] metas = {0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 63, 255, 8191};
        long rows = 0;
        for (int kind : registry.values())
            rows +=
                    kind == 1
                            ? 8192L * 16 + 3L * 2048 * metas.length * 4
                            : 8192L * 2 + 2048L * metas.length;
        try (var out = output(reference)) {
            out.writeInt(0x4e575231);
            out.writeInt(Math.toIntExact(rows));
            for (var entry : registry.entrySet()) {
                int id = entry.getKey(), kind = entry.getValue();
                for (int meta = 0; meta < 8192; meta++)
                    for (int pattern = 0; pattern < (kind == 1 ? 16 : 2); pattern++) {
                        world.mask = kind == 1 ? pattern : 0;
                        world.aboveId = kind == 2 && pattern == 1 ? 1 : 0;
                        world.aboveMeta = 0;
                        world.varyingSide = -1;
                        out.writeInt(id);
                        out.writeInt(meta);
                        out.writeInt(world.mask);
                        out.writeInt(-1);
                        out.writeInt(0);
                        out.writeInt(0);
                        out.writeInt(world.aboveId);
                        out.writeInt(0);
                        outcome(
                                out,
                                Block.get(id, meta, world, world.x, world.y, world.z, 0),
                                id,
                                meta);
                    }
                clear(caches);
                world.mask = 0;
                for (int neighbor = 0; neighbor < 2048; neighbor++)
                    for (int neighborMeta : metas) {
                        for (int own : kind == 1 ? new int[] {0, 15, 8191} : new int[] {0})
                            for (int side = 0; side < (kind == 1 ? 4 : 1); side++) {
                                world.varyingSide = kind == 1 ? side : -1;
                                world.neighborId = neighbor;
                                world.neighborMeta = neighborMeta;
                                world.aboveId = kind == 2 ? neighbor : 0;
                                world.aboveMeta = kind == 2 ? neighborMeta : 0;
                                out.writeInt(id);
                                out.writeInt(own);
                                out.writeInt(0);
                                out.writeInt(world.varyingSide);
                                out.writeInt(neighbor);
                                out.writeInt(neighborMeta);
                                out.writeInt(world.aboveId);
                                out.writeInt(world.aboveMeta);
                                outcome(
                                        out,
                                        Block.get(id, own, world, world.x, world.y, world.z, 0),
                                        id,
                                        own);
                            }
                        if (neighborMeta == metas[metas.length - 1]) clear(caches);
                    }
                world.varyingSide = -1;
                System.out.println("reference id=" + id);
            }
        }
        System.out.println("registry=" + registry + " referenceRows=" + rows);
    }
}
