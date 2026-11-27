package ac.navro.proxy.data.block;

import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtMap;


public final class NetworkBlockState {
    private final int networkId;
    private final int legacyFullId;
    private final NbtMap state;
    private final NbtMap properties;
    private final String identifier;
    private final Float declaredSlipperiness;
    private final BedrockBlockProperties.Properties bedrockProperties;

    NetworkBlockState(int networkId, int legacyFullId, NbtMap state) {
        this(networkId, legacyFullId, state, null);
    }

    NetworkBlockState(int networkId, int legacyFullId, NbtMap state, Float declaredSlipperiness) {
        this(networkId, legacyFullId, state, declaredSlipperiness, null);
    }

    NetworkBlockState(int networkId, int legacyFullId, NbtMap state, Float declaredSlipperiness,
                      BedrockBlockProperties.Properties bedrockProperties) {
        this.networkId = networkId;
        this.legacyFullId = legacyFullId;
        this.state = ItemUtil.copyTag(state);
        this.properties = this.state == null ? NbtMap.EMPTY : this.state.getCompound("states");
        this.identifier = this.state == null ? "" : this.state.getString("name");
        this.declaredSlipperiness = declaredSlipperiness;
        this.bedrockProperties = bedrockProperties;
    }
    public Float declaredSlipperiness() { return declaredSlipperiness; }
    public BedrockBlockProperties.Properties bedrockProperties() { return bedrockProperties; }
    public static NetworkBlockState legacy(int legacyFullId) { return new NetworkBlockState(Integer.MIN_VALUE, legacyFullId, null); }
    public int networkId() { return networkId; }
    public int legacyFullId() { return legacyFullId; }
    public int blockId() { return legacyFullId < 0 ? 0 : legacyFullId >> BlockStateEncoding.DATA_BITS; }
    public int blockData() { return legacyFullId < 0 ? 0 : legacyFullId & BlockStateEncoding.DATA_MASK; }
    public String identifier() { return identifier; }
    public boolean is(String identifier) { return identifier != null && identifier.equals(this.identifier); }
    public boolean identifierContains(String value) { return value != null && identifier.contains(value); }
    public NbtMap stateTag() { return ItemUtil.copyTag(state); }
    public boolean hasProperty(String name) { return properties.containsKey(name); }
    public int intProperty(String name, int fallback) {
        Object value = properties.get(name);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }
    public boolean booleanProperty(String name, boolean fallback) {
        Object value = properties.get(name);
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string)) return true;
            if ("false".equalsIgnoreCase(string)) return false;
        }
        return fallback;
    }
    public String stringProperty(String name, String fallback) {
        Object value = properties.get(name);
        if (value instanceof String string) return string;
        if (value instanceof Number number) return String.valueOf(number);
        return fallback;
    }
}
