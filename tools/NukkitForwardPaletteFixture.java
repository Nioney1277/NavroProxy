import cn.nukkit.GameVersion;
import cn.nukkit.block.Block;
import cn.nukkit.level.BlockPalette;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitForwardPaletteFixture {
    public static void main(String[] args) throws Exception {
        int[] versions = {844, 944, 975, 1001, 2168, 2193};
        int[] metas = {0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 63, 64, 127, 128, 255, 256, 511, 1023, 2047, 4095, 4096, 8191};
        try (var out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E465031); out.writeInt(Block.DATA_BITS); out.writeInt(versions.length);
            for (int protocol : versions) {
                var palette = new BlockPalette(GameVersion.byProtocol(protocol, false));
                var keys = new TreeSet<Integer>(palette.getLegacyToRuntimeIdMap().keySet());
                var ids = new HashSet<Integer>();
                for (int key : keys) ids.add(key >> Block.DATA_BITS);
                ids.add(-1); ids.add(2048); ids.add(100000);
                for (int id : ids) for (int meta : metas) keys.add(id << Block.DATA_BITS | meta);
                out.writeInt(protocol); out.writeInt(keys.size());
                for (int legacy : keys) {
                    out.writeInt(legacy);
                    out.writeInt(palette.getRuntimeId(legacy >> Block.DATA_BITS, legacy & Block.DATA_MASK));
                    out.writeInt(palette.getHashId(legacy >> Block.DATA_BITS, legacy & Block.DATA_MASK));
                }
                System.out.println(protocol+": native lookups="+keys.size());
            }
        }
    }
}
