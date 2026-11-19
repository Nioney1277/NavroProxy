package ac.navro.proxy.data;

import ac.navro.anticheat.util.math.Vec3;


public record FluidState(Type type, float height, int level) {
    public enum Type { WATER, LAVA, EMPTY }
    public static final FluidState EMPTY = new FluidState(Type.EMPTY, 0, 0);

    public int effectiveFlowDecay() { return level >= 8 ? 0 : level; }
    public boolean isFluid() { return type != Type.EMPTY; }
    public Vec3 zeroFlow() { return Vec3.ZERO; }
}
