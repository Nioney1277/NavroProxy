package ac.navro.proxy.data.block;


public final class BlockStateEncoding {
    public static final int DATA_BITS = 13;
    public static final int DATA_MASK = (1 << DATA_BITS) - 1;

    private BlockStateEncoding() { }

    public static int fullId(int blockId, int blockData) {
        return blockId << DATA_BITS | blockData & DATA_MASK;
    }
}
