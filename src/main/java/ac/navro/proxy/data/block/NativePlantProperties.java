package ac.navro.proxy.data.block;


public final class NativePlantProperties {
    public static final int BAMBOO = 418;
    public static final int POINTED_DRIPSTONE = 563;

    private NativePlantProperties() { }

    public static boolean bambooThick(NetworkBlockState state) {
        return state.blockId() == BAMBOO && (state.blockData() & 0x1) == 0x1;
    }
    public static boolean dripstoneHanging(NetworkBlockState state) {
        return state.blockId() == POINTED_DRIPSTONE && (state.blockData() & 0x1) != 0;
    }
    public static String dripstoneThickness(NetworkBlockState state) {
        if (state.blockId() != POINTED_DRIPSTONE) return "tip";
        return switch ((state.blockData() >>> 1) & 7) {
            case 0 -> "tip";
            case 1 -> "frustum";
            case 2 -> "middle";
            case 3 -> "base";
            case 4 -> "merge";
            default -> throw new InvalidBlockPropertyMetaException(state.blockData(), state.blockData() & 14);
        };
    }


    public static final class InvalidBlockPropertyMetaException extends IllegalArgumentException {
        private final int currentMeta, invalidMeta;
        public InvalidBlockPropertyMetaException(int currentMeta, int invalidMeta) {
            super("Property: dripstone_thickness. Current Meta: " + currentMeta + ", Invalid Meta: " + invalidMeta);
            this.currentMeta = currentMeta;
            this.invalidMeta = invalidMeta;
        }
        public Number getCurrentMeta() { return currentMeta; }
        public Number getInvalidMeta() { return invalidMeta; }
    }
}
