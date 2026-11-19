package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.prediction.fluid.FluidMovement;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.BdsTrigMath;
import ac.navro.anticheat.util.math.Vec3;


public final class RiptidePhysics {
    private static final float DEGREES_TO_RADIANS = 0.017453292F;
    private static final float NORMALIZATION_EPSILON = 0.0001F;

    private RiptidePhysics() { }

    public static int level(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData item) {
        return item != null && !item.isNull() && item.getDefinition()!=null && "minecraft:trident".equals(item.getDefinition().getIdentifier())
                ? ac.navro.proxy.compensated.CompensatedInventory.getEnchantmentLevel(item, NativeIds.Enchantment.ID_TRIDENT_RIPTIDE) : 0;
    }


    public static void prepare(PredictionContext player) {
        player.thisTickRiptideLevel = player.dirtyRiptide ? level(player.riptideItem) : 0;
        player.dirtyRiptide = false;
        player.riptideItem = org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR;
        player.thisTickSpinAttack = player.thisTickRiptideLevel > 0;
        player.thisTickOnGroundSpinAttack = player.thisTickSpinAttack && player.onGround;
        if (!player.thisTickSpinAttack) return;
        player.autoSpinAttackTicks = 20;
        player.getFlagTracker().set("SPIN_ATTACK", true);
        FluidMovement.updatePose(player);
    }

    public static Vec3 applyImpulse(PredictionContext player, Vec3 velocity) {
        if (player.thisTickRiptideLevel <= 0) return velocity;
        return impulse(velocity, player.pitch, player.yaw, player.thisTickRiptideLevel,
                player.thisTickOnGroundSpinAttack, player.riptideWasInWater,
                player.fluidPrediction.headInWater);
    }

    public static Vec3 impulse(Vec3 velocity, float pitch, float yaw, int level,
                               boolean onGround, boolean wasInWater, boolean headInWater) {
        final float pitchRadians = pitch * DEGREES_TO_RADIANS;
        final float yawRadians = yaw * DEGREES_TO_RADIANS;
        final float negativeSinYaw = -BdsTrigMath.sin(yawRadians);
        final float cosPitch = BdsTrigMath.cos(pitchRadians);
        float x = negativeSinYaw * cosPitch;
        final float sinPitch = BdsTrigMath.sin(pitchRadians);
        float z = cosPitch * BdsTrigMath.cos(yawRadians);
        final float xSquared = x * x;
        final float ySquared = sinPitch * sinPitch;
        final float xySquared = ySquared + xSquared;
        final float zSquared = z * z;
        final float length = StickyBlockPhysics.sqrtf(zSquared + xySquared);
        float y;
        if (length < NORMALIZATION_EPSILON) {
            x = y = z = 0.0F;
        } else {
            x = x / length;
            y = -sinPitch;
            y = y / length;
            z = z / length;
        }
        float magnitude = (float) level;
        magnitude = magnitude + 1.0F;
        magnitude = magnitude * 0.25F;
        magnitude = magnitude * 3.0F;
        x = x * magnitude;
        y = y * magnitude;
        z = z * magnitude;
        if (onGround) {
            if (!(wasInWater && !headInWater)) {
                y = y + 0.08F;
            } else {
                y = y / 0.8F;
                y = y * 0.98F;
            }
        }
        return new Vec3(x + velocity.x, y + velocity.y, z + velocity.z);
    }

    public record Snapshot(boolean pending, boolean stopRequested, int ticks, org.cloudburstmc.protocol.bedrock.data.inventory.ItemData item) { }

    public static Snapshot snapshot(PredictionContext player) {
        return new Snapshot(player.dirtyRiptide, player.dirtySpinStop,
                player.autoSpinAttackTicks, ac.navro.proxy.util.ItemUtil.copy(player.riptideItem));
    }

    public static void restore(PredictionContext player, Snapshot state) {
        player.dirtyRiptide = state != null && state.pending();
        player.dirtySpinStop = state != null && state.stopRequested();
        player.autoSpinAttackTicks = state == null ? 0 : state.ticks();
        player.riptideItem = state == null ? org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR : ac.navro.proxy.util.ItemUtil.copy(state.item());
        player.thisTickRiptideLevel = 0;
        player.thisTickSpinAttack = player.thisTickOnGroundSpinAttack = false;
        player.getFlagTracker().set("SPIN_ATTACK", player.autoSpinAttackTicks > 0);
        FluidMovement.updatePose(player);
    }
}
