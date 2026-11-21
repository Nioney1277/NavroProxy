import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.util.block.BlockUtil;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.types.GameType;
import sun.misc.Unsafe;
import java.io.*;
import java.security.*;
import java.util.*;


public final class NavroBlockHardnessFixture {
    private static final class State extends NavroBlockState {
        Block current;
        State() { super(null, new BlockVector3(), 0); }
        @Override public Block getBlock() { return current; }
    }
    private static int flags(Block block) {
        return (block instanceof BlockItemFrame ? 1 : 0) | (block instanceof BlockDoor ? 2 : 0)
                | (block instanceof BlockDoor door && door.isTop() ? 4 : 0);
    }
    private static void sample(DataOutputStream out, State state, NavroPlayer player, GameType[] modes,
                               Block block) throws IOException {
        state.current = block;
        out.writeInt(block.getId()); out.writeLong(Double.doubleToRawLongBits(block.getHardness()));
        out.writeByte(flags(block));
        int accepted = 0;
        for (int mode = 0; mode < modes.length; mode++) {
            player.gameType = modes[mode];
            if (BlockUtil.determineCanBreak(player, state)) accepted |= 1 << mode;
        }
        out.writeByte(accepted);
    }
    public static void main(String[] args) throws Exception {
        Block.init();
        var field = Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        NavroPlayer player = (NavroPlayer) ((Unsafe) field.get(null)).allocateInstance(NavroPlayer.class);
        field = Block.class.getDeclaredField("statePrototypes");field.setAccessible(true);
        Object[] caches = (Object[]) field.get(null);
        State state = new State();
        GameType[] modes = Arrays.copyOf(GameType.values(), GameType.values().length + 1);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var out = new DataOutputStream(new FileOutputStream(args[0]));
             var hashed = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            out.writeInt(0x4E484631);out.writeInt(Block.DATA_BITS);out.writeInt(modes.length);out.writeInt(Block.MAX_BLOCK_ID);
            for (int id = 0; id < Block.MAX_BLOCK_ID; id++) {
                out.writeLong(Double.doubleToRawLongBits(Block.hardness[id]));
                for (int data = 0; data < Block.DATA_SIZE; data++) {
                    hashed.writeInt(data);
                    sample(hashed, state, player, modes, Block.get(id, data, null, 0, 0, 0, 0));
                }
                out.write(digest.digest());
                if (caches[id] instanceof Map<?,?> cache) cache.clear();
                if ((id + 1) % 256 == 0) System.out.println("verified IDs " + (id + 1) + "/" + Block.MAX_BLOCK_ID);
            }
            int[] ids = {Integer.MIN_VALUE, -1, 2048, 8191, 9999};
            int[] data = {0, 1, 7, 63, 4096, 8191};
            out.writeInt(ids.length * data.length);
            for (int id : ids) for (int meta : data) {
                out.writeInt(id);out.writeInt(meta);
                sample(out, state, player, modes, Block.get(id, meta, null, 0, 0, 0, 0));
            }
            double[] hardness = {-1, Math.nextDown(-1D), Math.nextUp(-1D), -1.0000001, -0D, 0, 10,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            out.writeInt(hardness.length);
            for (double value : hardness) {
                out.writeDouble(value);
                sample(out, state, player, modes, new BlockStone() {
                    @Override public double getHardness() { return value; }
                });
            }
        }
        System.out.println("16,777,216 native states; all 7 game types plus null; 30 unknown-ID and 10 floating-point cases");
    }
}
