package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.player.data.VehicleData;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.List;
import java.util.function.LongSupplier;


public final class VehicleHardSetback {
    private final SessionState session;
    private final LongSupplier clock;
    private Pending pending;
    private VehicleData supersededMount;
    private long cooldownUntil=-1;
    private long cooldownVehicle=Long.MIN_VALUE;
    private static final class Pending {
        final VehicleData mount;
        final ac.navro.proxy.compensated.cache.entity.EntityCache actor;
        final Vec3 target;
        final boolean ground;
        final long started;
        boolean removed;
        TeleportData placement;
        Pending(VehicleData mount,ac.navro.proxy.compensated.cache.entity.EntityCache actor,Vec3 target,boolean ground,long started) {
            this.mount=mount;this.actor=actor;this.target=target.clone();this.ground=ground;this.started=started;
        }
    }
    public VehicleHardSetback(SessionState session) { this(session,System::nanoTime); }
    VehicleHardSetback(SessionState session,LongSupplier clock) { this.session=session;this.clock=clock; }
    public boolean pending() { return pending!=null; }
    public boolean waitingForRemoval() { return supersededMount!=null||pending!=null&&!pending.removed; }
    private boolean interactionBlocked() { return supersededMount!=null||pending!=null||session.player().tick()<cooldownUntil; }
    public boolean guardingMounts() {return interactionBlocked();}
    public boolean blocks(BedrockPacket packet) {
        if(!interactionBlocked())return false;
        return packet instanceof InteractPacket interact&&(interact.getAction()==InteractPacket.Action.INTERACT||interact.getAction()==InteractPacket.Action.DAMAGE)
                ||packet instanceof InventoryTransactionPacket transaction&&transaction.getTransactionType()==InventoryTransactionType.ITEM_USE_ON_ENTITY;
    }
    public boolean suppresses(EntityLinkData link) {
        if(pending==null&&supersededMount==null)return false;
        if(link.getTo()!=session.player().uniqueId()||link.getType()==EntityLinkData.Type.REMOVE)return false;
        long expected=pending!=null?pending.mount.vehicleUniqueId:supersededMount!=null?supersededMount.vehicleUniqueId:cooldownVehicle;
        if(link.getFrom()!=expected){
            if(pending!=null)supersededMount=pending.mount;
            pending=null;cooldownUntil=-1;cooldownVehicle=Long.MIN_VALUE;return false;
        }
        return true;
    }
    public boolean request(Vec3 target,float yaw,boolean ground) {
        var mount=session.player().vehicleData;
        var actor=mount==null?null:session.entity(mount.vehicleRuntimeId);
        if(pending!=null||supersededMount!=null||mount==null||actor==null)return false;
        var operation=new Pending(mount,actor,target,ground,clock.getAsLong());
        var remove=new SetEntityLinkPacket();
        remove.setEntityLink(new EntityLinkData(mount.vehicleUniqueId,session.player().uniqueId(),EntityLinkData.Type.REMOVE,true,false,0));
        var move=new MoveEntityAbsolutePacket();move.setRuntimeEntityId(mount.vehicleRuntimeId);
        move.setPosition(target.up(actor.getYOffset()).toVector3f());move.setRotation(org.cloudburstmc.math.vector.Vector3f.from(0,yaw,0));
        move.setTeleported(true);move.setForceMove(true);move.setForceCompletion(true);move.setOnGround(ground);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(mount.vehicleRuntimeId);motion.setMotion(org.cloudburstmc.math.vector.Vector3f.ZERO);
        pending=operation;
        try {
            if(!session.sendVehicleHardPackets(List.of(remove,move,motion))) {pending=null;return false;}
            session.vehicleTransitions().requestExit(mount.vehicleRuntimeId);
            if(!session.requestBackendVehicleExit(mount.vehicleRuntimeId)) {
                fail("Could not synchronize vehicle dismount with backend");return false;
            }
            trace("vehicle_hard_dismount_sent",operation);return true;
        } catch(RuntimeException|Error error) {fail("Vehicle setback transport failed");throw error;}
    }
    public boolean removed(long vehicleUniqueId,VehicleData expected) {
        if(supersededMount!=null&&supersededMount==expected&&expected.vehicleUniqueId==vehicleUniqueId)return true;
        var operation=pending;
        if(operation==null||operation.removed||operation.mount!=expected||expected.vehicleUniqueId!=vehicleUniqueId)return false;
        if(session.player().vehicleData!=expected||session.entity(expected.vehicleRuntimeId)!=operation.actor) {clear();return false;}
        operation.removed=true;
        try {
            session.vehicleTransitions().forceDetach(operation.target,operation.ground);
            if(!session.resetVehiclePassengerVelocity()){fail("Could not reset rider motion");return true;}
            var packet=new MovePlayerPacket();packet.setRuntimeEntityId(session.player().runtimeId());
            packet.setMode(MovePlayerPacket.Mode.TELEPORT);packet.setPosition(operation.target.up(1.62001F).toVector3f());
            var look=session.player().rawInputRotation();
            packet.setRotation(look==null?org.cloudburstmc.math.vector.Vector3f.ZERO:org.cloudburstmc.math.vector.Vector3f.from(look.x(),look.y(),look.z()));
            packet.setOnGround(operation.ground);packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.UNKNOWN);
            if(!session.sendVehicleHardPlacement(packet)) {fail("Could not send vehicle exit placement");return true;}
            for(var teleport:session.teleports().getQueuedTeleports())operation.placement=teleport;
            if(operation.placement==null){fail("Vehicle exit placement was not queued");return true;}
            trace("vehicle_hard_backend_removed",operation);return true;
        } catch(RuntimeException|Error error) {fail("Vehicle exit placement transport failed");throw error;}
    }
    public void afterInput(PlayerAuthInputPacket input) {
        var operation=pending;
        if(operation==null||!operation.removed||operation.placement==null)return;
        if(operation.placement.isAccepted()&&!session.teleports().getQueuedTeleports().contains(operation.placement)
                &&!input.getInputData().contains(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)) {
            cooldownUntil=session.player().tick()+10;cooldownVehicle=operation.mount.vehicleUniqueId;
            pending=null;trace("vehicle_hard_setback_confirmed",operation);
        }
    }
    public void tick() {
        var operation=pending;
        if(operation!=null&&clock.getAsLong()-operation.started>java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(session.maxLatencyWait()))
            fail("Timed out synchronizing vehicle setback");
    }
    public void mountApplied(VehicleData mount) {
        if(pending!=null&&pending.mount!=mount||supersededMount!=null&&supersededMount!=mount)clear();
    }
    public void clear() {pending=null;supersededMount=null;cooldownUntil=-1;cooldownVehicle=Long.MIN_VALUE;}
    private void fail(String message) {clear();session.player().requestDisconnect(message);}
    private void trace(String name,Pending operation) {
        var log=session.player().worldDiagnostics();
        if(log!=null)log.event(name,ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "vehicle",operation.mount.vehicleRuntimeId,"target",operation.target.toString()));
    }
}
