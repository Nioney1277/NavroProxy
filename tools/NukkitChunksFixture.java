import cn.nukkit.level.util.BitArrayVersion;
import cn.nukkit.utils.BinaryStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;


public class NukkitChunksFixture {
    public static void main(String[] args) throws Exception {
        int[] bitWidths = {0, 1, 2, 3, 4, 5, 6, 8, 16};
        try (var out = new DataOutputStream(new FileOutputStream(args[0]))) {
            out.writeInt(0x4E434831);
            out.writeInt(bitWidths.length * 2);
            for (int version : new int[]{8, 9}) for (int bits : bitWidths) {
                BinaryStream stream = new BinaryStream();
                stream.putByte((byte) version);
                stream.putByte((byte) 2);
                if (version >= 9) stream.putByte((byte) -5);
                int[][] expected = new int[2][4096];
                for (int layer = 0; layer < 2; layer++) {
                    var array = BitArrayVersion.get(bits, true).createPalette(4096);
                    int paletteSize = bits == 0 ? 1 : Math.min(1 << bits, 127);
                    int[] palette = new int[paletteSize];
                    for (int i = 0; i < paletteSize; i++)
                        palette[i] = i % 3 == 0 ? Integer.MIN_VALUE + i : 200 + i + layer * 500;
                    for (int i = 0; i < 4096; i++) {
                        int index = (i * 17 + (i >>> 4) + layer) % paletteSize;
                        array.set(i, index);
                        expected[layer][i] = palette[index];
                    }
                    stream.putByte((byte) ((bits << 1) | 1));
                    if (bits != 0) {
                        for (int word : array.getWords()) stream.putLInt(word);
                        stream.putVarInt(paletteSize);
                    }
                    for (int id : palette) stream.putVarInt(id);
                }
                byte[] payload = stream.getBuffer();
                out.writeInt(version); out.writeInt(bits);
                out.writeInt(payload.length); out.write(payload);
                for (int[] layer : expected) for (int id : layer) out.writeInt(id);
            }
        }
    }
}
