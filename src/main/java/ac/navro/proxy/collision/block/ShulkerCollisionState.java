package ac.navro.proxy.collision.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;


public final class ShulkerCollisionState {
    private final Vector3i position;
    private int facing = 1;
    private boolean open;
    private float openness;
    private Box shape;

    public ShulkerCollisionState(final Vector3i position, final NbtMap tag) {
        this.position = position;
        load(tag);
        calculateBox();
    }

    public void load(final NbtMap tag) {
        if (tag != null && tag.containsKey("facing")) {
            final int value = ac.navro.proxy.util.NbtNumber.getInt(tag, "facing");
            facing = value >= 0 && value <= 5 ? value : 0;
        }
    }

    public void setOpenCount(final int count) { open = count > 0; }
    public Box shape() { return shape; }


    public record Frame(int x, int y, int z, int facing, float openness, Box shape) { }

    public Frame snapshot() {
        return new Frame(position.getX(), position.getY(), position.getZ(), facing, openness, shape);
    }

    public void tick() {

        calculateBox();
        openness = open ? Math.min(1, openness + 0.05F) : Math.max(0, openness - 0.05F);
    }

    private void calculateBox() {
        final float inverse = 1.0F - openness;
        final float extension = 0.5F * (1.0F - inverse * inverse * inverse);
        float minX = position.getX(), minY = position.getY(), minZ = position.getZ();
        float maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        switch (face()) {
            case DOWN -> minY -= extension;
            case UP -> maxY += extension;
            case NORTH -> minZ -= extension;
            case SOUTH -> maxZ += extension;
            case WEST -> minX -= extension;
            case EAST -> maxX += extension;
        }
        shape = new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private Direction face() {
        return switch (facing) {
            case 1 -> Direction.UP;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> Direction.DOWN;
        };
    }
}
