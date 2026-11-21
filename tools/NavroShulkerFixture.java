import ac.navro.anticheat.collision.block.ShulkerCollisionState;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.tag.CompoundTag;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;


public class NavroShulkerFixture {
    public static void main(String[] args) throws Exception {
        int[][] positions = {{0, 0, 0}, {-17, -64, 31}, {16777216, 319, -16777217}};
        int[] facings = {-1, 0, 1, 2, 3, 4, 5, 6, 257};
        try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
            out.writeInt(0x4E534831); out.writeInt(positions.length * facings.length); out.writeInt(72);
            for (int[] p : positions) for (int facing : facings) {
                out.writeInt(p[0]); out.writeInt(p[1]); out.writeInt(p[2]); out.writeInt(facing);
                var actor = new ShulkerCollisionState(new BlockVector3(p[0], p[1], p[2]),
                        new CompoundTag().putInt("facing", facing));
                for (int tick = 0; tick < 72; tick++) {
                    actor.setOpenCount(tick < 26 ? 1 : tick < 50 ? 0 : tick < 57 ? 2 : -1);
                    if (tick == 12) actor.load(new CompoundTag().putInt("facing", facing + 1));
                    if (tick == 41) actor.load(new CompoundTag());
                    actor.tick();
                    var frame = actor.snapshot(); var box = frame.shape();
                    out.writeInt(frame.facing()); out.writeFloat(frame.openness());
                    out.writeFloat(box.minX); out.writeFloat(box.minY); out.writeFloat(box.minZ);
                    out.writeFloat(box.maxX); out.writeFloat(box.maxY); out.writeFloat(box.maxZ);
                }
            }
        }
    }
}
