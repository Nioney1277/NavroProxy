package ac.navro.proxy.data.block;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.NativeIds;


import ac.navro.anticheat.util.math.Mutable;
import ac.navro.proxy.prediction.StickyBlockPhysics;

import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.data.block.*;
import org.cloudburstmc.math.vector.Vector3i;

public final class NavroBlockState {
 private final NetworkBlockState networkState;
 public NavroBlockState(NetworkBlockState state){networkState=java.util.Objects.requireNonNull(state);}
 public int nativeId(){return NativeBlockProperties.forState(networkState).blockId();}
 public NetworkBlockState getNetworkState(){return networkState;}
 public boolean isWater(){return isWater(nativeId());}
 public static boolean isWater(int id){return id==NativeIds.BlockID.WATER||id==NativeIds.BlockID.STILL_WATER;}
    public boolean hasClimbableProperty() {
        if (isScaffolding()) {
            return false;
        }
        final int id = nativeId();
        return id == NativeIds.BlockID.LADDER
                || id == NativeIds.BlockID.VINE
                || id == NativeIds.BlockID.VINES
                || id == NativeIds.BlockID.WEEPING_VINES
                || id == NativeIds.BlockID.TWISTING_VINES
                || id == NativeIds.BlockID.CAVE_VINES
                || id == NativeIds.BlockID.CAVE_VINES_BODY_WITH_BERRIES
                || id == NativeIds.BlockID.CAVE_VINES_HEAD_WITH_BERRIES
                || networkState.is("minecraft:ladder")
                || networkState.is("minecraft:vine")
                || networkState.is("minecraft:vines")
                || networkState.is("minecraft:weeping_vines")
                || networkState.is("minecraft:weeping_vines_plant")
                || networkState.is("minecraft:twisting_vines")
                || networkState.is("minecraft:twisting_vines_plant")
                || networkState.is("minecraft:cave_vines")
                || networkState.is("minecraft:cave_vines_body_with_berries")
                || networkState.is("minecraft:cave_vines_head_with_berries");
    }
    public boolean hasClimbHaltProperty() {
        return hasClimbableProperty();
    }
    public boolean isScaffolding() {
        return nativeId() == NativeIds.BlockID.SCAFFOLDING
                || networkState.is("minecraft:scaffolding");
    }
    public boolean isAir() {
        return this.networkState.is("minecraft:air")
                || this.networkState.is("minecraft:cave_air")
                || this.networkState.is("minecraft:void_air")
                || this.networkState.legacyFullId() >= 0 && nativeId() == NativeIds.BlockID.AIR;
    }
    public void onSteppedOn(final PredictionContext player, final org.cloudburstmc.math.vector.Vector3i vector3i) {
        if (nativeId()==NativeIds.BlockID.HONEY_BLOCK || nativeId()==NativeIds.BlockID.SLIME_BLOCK) StickyBlockPhysics.standOn(player);
    }
    public void entityInside(final PredictionContext player, Mutable pos) {
        int id = nativeId();
        if(id==NativeIds.BlockID.HONEY_BLOCK){
            if(StickyBlockPhysics.insideHoney(player.velocity,player.position,player.boundingBox.maxX-player.boundingBox.minX,Vector3i.from(pos.getX(),pos.getY(),pos.getZ())))player.fallDistance=0;
            return;
        }
        if (id == NativeIds.BlockID.POWDER_SNOW && player.boundingBox.offset(0, 1.0E-3F, 0).contains(pos.getX(), pos.getY(), pos.getZ())) {
            return;
        }

        Vec3 movementMultiplier = Vec3.ZERO;
        if (id == NativeIds.BlockID.SWEET_BERRY_BUSH) {
            movementMultiplier = new Vec3(0.8F, 0.75F, 0.8F);
        } else if (id == NativeIds.BlockID.POWDER_SNOW && player.position.y < pos.getY() + 1 - 1.0E-5f) {
            movementMultiplier = new Vec3(0.9F, 1.5F, 0.9F);
        } else if (id == NativeIds.BlockID.COBWEB) {
            movementMultiplier = new Vec3(0.25F, 0.05F, 0.25F);
            if (player.hasEffect(NativeIds.Effect.WEAVING)) {
                movementMultiplier = new Vec3(0.5F, 0.25F, 0.5F);
            }
        }

        if (movementMultiplier.equals(Vec3.ZERO)) {
            return;
        }

        final boolean xLargerThanThreshold = Math.abs(player.stuckSpeedMultiplier.x) >= 1.0E-7;
        final boolean yLargerThanThreshold = Math.abs(player.stuckSpeedMultiplier.y) >= 1.0E-7;
        final boolean zLargerThanThreshold = Math.abs(player.stuckSpeedMultiplier.z) >= 1.0E-7;
        if (xLargerThanThreshold || yLargerThanThreshold || zLargerThanThreshold) {
            player.stuckSpeedMultiplier.x = Math.min(player.stuckSpeedMultiplier.x, movementMultiplier.x);
            player.stuckSpeedMultiplier.y = Math.min(player.stuckSpeedMultiplier.y, movementMultiplier.y);
            player.stuckSpeedMultiplier.z = Math.min(player.stuckSpeedMultiplier.z, movementMultiplier.z);
        } else {
            player.stuckSpeedMultiplier = movementMultiplier;
        }
    }
    public boolean isHoneyBlock() {
        return nativeId() == NativeIds.BlockID.HONEY_BLOCK || networkState.is("minecraft:honey_block");
    }
    public boolean isSlimeBlock() {
        return nativeId() == NativeIds.BlockID.SLIME_BLOCK || networkState.is("minecraft:slime_block");
    }
    public float getJumpFactor() {
        return isHoneyBlock() ? 0.6F : 1;
    }
    public float getFriction() {
        if (networkState.declaredSlipperiness() != null) return networkState.declaredSlipperiness();
        if (networkState.bedrockProperties() != null) return networkState.bedrockProperties().friction();
        int id = nativeId();
        if (id == NativeIds.BlockID.ICE || id == NativeIds.BlockID.PACKED_ICE || id == NativeIds.BlockID.FROSTED_ICE) {
            return 0.98F;
        } else if (isSlimeBlock() || isHoneyBlock()) {
            return 0.8F;
        } else if (id == NativeIds.BlockID.BLUE_ICE) {
            return 0.989F;
        }

        return 0.6F;
    }
    public boolean isBed() {
        return nativeId() == NativeIds.BlockID.BED_BLOCK || networkState.identifierContains("_bed");
    }
    public float getBlockBounciness() {
        if (isSlimeBlock()) {
            return 1;
        } else if (isBed()) {
            return 0.75F;
        }

        return 0;
    }
}
