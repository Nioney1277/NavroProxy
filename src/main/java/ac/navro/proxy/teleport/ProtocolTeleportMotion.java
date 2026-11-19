package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;

import java.util.ArrayList;
import java.util.List;


public record ProtocolTeleportMotion(long inputTick, float x, float y, float z,
                                     boolean generatedHorizontalImpulse) {
    public static ProtocolTeleportMotion fromConfirmation(long confirmationTick, Vec3 delta,
                                                          Vec3 expectedHorizontalImpulse) {
        if (delta == null || !Float.isFinite(delta.x) || !Float.isFinite(delta.y)
                || !Float.isFinite(delta.z) || delta.x == 0 && delta.y == 0 && delta.z == 0) return null;
        boolean generated = expectedHorizontalImpulse != null
                && Float.isFinite(expectedHorizontalImpulse.x) && Float.isFinite(expectedHorizontalImpulse.z)
                && (expectedHorizontalImpulse.x != 0 || expectedHorizontalImpulse.z != 0)
                && delta.x == expectedHorizontalImpulse.x && delta.z == expectedHorizontalImpulse.z;
        return new ProtocolTeleportMotion(confirmationTick + 1, delta.x, delta.y, delta.z, generated);
    }

    public List<Vec3> velocitiesForTick(long tick) {
        if (tick != inputTick) return List.of();
        List<Vec3> result = new ArrayList<>(2);
        if (y != 0) result.add(new Vec3(0, y, 0));
        if (generatedHorizontalImpulse) result.add(new Vec3(x, y, z));
        return List.copyOf(result);
    }

    public static Vec3 sprintJumpImpulse(float yaw) {
        float radians = yaw * MathUtil.DEGREE_TO_RAD;
        return new Vec3(-BdsTrigMath.sin(radians) * 0.2F, 0,
                BdsTrigMath.cos(radians) * 0.2F);
    }
}
