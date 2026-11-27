package ac.navro.proxy.validator.blockbreak;

import ac.navro.proxy.data.BreakingData;
import ac.navro.proxy.data.block.BlockBreakState;
import ac.navro.proxy.data.block.NativeBlockProperties;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.util.block.BlockUtil;
import ac.navro.proxy.util.block.BlockActions;
import ac.navro.proxy.util.block.BlockActionText;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public class ServerBreakBlockValidator {
    private static final EnumSet<PlayerActionType> ALLOWED_ACTIONS = EnumSet.of(
            PlayerActionType.START_BREAK,
            PlayerActionType.ABORT_BREAK,
            PlayerActionType.BLOCK_PREDICT_DESTROY,
            PlayerActionType.BLOCK_CONTINUE_DESTROY
    );




    public interface Context {
        CompensatedWorld world();
        Vec3 position();
        GameType gameType();
        String name();
        void restoreCorrectBlock(Vector3i position);
        default java.util.function.Consumer<String> debugLogger() { return null; }
        default BlockBreakState blockState(Vector3i position, int layer) {
            var state = Objects.requireNonNull(world().networkBlockState(world().getRawBlockAt(
                    position.getX(), position.getY(), position.getZ(), layer)), "Block palette is not initialized");
            return new BlockBreakState(state, NativeBlockProperties.forState(state));
        }
        default Iterable<PlayerBlockActionData> rawActions(PlayerAuthInputPacket packet) {
            final var map = new java.util.LinkedHashMap<PlayerActionType, PlayerBlockActionData>();
            for (var action : packet.getPlayerActions()) map.put(action == null ? null : action.getAction(), action);
            return map.values();
        }
        default List<PlayerBlockActionData> readActions(PlayerAuthInputPacket packet) { return BlockActions.read(packet); }
        default List<PlayerBlockActionData> writeActions(PlayerAuthInputPacket packet, List<PlayerBlockActionData> actions) {
            return BlockActions.write(packet, actions);
        }
    }

    private final Context player;
    private BreakingData breakingData;
    private long breakingEpoch = Long.MIN_VALUE;

    public ServerBreakBlockValidator(Context player) {
        this.player = player;
    }

    public void handle(PlayerAuthInputPacket packet) {
        final long worldEpoch = player.world().getBlockBreakEpoch();
        if (breakingEpoch != worldEpoch) {
            breakingData = null;
            breakingEpoch = worldEpoch;
        }
        if (!packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS)) {
            return;
        }

        trace(packet.getTick(), "raw=" + describe(player.rawActions(packet))
                + " breaking=" + breakingData
                + " packetPos=" + BlockActionText.position(packet.getPosition())
                + " headYaw=" + (packet.getRotation() == null ? 0F : packet.getRotation().getZ())
                + " pitch=" + (packet.getRotation() == null ? 0F : packet.getRotation().getX()));

        final List<PlayerBlockActionData> validActions = new ArrayList<>();
        final List<AcceptedBreak> acceptedBreaks = new ArrayList<>();
        final Set<Vector3i> completedInPacket = new HashSet<>();
        final var entries = player.readActions(packet);

        for (PlayerBlockActionData action : entries) {
            if (action == null || action.getAction() == null) {
                trace(packet.getTick(), "skip=malformed action=" + BlockActionText.action(action));
                continue;
            }

            final PlayerActionType actionType = action.getAction();
            final Vector3i position = action.getBlockPosition();
            final int face = action.getFace();
            if (!ALLOWED_ACTIONS.contains(actionType) || position == null || !isValid(position)) {
                trace(packet.getTick(), "skip=invalid action=" + BlockActionText.name(actionType) + " pos=" + BlockActionText.position(position));
                continue;
            }


            if (actionType == PlayerActionType.ABORT_BREAK) {
                if (breakingData != null && Objects.equals(position, breakingData.getPosition())) {
                    breakingData = null;
                    validActions.add(action);
                }
                continue;
            }
            if (face < 0 || face >= BlockFaceCount.COUNT) {
                trace(packet.getTick(), "skip=invalid-face action=" + BlockActionText.name(actionType) + " face=" + face);
                continue;
            }
            if (distanceSquared(position, blockPosition(player.position())) > 144L) {
                trace(packet.getTick(), "skip=far action=" + BlockActionText.name(actionType) + " pos=" + BlockActionText.position(position));
                player.restoreCorrectBlock(position);
                continue;
            }

            final var state = player.blockState(position, 0);

            if (state.nativeState().itemFrame()) {
                validActions.add(action);
                continue;
            }


            if ((actionType == PlayerActionType.BLOCK_CONTINUE_DESTROY
                    || actionType == PlayerActionType.BLOCK_PREDICT_DESTROY)
                    && (completedInPacket.contains(position)
                        || state.isAir() && player.world().isPredictedBlockBreak(position))) {

                continue;
            }
            if (!BlockUtil.determineCanBreak(player.gameType(), state)) {

                trace(packet.getTick(), "skip=unbreakable action=" + BlockActionText.name(actionType) + " pos=" + BlockActionText.position(position)
                        + " state=" + state.networkState().identifier()
                        + " id=" + state.nativeState().blockId()
                        + " gameType=" + player.gameType());
                player.restoreCorrectBlock(position);
                continue;
            }

            switch (actionType) {
                case START_BREAK, BLOCK_CONTINUE_DESTROY -> {
                    if (actionType == PlayerActionType.START_BREAK) {
                        player.world().beginBlockBreak(position);
                    }
                    if (breakingData == null || !Objects.equals(position, breakingData.getPosition())) {
                        if (breakingData != null) {
                            player.restoreCorrectBlock(breakingData.getPosition());
                        }
                        breakingData = new BreakingData(PlayerActionType.START_BREAK,
                                Vector3i.from(position.getX(), position.getY(), position.getZ()), face);
                    } else {
                        breakingData.setState(PlayerActionType.BLOCK_CONTINUE_DESTROY);
                    }


                    breakingData.setBreakingProcess(1F);
                }
                case BLOCK_PREDICT_DESTROY -> {
                    if (breakingData == null
                            || !Objects.equals(position, breakingData.getPosition())
                            || breakingData.getBreakingProcess() < 1F) {

                        trace(packet.getTick(), "skip=predict-state action=" + BlockActionText.name(actionType) + " pos=" + BlockActionText.position(position)
                                + " breaking=" + breakingData
                                + " state=" + state.networkState().identifier()
                                + " id=" + state.nativeState().blockId()
                                + " gameType=" + player.gameType());
                        player.restoreCorrectBlock(position);
                        continue;
                    }

                    final Vector3i otherHalf = doorOtherHalf(state, position);
                    Vector3i predictedOtherHalf = null;
                    if (otherHalf != null) {
                        final var otherState = player.blockState(otherHalf, 0);
                        if (isSameDoor(state, otherState)) {
                            predictedOtherHalf = otherHalf;
                            completedInPacket.add(otherHalf);
                        }
                    }
                    acceptedBreaks.add(new AcceptedBreak(action, predictedOtherHalf));
                    completedInPacket.add(Vector3i.from(position.getX(), position.getY(), position.getZ()));
                    breakingData = null;
                }
                default -> {
                    continue;
                }
            }

            validActions.add(action);

        }

        final var forwarded = player.writeActions(packet, validActions);
        for (final AcceptedBreak accepted : acceptedBreaks) {
            final Vector3i position = accepted.action().getBlockPosition();
            if (forwarded.contains(accepted.action())) {
                player.world().predictBlockBreak(position);
                if (accepted.otherHalf() != null) {
                    player.world().predictBlockBreak(accepted.otherHalf());
                }
            } else {


                player.restoreCorrectBlock(position);
                if (accepted.otherHalf() != null) {
                    player.restoreCorrectBlock(accepted.otherHalf());
                }
            }
        }
        trace(packet.getTick(), "valid=" + describe(forwarded) + " breaking=" + breakingData);
        if (validActions.isEmpty()) {
            packet.getInputData().remove(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
        }
    }

    private record AcceptedBreak(PlayerBlockActionData action, Vector3i otherHalf) { }

    private void trace(final long tick, final String detail) {
        final var logger = player.debugLogger();
        if (logger != null) {
            logger.accept("[BlockBreakDebug] player=" + player.name() + " tick=" + tick + " " + detail);
        }
    }

    private static String describe(final Iterable<PlayerBlockActionData> actions) {
        final StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (PlayerBlockActionData action : actions) {
            if (!first) result.append(",");
            first = false;
            result.append(action == null ? "null" : BlockActionText.name(action.getAction()) + "@" + BlockActionText.position(action.getBlockPosition()));
        }
        return result.append(']').toString();
    }

    private static Vector3i blockPosition(Vec3 position) {
        return Vector3i.from((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
    }

    private static boolean isValid(Vector3i position) {
        return Float.isFinite(position.getX()) && Float.isFinite(position.getY()) && Float.isFinite(position.getZ());
    }

    private static long distanceSquared(Vector3i first, Vector3i second) {
        final long x = (long) first.getX() - second.getX();
        final long y = (long) first.getY() - second.getY();
        final long z = (long) first.getZ() - second.getZ();
        return x * x + y * y + z * z;
    }

    private static boolean isDoor(final BlockBreakState state) {
        return state != null && (state.nativeState().door()
                || state.networkState().identifier().endsWith("_door"));
    }

    private static boolean isSameDoor(final BlockBreakState first, final BlockBreakState second) {
        if (!isDoor(first) || !isDoor(second)) return false;
        final String a = first.networkState().identifier();
        final String b = second.networkState().identifier();
        return a.isEmpty() || b.isEmpty() || a.equals(b)
                || first.nativeState().blockId() == second.nativeState().blockId();
    }

    private static Vector3i doorOtherHalf(final BlockBreakState state, final Vector3i position) {
        if (!isDoor(state)) return null;
        final boolean top = state.nativeState().door()
                ? state.nativeState().upperDoor()
                : state.networkState().booleanProperty("upper_block_bit", false);
        return top ? position.sub(0, 1, 0) : position.add(0, 1, 0);
    }

    private static final class BlockFaceCount {
        private static final int COUNT = 6;

        private BlockFaceCount() {
        }
    }
}
