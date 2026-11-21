package ac.navro.proxy.prediction.fluid;

import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NativeFluidBlockProperties.Properties;
import static ac.navro.proxy.data.block.NativeFluidBlockProperties.*;
import ac.navro.proxy.data.block.NativePistonProperties;
import ac.navro.proxy.collision.block.PistonBlock;
import ac.navro.proxy.collision.block.PistonArmBlock;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import org.cloudburstmc.math.vector.Vector3i;
import ac.navro.proxy.prediction.fluid.FluidBlockCollision.Context;
import ac.navro.proxy.prediction.fluid.FluidBlockCollision.State;
import java.util.List;
import java.util.Locale;




public final class FluidDynamicCollision {
    private FluidDynamicCollision() {
    }

    public static List<Box> getCollisionBox(Context player, Vector3i pos, State blockState) {
        final Properties state = blockState.getBlock();
        final NetworkBlockState network = blockState.getNetworkState();

        if (state.has(FENCE)
                || network.identifier().endsWith("_fence")) {
            final int mask = fenceConnectionMask(player, pos, blockState);
            return fenceShape((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
        }

        if (isPane(state)) {
            final int mask = paneConnectionMask(player, pos);
            return paneShape((mask & 1) != 0, (mask & 2) != 0,
                    (mask & 4) != 0, (mask & 8) != 0);
        }

        if (state.has(STAIRS)) {
            return stairShape(blockState, getStairShape(player, pos, blockState));
        }

        if (state.has(WALL) || network.identifier().endsWith("_wall")) {
            return wallShape(network, state);
        }

        if (NativePistonProperties.isPiston(network)) {
            return PistonBlock.from(network).getCollisionShape();
        }
        if (NativePistonProperties.isPistonHead(network)) {
            return PistonArmBlock.getCollisionShape(player.compensatedWorld.world, pos, network,
                    NativePistonProperties.headFacing(network));
        }

        if (state.has(CHORUS) || network.is("minecraft:chorus_plant")) {
            return chorusShape(player, pos, network);
        }

        return null;
    }

    static List<Box> fenceShape(boolean north, boolean east, boolean south, boolean west) {
        return FluidBlockCollision.dynamicShape(1,
                connectionMask(north, east, south, west));
    }

    static List<Box> paneShape(boolean north, boolean east, boolean south, boolean west) {

        final java.util.ArrayList<Box> shape = new java.util.ArrayList<>(2);
        if (north || south) {
            final float minZ = north ? 0.0F : 0.5F;
            final float maxZ = south ? 1.0F : 0.5F;
            shape.add(new Box(0.4375F, 0, minZ, 0.5625F, 1, maxZ));
        }
        if (east || west) {
            final float minX = west ? 0.0F : 0.5F;
            final float maxX = east ? 1.0F : 0.5F;
            shape.add(new Box(minX, 0, 0.4375F, maxX, 1, 0.5625F));
        }
        if (shape.isEmpty()) {
            shape.add(new Box(0.4375F, 0, 0.4375F, 0.5625F, 1, 0.5625F));
        }
        return List.copyOf(shape);
    }

    static List<Box> stairShape(State state, StairShape shape) {
        int key = direction(stairDirection(state));
        key |= (stairTop(state) ? 1 : 0) << 2;
        key |= shape.ordinal() << 3;
        return FluidBlockCollision.dynamicShape(3, key);
    }

    private static List<Box> wallShape(NetworkBlockState network, Properties wall) {
        final boolean hasPacketState = network.hasProperty("wall_post_bit")
                || network.hasProperty("wall_connection_type_north");
        final boolean post;
        final boolean north;
        final boolean east;
        final boolean south;
        final boolean west;
        if (hasPacketState) {
            post = network.booleanProperty("wall_post_bit", false);
            north = wall(network.stringProperty("wall_connection_type_north", "none")) != 0;
            east = wall(network.stringProperty("wall_connection_type_east", "none")) != 0;
            south = wall(network.stringProperty("wall_connection_type_south", "none")) != 0;
            west = wall(network.stringProperty("wall_connection_type_west", "none")) != 0;
        } else {
            return wall.wall();
        }

        float minZ = north ? 0.0F : 0.25F;
        float maxZ = south ? 1.0F : 0.75F;
        float minX = west ? 0.0F : 0.25F;
        float maxX = east ? 1.0F : 0.75F;

        if (north && south && !west && !east && !post) {
            minX = 0.3125F;
            maxX = 0.6875F;
        } else if (!north && !south && west && east && !post) {
            minZ = 0.3125F;
            maxZ = 0.6875F;
        }

        return List.of(new Box(minX, 0, minZ, maxX, 1.5F, maxZ));
    }

    private static int wall(String type) {
        if ("short".equalsIgnoreCase(type)) return 1;
        if ("tall".equalsIgnoreCase(type)) return 2;
        return 0;
    }

    private static Direction pistonFacingDirection(NetworkBlockState state, Direction fallback) {
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

    private static int pistonDirection(Direction face) {
        return switch (face) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
            default -> 0;
        };
    }

    private static List<Box> chorusShape(Context player, Vector3i pos, NetworkBlockState state) {
        final boolean packetConnections = state.hasProperty("down") || state.hasProperty("up")
                || state.hasProperty("north") || state.hasProperty("east")
                || state.hasProperty("south") || state.hasProperty("west");
        int key;
        if (packetConnections) {
            key = state.booleanProperty("down", false) ? 1 : 0;
            key |= (state.booleanProperty("up", false) ? 1 : 0) << 1;
            key |= (state.booleanProperty("north", false) ? 1 : 0) << 2;
            key |= (state.booleanProperty("east", false) ? 1 : 0) << 3;
            key |= (state.booleanProperty("south", false) ? 1 : 0) << 4;
            key |= (state.booleanProperty("west", false) ? 1 : 0) << 5;
        } else {
            key = connectsChorus(player, pos, Direction.DOWN, true) ? 1 : 0;
            key |= (connectsChorus(player, pos, Direction.UP, false) ? 1 : 0) << 1;
            key |= (connectsChorus(player, pos, Direction.NORTH, false) ? 1 : 0) << 2;
            key |= (connectsChorus(player, pos, Direction.EAST, false) ? 1 : 0) << 3;
            key |= (connectsChorus(player, pos, Direction.SOUTH, false) ? 1 : 0) << 4;
            key |= (connectsChorus(player, pos, Direction.WEST, false) ? 1 : 0) << 5;
        }
        return FluidBlockCollision.dynamicShape(5, key);
    }

    private static boolean connectsChorus(Context player, Vector3i pos,
                                          Direction face, boolean endStone) {
        Properties block = player.compensatedWorld.getBlockState(
                pos.getX() + face.getXOffset(), pos.getY() + face.getYOffset(),
                pos.getZ() + face.getZOffset(), 0).getBlock();
        return block.has(CHORUS) || block.has(CHORUS_FLOWER)
                || endStone && block.getId() == 121;
    }

    private static int connectionMask(boolean north, boolean east, boolean south, boolean west) {
        return (north ? 1 : 0) | (east ? 2 : 0) | (south ? 4 : 0) | (west ? 8 : 0);
    }


    public static int fenceConnectionMask(Context player, Vector3i pos, State state) {
        final NetworkBlockState network = state.getNetworkState();



        if (network.hasProperty("minecraft:connection_north")
                || network.hasProperty("minecraft:connection_east")
                || network.hasProperty("minecraft:connection_south")
                || network.hasProperty("minecraft:connection_west")) {
            return connectionMask(network.booleanProperty("minecraft:connection_north", false),
                    network.booleanProperty("minecraft:connection_east", false),
                    network.booleanProperty("minecraft:connection_south", false),
                    network.booleanProperty("minecraft:connection_west", false));
        }
        final Properties current = state.getBlock();
        return connectionMask(connectsFence(player, pos, current, Direction.NORTH),
                connectsFence(player, pos, current, Direction.EAST),
                connectsFence(player, pos, current, Direction.SOUTH),
                connectsFence(player, pos, current, Direction.WEST));
    }

    private static int direction(Direction face) {
        return switch (face) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static boolean connectsFence(Context player, Vector3i pos, Properties current, Direction face) {
        State neighbourState = neighbour(player, pos, face);
        Properties neighbour = neighbourState.getBlock();
        return (!neighbour.has(CONNECTION_EXCEPTION) && neighbourState.isFaceSturdy(player))
                || (neighbour.has(MAPPED_FENCE)
                && (neighbour.getId() == 113)
                == (current.getId() == 113))
                || connectsToDirection(neighbour, face.getOpposite());
    }

    private static boolean connectsPane(Context player, Vector3i pos, Direction face) {
        State neighbourState = neighbour(player, pos, face);
        Properties neighbour = neighbourState.getBlock();
        final boolean sturdy = neighbourState.isFaceSturdy(player);
        final String neighbourIdentifier = neighbourState.getNetworkState().identifier();
        return (!neighbour.has(CONNECTION_EXCEPTION) && sturdy)
                || isPane(neighbour)
                || neighbour.has(MAPPED_WALL)
                || neighbour.has(WALL)
                || neighbourIdentifier.endsWith("_wall");
    }

    public static int paneConnectionMask(Context player, Vector3i pos) {
        return connectionMask(
                connectsPane(player, pos, Direction.NORTH),
                connectsPane(player, pos, Direction.EAST),
                connectsPane(player, pos, Direction.SOUTH),
                connectsPane(player, pos, Direction.WEST));
    }

    public static boolean isPane(Properties state) {
        return state.has(PANE);
    }

    private static StairShape getStairShape(Context player, Vector3i pos, State state) {
        Direction direction = stairDirection(state);
        State forward = neighbour(player, pos, direction);
        if (sameHalfStair(state, forward)) {
            Direction other = stairDirection(forward);
            if (other.axis() != direction.axis()
                    && isDifferentOrientation(player, state, pos, other.getOpposite())) {
                return other == rotateYCCW(direction) ? StairShape.OUTER_LEFT : StairShape.OUTER_RIGHT;
            }
        }

        State behind = neighbour(player, pos, direction.getOpposite());
        if (sameHalfStair(state, behind)) {
            Direction other = stairDirection(behind);
            if (other.axis() != direction.axis()
                    && isDifferentOrientation(player, state, pos, other)) {
                return other == rotateYCCW(direction) ? StairShape.INNER_LEFT : StairShape.INNER_RIGHT;
            }
        }
        return StairShape.STRAIGHT;
    }

    private static boolean isDifferentOrientation(Context player, State state,
                                                  Vector3i pos, Direction direction) {
        State adjacent = neighbour(player, pos, direction);
        return !sameHalfStair(state, adjacent) || stairDirection(adjacent) != stairDirection(state);
    }

    private static boolean sameHalfStair(State first, State second) {
        return second.getBlock().has(STAIRS)
                && stairTop(first) == stairTop(second);
    }

    private static boolean stairTop(State state) {
        NetworkBlockState network = state.getNetworkState();
        return network.booleanProperty("upside_down_bit", (state.getBlock().getDamage() & 4) != 0);
    }

    private static Direction stairDirection(State state) {
        NetworkBlockState network = state.getNetworkState();
        String cardinal = network.stringProperty("minecraft:cardinal_direction",
                network.stringProperty("cardinal_direction", ""));
        Direction fromString = horizontalFace(cardinal);
        if (fromString != null) {
            return fromString;
        }
        return switch (network.intProperty("weirdo_direction", state.getBlock().getDamage() & 3)) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.WEST;
            case 2 -> Direction.SOUTH;
            default -> Direction.NORTH;
        };
    }

    static Direction facingDirection(NetworkBlockState state, Direction fallback) {
        String direction = state.stringProperty("minecraft:cardinal_direction",
                state.stringProperty("cardinal_direction", ""));
        Direction horizontal = horizontalFace(direction);
        if (horizontal != null) {
            return horizontal;
        }
        return switch (state.intProperty("facing_direction", pistonDirection(fallback))) {
            case 0 -> Direction.DOWN;
            case 1 -> Direction.UP;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> fallback;
        };
    }

    static Direction horizontalFace(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    private static State neighbour(Context player, Vector3i pos, Direction face) {
        return player.compensatedWorld.getBlockState(
                pos.getX() + face.getXOffset(), pos.getY(), pos.getZ() + face.getZOffset(), 0);
    }

    private static Direction rotateYCCW(Direction face) {
        return switch(face){case NORTH -> Direction.WEST;case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;case EAST -> Direction.NORTH;default -> face;};
    }

    private static boolean connectsToDirection(Properties state,Direction direction) {
        if(!state.has(FENCE_GATE)) return false;
        Direction gateFace=state.gateFace();
        return (gateFace.getXOffset()!=0 && direction.getXOffset()==0)
                || (gateFace.getZOffset()!=0 && direction.getZOffset()==0);
    }

    enum StairShape {
        STRAIGHT,
        INNER_LEFT,
        INNER_RIGHT,
        OUTER_LEFT,
        OUTER_RIGHT
    }
}
