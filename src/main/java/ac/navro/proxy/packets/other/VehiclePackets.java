package ac.navro.proxy.packets.other;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.player.data.VehicleData;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;


public final class VehiclePackets {
    private VehiclePackets() { }

    public static void onPacketReceive(WirePlayerState player, BedrockPacket packet) {
        if (packet instanceof InteractPacket interact && interact.getRuntimeEntityId() == player.runtimeId()
                && interact.getAction() == InteractPacket.Action.LEAVE_VEHICLE) player.vehicleData = null;
    }

    public static void onPacketSend(SessionState state, BedrockPacket packet) {
        if (packet instanceof SetEntityLinkPacket link)
            state.player().queueAcknowledgment(packet, () -> applySetEntityLink(state, link));
        var links = packet instanceof AddEntityPacket entity ? entity.getEntityLinks()
                : packet instanceof AddPlayerPacket player ? player.getEntityLinks() : null;
        if (links != null && !links.isEmpty()) {
            var snapshot = java.util.List.copyOf(links);
            state.player().queueAcknowledgment(packet, () -> snapshot.forEach(link ->
                    applyLink(state, link.getFrom(), link.getTo(), link.getType())));
        }
    }

    private static void applySetEntityLink(SessionState state, SetEntityLinkPacket packet) {
        var link = packet.getEntityLink();

        applyLink(state, link.getFrom(), link.getTo(), link.getType());
    }

    private static void applyLink(SessionState state, long vehicleUniqueId, long riderUniqueId, EntityLinkData.Type type) {
        var player = state.player();
        Long vehicleRuntime = state.entityRuntimeIdByUniqueId(vehicleUniqueId);


        boolean localRider = riderUniqueId == player.runtimeId();
        Long riderRuntime = localRider ? player.runtimeId() : state.entityRuntimeIdByUniqueId(riderUniqueId);
        if (!localRider && riderRuntime != null) {
            var rider = state.entity(riderRuntime);
            if (rider != null) rider.inVehicle(type != EntityLinkData.Type.REMOVE);
        }
        if (!localRider) return;
        var linkedVehicle = vehicleRuntime == null ? null : state.entity(vehicleRuntime);
        if (linkedVehicle == null) return;
        state.teleports().getQueuedTeleports().clear();
        if (type == EntityLinkData.Type.REMOVE) {
            player.vehicleData = null;
            return;
        }
        var data = new VehicleData();
        data.vehicleRuntimeId = vehicleRuntime;
        player.vehicleData = data;
    }
}
