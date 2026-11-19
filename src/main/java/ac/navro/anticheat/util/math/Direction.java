package ac.navro.anticheat.util.math;


public enum Direction {
    DOWN(Axis.Y, -1), UP(Axis.Y, 1), NORTH(Axis.Z, -1), SOUTH(Axis.Z, 1),
    WEST(Axis.X, -1), EAST(Axis.X, 1);

    public enum Axis { X, Y, Z }

    private final Axis axis;
    private final int step;

    Direction(Axis axis, int step) {
        this.axis = axis;
        this.step = step;
    }

    public Axis axis() { return axis; }
    public int step() { return step; }
    public int getXOffset() { return axis == Axis.X ? step : 0; }
    public int getYOffset() { return axis == Axis.Y ? step : 0; }
    public int getZOffset() { return axis == Axis.Z ? step : 0; }

    public Direction getOpposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }
}
