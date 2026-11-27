package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.player.data.VehicleData;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class VehicleTransitions {
    private static final float PLAYER_OFFSET = 1.62001F;
    private final SessionState session;
    private VehicleData mount;
    private VehicleData announcedMount;
    private boolean hasAnnouncedMount;
    private TeleportData rideTeleport;
    private TeleportData appliedTeleport;
    private boolean leaveRequested, detached, removalSent;
    private boolean blocked, skipTravel, teleportPlaced;
    private long leaveRevision;
    private boolean awaitingPlacement;

    public VehicleTransitions(SessionState session) { this.session = session; }
    public boolean blocked() { return blocked; }
    public boolean skipTravel() { return skipTravel; }
    public TeleportData lastTeleport() { return rideTeleport; }
    public boolean awaitingPlacement() { return awaitingPlacement; }
    public long leaveRevision() { return leaveRevision; }

    public void clear() {
        announcedMount=null;
        hasAnnouncedMount=false;
        awaitingPlacement = false;
        mount = null;
        rideTeleport = null;
        appliedTeleport = null;
        leaveRequested = detached = removalSent = blocked = skipTravel = teleportPlaced = false;
    }

    public void mounted(VehicleData value) {
        session.vehicleHardSetback().mountApplied(value);
        var announced=announcedMount;boolean hasAnnounced=hasAnnouncedMount;
        clear();
        mount = value;
        announcedMount=announced;hasAnnouncedMount=hasAnnounced;
    }


    public VehicleData announceMount(long runtimeId,long uniqueId,boolean driver) {
        var previous=hasAnnouncedMount?announcedMount:session.player().vehicleData;
        if(previous!=null&&previous.vehicleRuntimeId==runtimeId&&previous.vehicleUniqueId==uniqueId&&previous.driver==driver)
            return previous;
        var next=new VehicleData();next.vehicleRuntimeId=runtimeId;next.vehicleUniqueId=uniqueId;next.driver=driver;
        announcedMount=next;hasAnnouncedMount=true;return next;
    }

    public VehicleData announceRemoval(long uniqueId) {
        var previous=hasAnnouncedMount?announcedMount:session.player().vehicleData;
        if(previous==null||previous.vehicleUniqueId!=uniqueId)return null;
        announcedMount=null;hasAnnouncedMount=true;return previous;
    }

    private void trackMount() {
        if (session.player().vehicleData != null && session.player().vehicleData != mount)
            mounted(session.player().vehicleData);
    }

    public void requestExit(long target) {
        trackMount();
        if (mount == null || detached || target != mount.vehicleRuntimeId && target != session.player().runtimeId()) return;
        leaveRequested = true;
        leaveRevision++;
    }


    public void refused(VehicleData expected, long revision) {
        if (mount == expected && session.player().vehicleData == expected && leaveRevision == revision) {
            leaveRequested = false;
        }
    }

    public void teleport(TeleportData data) {
        trackMount();
        rideTeleport = data;
    }

    public void removalSent(long vehicleUniqueId, long riderUniqueId) {
        trackMount();
        if (mount != null && mount.vehicleUniqueId == vehicleUniqueId && riderUniqueId == session.player().uniqueId())
            removalSent = true;
    }


    public void acknowledged(long vehicleUniqueId, VehicleData expected, TeleportData lastTeleport) {
        if(session.vehicleHardSetback().removed(vehicleUniqueId,expected))return;
        trackMount();
        if (mount == null || expected == null || detached || mount.vehicleUniqueId != vehicleUniqueId
                || mount != expected || session.player().vehicleData != mount) return;
        Vec3 position = session.player().position().clone();
        detach(position, false);

        if (rideTeleport != null && rideTeleport.isAccepted()) {
            session.resetVehiclePassengerVelocity();
            placeTeleport();
        } else {


            if (rideTeleport != null) {
                awaitingPlacement = true;
            } else {
                awaitingPlacement = !session.sendVehicleExitPlacement(position);
            }
            trace(awaitingPlacement ? "vehicle_exit_ack_wait_placement" : "vehicle_exit_resync", position);
        }
    }

    private void detach(Vec3 feet, boolean ground) {
        var wire = session.player();
        wire.vehicleData = null;
        wire.setPredictionFlag(EntityFlag.RIDING, false);


        wire.acceptTeleport(feet, ground);
        session.resetMovementHistory();
        session.discardVehicleCorrections();
        session.authInputPackets().velocityPackets().clearExternalMotion();
        session.authInputPackets().reset();
        session.authInputPackets().context().inBlockState = null;
        session.authInputPackets().context().cachedOnPos = null;
        session.authInputPackets().context().fallDistance = 0;
        detached = true;
    }

    public void forceDetach(Vec3 feet,boolean ground) {
        detach(feet,ground);rideTeleport=null;appliedTeleport=null;awaitingPlacement=false;teleportPlaced=false;
    }
    private void placeTeleport() {
        var destination = rideTeleport;
        detach(destination.getPosition().down(PLAYER_OFFSET), destination.isOnGround());
        session.teleports().setLastKnowValid(destination.getPosition().clone());
        trace("vehicle_exit_teleport", session.player().position());
        rideTeleport = null;
        awaitingPlacement = false;
        teleportPlaced = true;
        appliedTeleport = destination;
    }


    public void prepare(PlayerAuthInputPacket input) {
        blocked = skipTravel = false;
        if(session.vehicleHardSetback().waitingForRemoval()){blocked=true;return;}
        trackMount();
        if (mount == null) return;
        boolean vehicleInput = input.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        if (vehicleInput) {
            blocked = detached;

            if (!detached && rideTeleport != null && rideTeleport.isAccepted()) rideTeleport = null;
            return;
        }
        if (detached && teleportPlaced) {
            skipTravel = confirmsPlacement(input);
            teleportPlaced = false;
            return;
        }
        if (rideTeleport != null) {
            if (!rideTeleport.isAccepted()) {
                blocked = true;
                trace("vehicle_exit_wait_teleport", null);
                return;
            }
            placeTeleport();
            teleportPlaced = false;
            skipTravel = confirmsPlacement(input);
            return;
        }
        if (detached) {
            blocked = awaitingPlacement;
            if (blocked) trace("vehicle_exit_wait_placement", null);
            return;
        }


        if (leaveRequested || removalSent) {
            blocked = true;
            trace("vehicle_exit_wait", null);
        }
    }

    private boolean confirmsPlacement(PlayerAuthInputPacket input) {
        return appliedTeleport!=null&&input.getPosition()!=null&&appliedTeleport.matchesConfirmation(
                new Vec3(input.getPosition()), input.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT));
    }

    private void trace(String event, Vec3 feet) {
        var log = session.player().worldDiagnostics();
        if (log != null) log.event(event, ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "vehicle", mount.vehicleRuntimeId, "feet", feet == null ? null : feet.toString()));
    }
}
