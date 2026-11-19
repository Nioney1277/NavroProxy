package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Direction;
import java.util.Locale;


public final class NativePistonProperties {
    public static final int STICKY_PISTON = 29, PISTON = 33, PISTON_HEAD = 34;
    public static final int MOVING_BLOCK = 250, PISTON_HEAD_STICKY = 472;

    private NativePistonProperties() { }

    public static boolean isMovingBlock(NetworkBlockState state) {
        return state != null && (state.is("minecraft:moving_block") || state.blockId() == MOVING_BLOCK);
    }
    public static boolean isPiston(NetworkBlockState state) {
        return state != null && (state.blockId() == PISTON || state.blockId() == STICKY_PISTON
                || state.is("minecraft:piston") || state.is("minecraft:sticky_piston"));
    }
    public static boolean isPistonHead(NetworkBlockState state) {
        return state != null && (state.blockId() == PISTON_HEAD || state.blockId() == PISTON_HEAD_STICKY
                || state.is("minecraft:piston_arm_collision") || state.is("minecraft:sticky_piston_arm_collision"));
    }
    public static Direction headFacing(NetworkBlockState state) {
        Direction fallback = Direction.DOWN;
        if (state.blockId() == PISTON_HEAD || state.blockId() == PISTON_HEAD_STICKY) {

            fallback = Direction.values()[Math.abs(state.blockData() % Direction.values().length)];
            if (fallback.axis() != Direction.Axis.Y) fallback = fallback.getOpposite();
        }
        return pistonFacingDirection(state, fallback);
    }

    public static Direction pistonFacingDirection(NetworkBlockState state, Direction fallback) {
        String direction = state.stringProperty("minecraft:cardinal_direction",
                state.stringProperty("cardinal_direction", ""));
        Direction horizontal = horizontalFace(direction);
        if (horizontal != null) {
            return horizontal;
        }
        if (!state.hasProperty("facing_direction")) {
            return fallback;
        }
        return switch (state.intProperty("facing_direction", -1)) {
            case 0 -> Direction.DOWN;
            case 1 -> Direction.UP;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.NORTH;
            case 4 -> Direction.EAST;
            case 5 -> Direction.WEST;
            default -> fallback;
        };
    }

    private static Direction horizontalFace(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }
}
