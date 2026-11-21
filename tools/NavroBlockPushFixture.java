package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.tag.CompoundTag;
import ac.navro.anticheat.collision.block.ShulkerCollisionState;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;


public final class NavroBlockPushFixture {
    private static void vector(DataOutputStream out, Vec3 v) throws Exception {
        out.writeFloat(v.x); out.writeFloat(v.y); out.writeFloat(v.z);
    }
    private static void box(DataOutputStream out, Box b) throws Exception {
        out.writeFloat(b.minX); out.writeFloat(b.minY); out.writeFloat(b.minZ);
        out.writeFloat(b.maxX); out.writeFloat(b.maxY); out.writeFloat(b.maxZ);
    }
    public static void main(String[] args) throws Exception {
        Random random = new Random(0x4E4156524F505553L);
        int count = 3000;
        try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
            out.writeInt(0x4E425031); out.writeInt(count);
            for (int i = 0; i < count; i++) {
                float origin = switch (i % 8) {
                    case 0 -> 0;
                    case 1 -> -17;
                    case 2 -> 65536;
                    case 3 -> 16777216;
                    default -> random.nextFloat() * 16 - 8;
                };
                Box shulker = new Box(origin, 0, origin, origin + 1, 1, origin + 1);
                float x = origin + random.nextFloat() * 1.4F - 0.2F;
                float y = random.nextFloat() * 1.4F - 0.2F;
                float z = origin + random.nextFloat() * 1.4F - 0.2F;
                Box actor = new Box(x, y, z, x + 0.6F, y + 1.8F, z + 0.6F);
                Vec3 velocity = new Vec3(random.nextFloat() * 0.4F - 0.2F,
                        random.nextFloat() - 0.5F, random.nextFloat() * 0.4F - 0.2F);
                var collisions = new ArrayList<Box>();
                switch (i % 10) {
                    case 0 -> { }
                    case 1 -> {
                        actor = new Box(origin + 0.2F, 0.2F, origin + 0.2F,
                                origin + 0.8F, 0.8F, origin + 0.8F);
                        collisions.add(shulker); velocity = Vec3.ZERO;
                    }
                    case 2 -> {
                        collisions.add(new Box(origin - 4, -1, origin - 4, origin + 4, 3, origin + 4));
                        velocity = new Vec3(-0.0F, 0.123F, 0);
                    }
                    case 3 -> collisions.add(new Box(origin + 8, 0, origin + 8, origin + 9, 1, origin + 9));
                    case 4 -> {
                        actor = new Box(origin + 0.5F, 0, origin + 0.2F, origin + 1.1F, 1.8F, origin + 0.8F);
                        collisions.add(new Box(origin + 1.1F, 0, origin, origin + 2, 2, origin + 1));
                        velocity = new Vec3(1.0E-8F, -0.0F, -1.0E-8F);
                    }
                    default -> {
                        collisions.add(shulker);
                        for (int j = random.nextInt(6); j > 0; j--) {
                            float bx = origin + random.nextFloat() * 4 - 2;
                            float by = random.nextFloat() * 3 - 1;
                            float bz = origin + random.nextFloat() * 4 - 2;
                            collisions.add(new Box(bx, by, bz, bx + 0.1F + random.nextFloat() * 2,
                                    by + 0.1F + random.nextFloat() * 2, bz + 0.1F + random.nextFloat() * 2));
                        }
                        Collections.shuffle(collisions, random);
                    }
                }
                if (i % 71 == 0) actor = Box.EMPTY;
                if (i % 73 == 0) shulker = Box.EMPTY;
                int facing = (i / 10 + i) % 6;
                box(out, actor); box(out, shulker); out.writeInt(facing); vector(out, velocity);
                out.writeInt(collisions.size()); for (Box collision : collisions) box(out, collision);
                vector(out, MoveTowardsClosestSpace.apply(actor, collisions, velocity));
                vector(out, ShulkerPushPhysics.calculateMovement(actor, shulker,
                        BlockFace.values()[facing], List.copyOf(collisions)));
            }
        }
        try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            out.writeInt(0x4E425332); out.writeInt(6);
            for (int facing = 0; facing < 6; facing++) {
                var lid = new ShulkerCollisionState(new BlockVector3(0, 64, 0),
                        new CompoundTag().putByte("facing", facing));
                lid.setOpenCount(1); lid.tick(); lid.tick();
                Box shulker = lid.snapshot().shape();
                Box actor = switch (facing) {
                    case 0 -> new Box(0.2F, 62.16F, 0.2F, 0.8F, 63.96F, 0.8F);
                    case 1 -> new Box(0.2F, 65.04F, 0.2F, 0.8F, 66.84F, 0.8F);
                    case 2 -> new Box(0.2F, 64.1F, -0.64F, 0.8F, 65.9F, -0.04F);
                    case 3 -> new Box(0.2F, 64.1F, 1.04F, 0.8F, 65.9F, 1.64F);
                    case 4 -> new Box(-0.64F, 64.1F, 0.2F, -0.04F, 65.9F, 0.8F);
                    default -> new Box(1.04F, 64.1F, 0.2F, 1.64F, 65.9F, 0.8F);
                };
                Vec3 request = ShulkerPushPhysics.calculateMovement(actor, shulker, BlockFace.values()[facing], List.of());
                var result = Collider.solveResolved(actor, List.of(shulker), request, new Vec3(1, 1, 1), 0.5625F, true);
                out.writeInt(facing); box(out, actor); box(out, shulker); vector(out, request);
                vector(out, result.result().movement); box(out, result.result().aabb);
                out.writeFloat(result.ordinaryOverlapDepth());
                boolean horizontal = Math.abs(request.x - result.result().movement.x) > 1.1920929E-7F
                        || Math.abs(request.z - result.result().movement.z) > 1.1920929E-7F;
                boolean vertical = Math.abs(request.y - result.result().movement.y) > 1.1920929E-7F;
                out.writeBoolean(horizontal); out.writeBoolean(vertical);
                out.writeBoolean(vertical ? request.y < 0 : request.y == 0);
            }
        }
    }
}
