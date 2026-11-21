import cn.nukkit.block.*;
import cn.nukkit.block.custom.CustomBlockManager;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitBlockPropertiesExporter {
    private record Properties(int blockId, long hardness, int flags) {
        static Properties of(Block block) {
            int flags = (block instanceof BlockItemFrame ? 1 : 0)
                    | (block instanceof BlockDoor ? 2 : 0)
                    | (block instanceof BlockDoor door && door.isTop() ? 4 : 0);
            return new Properties(block.getId(), Double.doubleToRawLongBits(block.getHardness()), flags);
        }
        void write(DataOutputStream out) throws IOException {
            out.writeInt(blockId); out.writeLong(hardness); out.writeByte(flags);
        }
    }
    private record Run(int end, Properties properties) { }

    public static void main(String[] args) throws Exception {
        Block.init();

        var field = Block.class.getDeclaredField("statePrototypes");
        field.setAccessible(true);
        Object[] caches = (Object[]) field.get(null);
        long states = 0, runs = 0, hardnessDifferences = 0;
        var audit = new StringBuilder("id\tnative_class\tBlock.hardness[id]\tgetHardness() by metadata\n");
        try (var out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E425031);
            out.writeInt(Block.DATA_BITS);
            out.writeInt(Block.MAX_BLOCK_ID);
            out.writeInt(CustomBlockManager.LOWEST_CUSTOM_BLOCK_ID);
            new Properties(-1, Double.doubleToRawLongBits(new BlockUnknown(-1).getHardness()), 0).write(out);
            for (int id = 0; id < Block.MAX_BLOCK_ID; id++) {
                out.writeLong(Double.doubleToRawLongBits(Block.hardness[id]));
                List<Run> row = new ArrayList<>();
                Properties previous = null;
                for (int data = 0; data < Block.DATA_SIZE; data++) {

                    Block block = Block.get(id, data, null, 0, 0, 0, 0);
                    Properties current = Properties.of(block);
                    if (previous != null && !previous.equals(current)) row.add(new Run(data - 1, previous));
                    if (current.hardness != Double.doubleToRawLongBits(Block.hardness[id])) hardnessDifferences++;
                    previous = current;
                    states++;
                }
                row.add(new Run(Block.DATA_MASK, previous));
                out.writeInt(row.size());
                for (Run run : row) { out.writeShort(run.end); run.properties.write(out); }
                runs += row.size();
                audit.append(id).append('\t').append(Block.list[id] == null ? "BlockUnknown" : Block.list[id].getName())
                        .append('\t').append(Block.hardness[id]).append('\t');
                int start = 0;
                int hardnessStart = 0;
                long lastHardness = row.get(0).properties.hardness;
                for (Run run : row) {
                    if (run.properties.hardness != lastHardness) {
                        audit.append(hardnessStart).append('-').append(start - 1).append('=')
                                .append(Double.longBitsToDouble(lastHardness)).append(';');
                        hardnessStart = start;
                        lastHardness = run.properties.hardness;
                    }
                    start = run.end + 1;
                }
                audit.append(hardnessStart).append('-').append(Block.DATA_MASK).append('=')
                        .append(Double.longBitsToDouble(lastHardness)).append('\n');
                if (caches[id] instanceof Map<?,?> cache) cache.clear();
                if ((id + 1) % 256 == 0) System.out.println("exported IDs " + (id + 1) + "/" + Block.MAX_BLOCK_ID);
            }
        }
        if (args.length > 1) java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), audit);
        System.out.println("states=" + states + " runs=" + runs + " instance/static differences=" + hardnessDifferences);
    }
}
