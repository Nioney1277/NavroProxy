package ac.navro.proxy.util.block;

import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class BlockActions {
    private BlockActions() { }

    public static List<PlayerBlockActionData> read(PlayerAuthInputPacket packet) {

        return packet.getPlayerActions().stream().map(action -> action == null ? null : copy(action))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }


    public static List<PlayerBlockActionData> read(Map<PlayerActionType, PlayerBlockActionData> actions) {
        final var map = new LinkedHashMap<>(actions);
        map.entrySet().removeIf(e -> e.getValue() == null || e.getKey() != e.getValue().getAction());
        final List<PlayerBlockActionData> ordered = new ArrayList<>();
        final var finish = map.remove(PlayerActionType.BLOCK_PREDICT_DESTROY);
        final var start = map.get(PlayerActionType.START_BREAK);


        if (finish != null && start != null && Objects.equals(start.getBlockPosition(), finish.getBlockPosition())) {
            ordered.add(map.remove(PlayerActionType.START_BREAK));
        }
        if (finish != null) ordered.add(finish);
        final var abort = map.remove(PlayerActionType.ABORT_BREAK);
        if (abort != null) ordered.add(abort);
        ordered.addAll(map.values());
        return ordered;
    }


    public static List<PlayerBlockActionData> write(PlayerAuthInputPacket packet,
                                                   List<PlayerBlockActionData> actions) {
        var snapshot = actions.stream().map(BlockActions::copy).toList();
        packet.getPlayerActions().clear();
        packet.getPlayerActions().addAll(snapshot);
        if (actions.isEmpty()) packet.getInputData().remove(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
        return actions;
    }

    private static PlayerBlockActionData copy(PlayerBlockActionData action) {
        var result = new PlayerBlockActionData();
        result.setAction(action.getAction());
        result.setBlockPosition(action.getBlockPosition());
        result.setFace(action.getFace());
        return result;
    }
}
