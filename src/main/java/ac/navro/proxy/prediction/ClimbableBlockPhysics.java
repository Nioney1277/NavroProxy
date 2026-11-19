package ac.navro.proxy.prediction;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.proxy.collision.ScaffoldingCollision;
import ac.navro.proxy.compensated.world.CompensatedWorldImpl;
import ac.navro.proxy.data.block.NavroBlockState;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;


public final class ClimbableBlockPhysics {
    private ClimbableBlockPhysics() { }
    private static int floor(float value){return (int)Math.floor(value);}

    public static NavroBlockState blockAtFeet(PredictionContext player, CompensatedWorldImpl world) {
        final int x = floor(player.position.x);
        final int y = floor(player.boundingBox.minY);
        final int z = floor(player.position.z);
        return world.isChunkLoaded(x, z) ? world.getBlockState(x, y, z, 0) : null;
    }

    public static boolean isClimbing(PredictionContext player) {
        return isClimbing(player, player.compensatedWorld);
    }

    static boolean isClimbing(PredictionContext player, CompensatedWorldImpl world) {
        if (player.getFlagTracker().has("WALLCLIMBING")) return true;
        if (!player.getFlagTracker().has("CAN_CLIMB")) return false;
        final NavroBlockState feet = blockAtFeet(player, world);
        return feet != null && feet.hasClimbableProperty();
    }

    public static boolean hasClimbHaltAtFeet(PredictionContext player) {
        final NavroBlockState feet = blockAtFeet(player, player.compensatedWorld);
        return feet != null && feet.hasClimbHaltProperty();
    }

    public static Vec3 clampDownwardMovement(PredictionContext player, Vec3 velocity) {
        final NavroBlockState feet = blockAtFeet(player, player.compensatedWorld);
        final boolean powderSnow = feet != null && isPowderSnow(feet)
                && player.canStandOnSnow();
        return clampDownwardMovement(velocity, isClimbing(player), powderSnow,
                player.getFlagTracker().has("SNEAKING"),
                feet != null && feet.hasClimbHaltProperty());
    }

    static Vec3 clampDownwardMovement(Vec3 velocity, boolean climbing, boolean powderSnow,
                                     boolean sneaking, boolean haltAtFeet) {
        if (!climbing && !powderSnow) return velocity;
        float y = Math.max(velocity.y, -0.2F);
        if (y < 0 && sneaking && haltAtFeet) y = 0;
        return new Vec3(velocity.x, y, velocity.z);
    }


    public static void applyAutoClimb(PredictionContext player) {
        if (player.horizontalCollision && isClimbing(player)) player.velocity.y = 0.2F;
    }

    public static boolean isJumping(PredictionContext player) {
        return !player.getFlagTracker().isFlying()
                && (player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.JUMPING)
                || player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.AUTO_JUMPING_IN_WATER)
                || player.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.ASCEND_BLOCK));
    }

    static boolean isAscending(PredictionContext player) {
        return isJumping(player)
                && !player.getFlagTracker().has(ScaffoldingCollision.DESCEND_THROUGH_BLOCK_FLAG)
                && (player.getFlagTracker().has("IN_SCAFFOLDING")
                || player.getFlagTracker().has("IN_ASCENDABLE_BLOCK"));
    }


    public static Vec3 applyBlockJump(PredictionContext player, Vec3 velocity, boolean climbing) {
        if (!isJumping(player)) return null;
        if (isAscending(player)) return new Vec3(velocity.x, 0.15F, velocity.z);
        if (climbing) return new Vec3(velocity.x, 0.2F, velocity.z);
        return null;
    }

    public static void recordAcceptedJump(PredictionContext player) {
        if (isAscending(player)) player.climbJumpDelay = 10;
    }

    public static void refreshFlags(PredictionContext player) {
        refreshFlags(player, player.compensatedWorld, player.canStandOnSnow());
    }

    static void refreshFlags(PredictionContext player, CompensatedWorldImpl world, boolean canStandOnSnow) {
        final Box box = player.boundingBox;
        final int inY = floor(box.minY);
        final int overY = floor(box.minY - 1.0F);
        player.getFlagTracker().set("IN_SCAFFOLDING",
                anyBlock(world, box, inY, false, canStandOnSnow));
        player.getFlagTracker().set("OVER_SCAFFOLDING",
                anyBlock(world, box, overY, false, canStandOnSnow));
        player.getFlagTracker().set("IN_ASCENDABLE_BLOCK",
                anyBlock(world, box, inY, true, canStandOnSnow));
        player.getFlagTracker().set("OVER_DESCENDABLE_BLOCK",
                anyBlock(world, box, overY, true, canStandOnSnow));
    }

    private static boolean anyBlock(CompensatedWorldImpl world, Box box, int y,
                                    boolean ascendable, boolean canStandOnSnow) {
        for (int x = floor(box.minX); x <= floor(box.maxX); x++) {
            for (int z = floor(box.minZ); z <= floor(box.maxZ); z++) {
                if (!world.isChunkLoaded(x, z)) continue;
                final NavroBlockState state = world.getBlockState(x, y, z, 0);
                if (ascendable && isPowderSnow(state)) {
                    if (canStandOnSnow) return true;
                    continue;
                }
                if (!state.isScaffolding()) continue;
                if (!ascendable) return true;
                final NavroBlockState below = world.getBlockState(x, y - 1, z, 0);
                if (!below.isAir() && !below.isWater()
                        && !NavroBlockState.isWater(below.nativeId())
                        && !below.getNetworkState().is("minecraft:water")
                        && !below.getNetworkState().is("minecraft:flowing_water")) return true;
            }
        }
        return false;
    }

    private static boolean isPowderSnow(NavroBlockState state) {
        return state.nativeId() == NativeIds.BlockID.POWDER_SNOW
                || state.getNetworkState().is("minecraft:powder_snow");
    }
}
