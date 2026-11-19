package ac.navro.proxy.collision.block;

import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.util.NbtNumber;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import java.util.ArrayList;
import java.util.List;


public final class PistonArmBlock {
    private static final float ARM_MIN = 0.3125F;
    private static final float ARM_MAX = 0.6875F;
    private PistonArmBlock() { }

    public static CollisionBoxes _getCollisions(final Direction facingDir) {
        final Direction face = facingDir == null ? Direction.UP : facingDir;
        return switch (face) {
            case NORTH -> new CollisionBoxes(
                    new Box(ARM_MIN, ARM_MIN, 1.0F, ARM_MAX, ARM_MAX, 1.25F),
                    new Box(ARM_MIN, ARM_MIN, 0.25F, ARM_MAX, ARM_MAX, 1.0F),
                    new Box(0, 0, 0, 1, 1, 0.25F));
            case SOUTH -> new CollisionBoxes(
                    new Box(ARM_MIN, ARM_MIN, -0.25F, ARM_MAX, ARM_MAX, 0.0F),
                    new Box(ARM_MIN, ARM_MIN, 0.0F, ARM_MAX, ARM_MAX, 0.75F),
                    new Box(0, 0, 0.75F, 1, 1, 1));
            case WEST -> new CollisionBoxes(
                    new Box(1.0F, ARM_MIN, ARM_MIN, 1.25F, ARM_MAX, ARM_MAX),
                    new Box(0.25F, ARM_MIN, ARM_MIN, 1.0F, ARM_MAX, ARM_MAX),
                    new Box(0, 0, 0, 0.25F, 1, 1));
            case EAST -> new CollisionBoxes(
                    new Box(-0.25F, ARM_MIN, ARM_MIN, 0.0F, ARM_MAX, ARM_MAX),
                    new Box(0.0F, ARM_MIN, ARM_MIN, 0.75F, ARM_MAX, ARM_MAX),
                    new Box(0.75F, 0, 0, 1, 1, 1));
            case DOWN -> new CollisionBoxes(
                    new Box(ARM_MIN, 1.0F, ARM_MIN, ARM_MAX, 1.25F, ARM_MAX),
                    new Box(ARM_MIN, 0.25F, ARM_MIN, ARM_MAX, 1.0F, ARM_MAX),
                    new Box(0, 0, 0, 1, 0.25F, 1));
            default -> new CollisionBoxes(
                    new Box(ARM_MIN, -0.25F, ARM_MIN, ARM_MAX, 0.0F, ARM_MAX),
                    new Box(ARM_MIN, 0.0F, ARM_MIN, ARM_MAX, 0.75F, ARM_MAX),
                    new Box(0, 0.75F, 0, 1, 1, 1));
        };
    }

    public static List<Box> getCollisionShape(final CompensatedWorld world,
                                              final Vector3i pos,
                                              final NetworkBlockState state,
                                              final Direction face) {
        final List<Box> staticShape = _getCollisions(face).asList();
        if (world == null || pos == null || face == null) return staticShape;
        final NbtMap tag = world.getBlockEntityTag(
                pos.getX() - face.getXOffset(), pos.getY() - face.getYOffset(),
                pos.getZ() - face.getZOffset());
        if (tag == null || !tag.containsKey("State")) return staticShape;
        final int pistonState = NbtNumber.getByte(tag, "State");
        if (pistonState == 0) return List.of();
        if (pistonState != 1 && pistonState != 3) return staticShape;
        final String progressKey = pistonState == 1 ? "LastProgress" : "Progress";
        if (!tag.containsKey(progressKey)) return staticShape;
        final float progress = NbtNumber.getFloat(tag, progressKey);
        return Float.isFinite(progress)
                ? animatedLocalCollisionShape(face, Math.max(0, Math.min(1, progress)))
                : staticShape;
    }

    private static List<Box> animatedLocalCollisionShape(final Direction face,
                                                         final float progress) {
        final CollisionBoxes base = _getCollisions(face);
        final ArrayList<Box> result = new ArrayList<>(3);
        if (base.armBaseAABB() != null && base.armBaseAABB().isValid()) {
            result.add(base.armBaseAABB());
        }

        final Box arm = animatedArm(base.armAABB(), face, progress);
        if (arm != null && arm.isValid()) {
            result.add(arm);
        }

        final Box front = base.frontAABB();
        if (front != null && front.isValid()) {
            final float translate = progress - 1.0F;
            result.add(front.offset(
                    translate * face.getXOffset(),
                    translate * face.getYOffset(),
                    translate * face.getZOffset()));
        }
        return List.copyOf(result);
    }

    private static Box animatedArm(final Box arm,
                                   final Direction face,
                                   final float progress) {
        if (arm == null || !arm.isValid()) {
            return Box.invalid();
        }
        final float extension = Math.max(0.0F, progress - 0.25F);
        if (extension <= 1.0E-6F) {
            return Box.invalid();
        }
        return switch (face) {
            case EAST -> new Box(arm.minX, arm.minY, arm.minZ,
                    arm.minX + extension, arm.maxY, arm.maxZ);
            case WEST -> new Box(arm.maxX - extension, arm.minY, arm.minZ,
                    arm.maxX, arm.maxY, arm.maxZ);
            case UP -> new Box(arm.minX, arm.minY, arm.minZ,
                    arm.maxX, arm.minY + extension, arm.maxZ);
            case DOWN -> new Box(arm.minX, arm.maxY - extension, arm.minZ,
                    arm.maxX, arm.maxY, arm.maxZ);
            case SOUTH -> new Box(arm.minX, arm.minY, arm.minZ,
                    arm.maxX, arm.maxY, arm.minZ + extension);
            default -> new Box(arm.minX, arm.minY, arm.maxZ - extension,
                    arm.maxX, arm.maxY, arm.maxZ);
        };
    }

    public record CollisionBoxes(Box armBaseAABB, Box armAABB, Box frontAABB) {
        public List<Box> asList() {
            final ArrayList<Box> result = new ArrayList<>(3);
            if (armBaseAABB != null && armBaseAABB.isValid()) result.add(armBaseAABB);
            if (armAABB != null && armAABB.isValid()) result.add(armAABB);
            if (frontAABB != null && frontAABB.isValid()) result.add(frontAABB);
            return List.copyOf(result);
        }
    }
}
