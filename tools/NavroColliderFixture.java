package ac.navro.anticheat.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import java.io.*;
import java.nio.file.*;
import java.util.*;


public final class NavroColliderFixture {
    private static void vector(DataOutputStream out, Vec3 v) throws IOException {
        out.writeFloat(v.x); out.writeFloat(v.y); out.writeFloat(v.z);
    }
    private static void box(DataOutputStream out, Box b) throws IOException {
        out.writeFloat(b.minX); out.writeFloat(b.minY); out.writeFloat(b.minZ);
        out.writeFloat(b.maxX); out.writeFloat(b.maxY); out.writeFloat(b.maxZ);
    }
    public static void main(String[] args) throws Exception {
        Random random = new Random(0x4E4156524FL);
        try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
            out.writeInt(0x4E434D31); out.writeInt(1200);
            for (int i = 0; i < 1200; i++) {
                Box actor; Vec3 movement, budget; List<Box> shapes;
                float step = i % 3 == 0 ? 0 : i % 3 == 1 ? 0.5625F : 1.0625F;
                boolean canStep = (i & 1) == 0;
                if (i == 0) {
                    actor = new Box(0.9F, 1, 0.2F, 1.5F, 2.8F, 0.8F);
                    movement = new Vec3(1, 0, 0); budget = new Vec3(1, 1, 1);
                    shapes = List.of(new Box(0, 1, 0, 1, 2, 1)); step = 0.5625F; canStep = true;
                } else {
                    float x = random.nextFloat() * 4 - 2, y = random.nextFloat() * 3, z = random.nextFloat() * 4 - 2;
                    actor = new Box(x, y, z, x + 0.6F, y + 1.8F, z + 0.6F);
                    movement = new Vec3(random.nextFloat() * 3 - 1.5F, random.nextFloat() * 2 - 1,
                            random.nextFloat() * 3 - 1.5F);
                    budget = switch (i % 4) {
                        case 0 -> Vec3.ZERO;
                        case 1 -> new Vec3(0, 0.05F, 0);
                        case 2 -> new Vec3(0.1F, 0.1F, 0.1F);
                        default -> new Vec3(1, 1, 1);
                    };
                    var colliders = new ArrayList<Box>();
                    colliders.add(new Box(-5, -1, -5, 5, 0, 5));
                    int count = random.nextInt(6);
                    for (int j = 0; j < count; j++) {
                        float bx = random.nextFloat() * 5 - 2.5F, by = random.nextFloat() * 4 - 1, bz = random.nextFloat() * 5 - 2.5F;
                        colliders.add(new Box(bx, by, bz, bx + random.nextFloat() + 0.1F,
                                by + random.nextFloat() + 0.1F, bz + random.nextFloat() + 0.1F));
                    }
                    shapes = List.copyOf(colliders);
                }
                box(out, actor); vector(out, movement); vector(out, budget);
                out.writeFloat(step); out.writeBoolean(canStep); out.writeInt(shapes.size());
                for (Box shape : shapes) box(out, shape);
                var result = Collider.solveResolved(actor, shapes, movement, budget, step, canStep);
                vector(out, result.result().movement); box(out, result.result().aabb);
                out.writeFloat(result.result().overlapDepth); out.writeFloat(result.ordinaryOverlapDepth());
            }
        }
    }
}
