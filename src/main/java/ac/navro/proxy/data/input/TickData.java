package ac.navro.proxy.data.input;

import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.ItemUseTracker;
import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.state.WireTickSnapshot;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import java.util.Set;





public record TickData(PlayerAuthInputPacket packet, WireTickSnapshot state,
                       Set<Integer> itemUseFlags, ItemUseTracker.Snapshot<ItemData> itemUseSnapshot,
                       float fallDistance, float swimSpeedMultiplier, float waterDrag,
                       RiptidePhysics.Snapshot riptideStartState, RiptidePhysics.Snapshot riptideEndState,
                       MovementAabbSnapshot inputAabb, MovementAabbSnapshot endAabb) {
    public TickData {
        packet = snapshotPacket(packet);
        itemUseFlags = Set.copyOf(itemUseFlags);
    }

    public static TickData capture(SessionState session, PlayerAuthInputPacket packet) {
        var wire = session.player();
        var context = session.authInputPackets().context();
        var tracker = session.authInputPackets().itemUse().getItemUseTracker();
        var state = wire.snapshot(session.fluidState());
        return new TickData(packet, state, tracker.snapshotPredictionFlags(wire.tick()), tracker.snapshot(),
                context.fallDistance, context.fluidPrediction.swimSpeedMultiplier, context.fluidPrediction.waterDrag,
                context.riptideTickStartState, RiptidePhysics.snapshot(context),
                state.inputAabb() == null ? wire.snapshotAabb() : state.inputAabb(), wire.snapshotAabb());
    }


    public static PlayerAuthInputPacket snapshotPacket(PlayerAuthInputPacket source) {
        var copy = new PlayerAuthInputPacket();
        copy.setRotation(source.getRotation());
        copy.setTick(source.getTick());
        copy.setPosition(source.getPosition());
        copy.setDelta(source.getDelta());
        copy.setMotion(source.getMotion());
        copy.getInputData().addAll(source.getInputData());
        copy.setInputMode(source.getInputMode());
        copy.setInputInteractionModel(source.getInputInteractionModel());
        copy.setPlayMode(source.getPlayMode());
        if (source.getCameraOrientation() != null) copy.setCameraOrientation(source.getCameraOrientation());
        if (source.getRawMoveVector() != null) copy.setRawMoveVector(source.getRawMoveVector());
        if (source.getInteractRotation() != null) copy.setInteractRotation(source.getInteractRotation());
        return copy;
    }
}
