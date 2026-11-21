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


public final class NukkitDoorSelectionExporter {
    public static final class World extends Level {
        int partnerId, partnerMeta;

        public World() {
            super(null, "fixture", "", (Class<? extends LevelProvider>) null);
        }

        private Block at(int x, int y, int z, int layer) {
            return Block.get(partnerId, partnerMeta, this, x, y, z, layer);
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

    static boolean supported(Block block) throws Exception {
        if (!(block instanceof BlockDoor)) return false;
        Class<?> cls = block.getClass();
        while (cls != null) {
            try {
                if (cls.getDeclaredMethod("recalculateBoundingBox").getDeclaringClass()
                        != BlockDoor.class) return false;
                break;
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            }
        }
        return block.getClass().getMethod("isRightHinged").getDeclaringClass() == BlockDoor.class
                && block.getClass().getMethod("isTop").getDeclaringClass() == BlockDoor.class;
    }

    static boolean sturdy(Block b) {
        return new NavroBlockState(
                        b, NetworkBlockState.legacy(b.getFullId()), new BlockVector3(), 0)
                .isFaceSturdy(null);
    }

    static DataOutputStream output(String path) throws Exception {
        return new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(path))));
    }

    public static void main(String[] args) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        field = Server.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, unsafe.allocateInstance(Server.class));
        Block.init();
        var world = (World) unsafe.allocateInstance(World.class);
        var ids = new ArrayList<Integer>();
        for (int id = 0; id < 2048; id++)
            if (supported(Block.get(id, 0, world, 0, 0, 0, 0))) ids.add(id);
        field = Block.class.getDeclaredField("statePrototypes");
        field.setAccessible(true);
        Object[] caches = (Object[]) field.get(null);
        try (var out = output(args[0])) {
            out.writeInt(0x4e445031);
            out.writeInt(ids.size());
            for (int id : ids) {
                out.writeInt(id);
                world.partnerId = id;
                world.partnerMeta = 0;
                for (int meta = 0; meta < 8192; meta++)
                    out.writeBoolean(sturdy(Block.get(id, meta, world, 0, 0, 0, 0)));
                for (Object cache : caches) if (cache instanceof Map<?, ?> m) m.clear();
            }
        }
        int[] metas = new int[34];
        for (int i = 0; i < 32; i++) metas[i] = i;
        metas[32] = 255;
        metas[33] = 8191;
        int rows = ids.size() * metas.length * metas.length * 3;
        try (var out = output(args[1])) {
            out.writeInt(0x4e445231);
            out.writeInt(rows);
            for (int id : ids)
                for (int own : metas)
                    for (int partner : metas)
                        for (int kind = 0; kind < 3; kind++) {
                            world.partnerId = kind == 0 ? id : kind == 1 ? 1 : 0;
                            world.partnerMeta = partner;
                            int x = 17, y = 65, z = -9;
                            var b = Block.get(id, own, world, x, y, z, 0);
                            var bounds = b.getBoundingBox();
                            out.writeInt(id);
                            out.writeInt(own);
                            out.writeInt(world.partnerId);
                            out.writeInt(partner);
                            out.writeInt(b.getId());
                            out.writeInt(b.getDamage());
                            out.writeBoolean(sturdy(b));
                            out.writeDouble(bounds.getMinX());
                            out.writeDouble(bounds.getMinY());
                            out.writeDouble(bounds.getMinZ());
                            out.writeDouble(bounds.getMaxX());
                            out.writeDouble(bounds.getMaxY());
                            out.writeDouble(bounds.getMaxZ());
                        }
        }
        System.out.println("door IDs=" + ids + " rows=" + rows);
    }
}
