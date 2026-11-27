package ac.navro.proxy.data.block;


public record BlockBreakState(NetworkBlockState networkState, NativeBlockProperties.Properties nativeState) {
    public double hardness() {
        var physical = networkState.bedrockProperties();
        return physical == null ? nativeState.hardness() : physical.hardness();
    }

    public boolean isAir() {
        return networkState.is("minecraft:air") || networkState.is("minecraft:cave_air")
                || networkState.is("minecraft:void_air")
                || networkState.bedrockProperties() == null && nativeState.blockId() == 0;
    }
}
