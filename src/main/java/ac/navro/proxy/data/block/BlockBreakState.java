package ac.navro.proxy.data.block;


public record BlockBreakState(NetworkBlockState networkState, NativeBlockProperties.Properties nativeState) {
    public boolean isAir() {
        return networkState.is("minecraft:air") || networkState.is("minecraft:cave_air")
                || networkState.is("minecraft:void_air") || nativeState.blockId() == 0;
    }
}
