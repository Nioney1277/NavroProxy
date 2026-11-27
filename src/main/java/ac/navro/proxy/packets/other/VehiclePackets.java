package ac.navro.proxy.packets.other;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.player.data.VehicleData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;



public final class VehiclePackets {
    private VehiclePackets() { }

    public static void onPacketReceive(SessionState state, BedrockPacket packet) {
        var player = state.player();
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.RiderJumpPacket jump && player.vehicleData != null)
            player.vehicleData.jumpCharge = Math.max(0, Math.min(100, jump.getJumpStrength()));
        if (packet instanceof InteractPacket interact && interact.getAction()==InteractPacket.Action.LEAVE_VEHICLE
                && player.vehicleData!=null) {
            if(state.detectionEnabled())state.vehicleTransitions().requestExit(interact.getRuntimeEntityId());
            else if(interact.getRuntimeEntityId()==player.runtimeId())player.vehicleData=null;
        }
    }

    public static void onPacketSend(SessionState state, BedrockPacket packet) {
        if (packet instanceof SetEntityLinkPacket link && link.getEntityLink()!=null)
            scheduleLink(state,packet,link.getEntityLink());
        var links = packet instanceof AddEntityPacket entity ? entity.getEntityLinks()
                : packet instanceof AddPlayerPacket player ? player.getEntityLinks() : null;
        if(links!=null)for(var link:java.util.List.copyOf(links))scheduleLink(state,packet,link);
    }

    private static void scheduleLink(SessionState state,BedrockPacket packet,EntityLinkData snapshot) {
        var mounted = state.player().vehicleData;
        VehicleData planned=null;
        if(state.detectionEnabled()&&snapshot.getTo()==state.player().uniqueId()) {
            if(snapshot.getType()==EntityLinkData.Type.REMOVE)
                mounted=state.vehicleTransitions().announceRemoval(snapshot.getFrom());
            else {
                var runtime=state.entityRuntimeIdByUniqueId(snapshot.getFrom());
                if(runtime!=null)planned=state.vehicleTransitions().announceMount(runtime,snapshot.getFrom(),snapshot.getType()==EntityLinkData.Type.RIDER);
            }
        }
        final var expected=mounted;
        final var mountPlan=planned;
        var teleport = state.detectionEnabled() ? state.vehicleTransitions().lastTeleport() : null;
        long leaveRevision = state.detectionEnabled() ? state.vehicleTransitions().leaveRevision() : 0;
        if (state.detectionEnabled() && snapshot.getType() == EntityLinkData.Type.REMOVE)
            state.vehicleTransitions().removalSent(snapshot.getFrom(), snapshot.getTo());
        var runtimeAtSend=state.entityRuntimeIdByUniqueId(snapshot.getFrom());
        var actorAtSend=runtimeAtSend==null?null:state.entity(runtimeAtSend);
        state.player().queueAcknowledgment(packet, () -> {
            if (snapshot.getTo() == state.player().uniqueId() && snapshot.getType() == EntityLinkData.Type.REMOVE
                    && state.detectionEnabled()) {
                state.vehicleTransitions().acknowledged(snapshot.getFrom(), expected, teleport);
            } else {
                if(actorAtSend!=null&&state.entity(actorAtSend.runtimeId())!=actorAtSend)return;
                applyLink(state, snapshot.getFrom(), snapshot.getTo(), snapshot.getType(),mountPlan);
                if (state.detectionEnabled() && expected != null && snapshot.getTo() == state.player().uniqueId())
                    state.vehicleTransitions().refused(expected, leaveRevision);
            }
        });
    }

    private static void applyLink(SessionState state, long vehicleUniqueId, long riderUniqueId, EntityLinkData.Type type) {
        applyLink(state,vehicleUniqueId,riderUniqueId,type,null);
    }
    private static void applyLink(SessionState state, long vehicleUniqueId, long riderUniqueId, EntityLinkData.Type type,VehicleData planned) {
        var player = state.player();
        Long vehicleRuntime = state.entityRuntimeIdByUniqueId(vehicleUniqueId);


        boolean localRider = riderUniqueId == player.uniqueId();
        Long riderRuntime = localRider ? Long.valueOf(player.runtimeId()) : state.entityRuntimeIdByUniqueId(riderUniqueId);
        if (!localRider && riderRuntime != null) {
            var rider = state.entity(riderRuntime);
            if (rider != null) rider.inVehicle(type != EntityLinkData.Type.REMOVE);
        }
        if (!localRider) {
            if (type == EntityLinkData.Type.RIDER && player.vehicleData != null
                    && player.vehicleData.vehicleUniqueId == vehicleUniqueId) {
                player.vehicleData.driver = false;
                player.vehicleData.canWeControlThisVehicle = false;
                if (state.detectionEnabled()) state.authInputPackets().vehicles().reset();
            }
            return;
        }
        if(type==EntityLinkData.Type.REMOVE) {
            if(state.detectionEnabled())state.vehicleTransitions().acknowledged(vehicleUniqueId, player.vehicleData, state.vehicleTransitions().lastTeleport());
            else if(player.vehicleData!=null&&player.vehicleData.vehicleUniqueId==vehicleUniqueId)player.vehicleData=null;
            return;
        }
        var linkedVehicle = vehicleRuntime == null ? null : state.entity(vehicleRuntime);
        if (linkedVehicle == null) return;
        var current = player.vehicleData;
        boolean driver = type == EntityLinkData.Type.RIDER;
        if (current != null && current.vehicleRuntimeId == vehicleRuntime && current.vehicleUniqueId == vehicleUniqueId
                && current.driver == driver && (planned==null||planned==current)) return;
        state.teleports().getQueuedTeleports().clear();
        var data = new VehicleData();
        data.vehicleRuntimeId = vehicleRuntime;
        data.vehicleUniqueId = vehicleUniqueId;
        data.driver = type == EntityLinkData.Type.RIDER;
        data.canWeControlThisVehicle = data.driver && (linkedVehicle.boat() || linkedVehicle.horse());
        state.resetMovementHistory();
        state.discardVehicleCorrections();
        if (state.detectionEnabled()) state.authInputPackets().vehicles().reset();
        if(planned!=null){planned.canWeControlThisVehicle=data.canWeControlThisVehicle;data=planned;}
        player.vehicleData = data;
        if (state.detectionEnabled()) {
            state.vehicleTransitions().mounted(data);
            if(data.driver && player.protocolVersion()>=944 && linkedVehicle.getCurrent()!=null
                    && (linkedVehicle.boat()&&ac.navro.proxy.prediction.vehicle.BoatParameters.read(linkedVehicle)!=null
                        ||linkedVehicle.horse()&&linkedVehicle.flag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SADDLED)
                        &&Float.isFinite(linkedVehicle.attribute("minecraft:movement",Float.NaN))
                        &&Float.isFinite(linkedVehicle.attribute("minecraft:horse.jump_strength",Float.NaN)))) {
                state.authInputPackets().velocityPackets().clearExternalMotion();
                state.authInputPackets().context().certainVelocity=null;
                state.authInputPackets().context().uncertainVelocity=null;
                state.authInputPackets().vehicles().mounted(linkedVehicle);
            }
        }
    }
}
