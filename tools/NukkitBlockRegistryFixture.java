import cn.nukkit.GameVersion;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.utils.Hash;
import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;


public class NukkitBlockRegistryFixture {
    public static void main(String[] args) throws Exception {


        var thresholds = new TreeMap<Integer, GameVersion>();
        thresholds.put(843, GameVersion.V1_21_111);
        thresholds.put(944, GameVersion.V1_26_10);
        thresholds.put(974, GameVersion.V1_26_20);
        thresholds.put(1001, GameVersion.V1_26_30);
        thresholds.put(2168, GameVersion.V1_26_40);
        thresholds.put(2192, GameVersion.V1_26_50);
        int[] protocols = {924, 944, 975, 1001, 2168, 2169, 2192, 2193, 2225};
        try (var out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E425332);
            out.writeInt(cn.nukkit.block.Block.DATA_BITS);
            out.writeInt(protocols.length);
            for (int protocol : protocols) {
                GameVersion version = GameVersion.byProtocol(protocol, false);
                int paletteProtocol = thresholds.floorEntry(version.getProtocol()).getValue().getProtocol();
                String path = "runtime_block_states_" + paletteProtocol + ".dat";
                var raw = NukkitBlockRegistryFixture.class.getClassLoader().getResourceAsStream(path);
                var list = (ListTag<?>) NBTIO.readTag(new GZIPInputStream(raw), ByteOrder.BIG_ENDIAN, false);
                out.writeInt(protocol); out.writeInt(paletteProtocol); out.writeInt(list.size());
                for (var tag : list.getAll()) {
                    CompoundTag source = (CompoundTag) tag;
                    CompoundTag state = source.clone().remove("id").remove("data").remove("runtimeId").remove("stateOverload");
                    out.writeInt(source.getInt("runtimeId"));
                    out.writeInt(source.getInt("id") << cn.nukkit.block.Block.DATA_BITS | source.getShort("data") & cn.nukkit.block.Block.DATA_MASK);
                    out.writeInt(Hash.hashBlock(state));
                }
                System.out.println(protocol + " -> " + paletteProtocol + ", states=" + list.size());
            }
        }
    }
}
