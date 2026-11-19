package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WireVec2;


public final class ProtocolInputMath {
    private ProtocolInputMath() {}

    public static Vec3 normalize(WireVec2 rawMove, WireVec2 motion, boolean inventoryAction) {
        if (inventoryAction || rawMove == null || motion == null
                || (motion.x() == 0 && motion.y() == 0)) return Vec3.ZERO.clone();
        float x = MathUtil.clamp(rawMove.x(), -1, 1);
        float z = MathUtil.clamp(rawMove.y(), -1, 1);
        Vec3 input = new Vec3(x, 0, z);
        if (MathUtil.sign(input.x) == input.x && MathUtil.sign(input.z) == input.z
                && input.x != 0 && input.z != 0) return input.multiply(0.70710677F);
        float length = input.horizontalLength();
        return length >= 1 ? new Vec3(input.x / length, 0, input.z / length) : input;
    }

    public static Vec3 scale(Vec3 raw, boolean sprinting, boolean swimming,
                              boolean inventoryAction, boolean sneaking,
                              boolean crawling, boolean gliding, int slowdownTicks,
                              float swiftSneakModifier, float itemSlowdown) {
        Vec3 input = raw;
        if (sprinting && !swimming && !inventoryAction && input.z <= 0) {
            float forward = 1.0F;
            double length = Math.sqrt((double) input.x * input.x + forward * forward);
            input = new Vec3((float) (input.x / length), input.y, (float) (forward / length));
        }
        input = input.multiply(0.98F);
        if ((sneaking || crawling || gliding) && !swimming) {
            int ticks = slowdownTicks + 1;
            if (ticks > 0) {
                float multiplier = 0.3F + (ticks > 2 ? swiftSneakModifier : 0);
                input = input.multiply(MathUtil.clamp(multiplier, 0, 1));
            }
        }
        return itemSlowdown == 1.0F ? input : input.multiply(itemSlowdown);
    }
}
