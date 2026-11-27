package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleHardSetbackTest {
    @Test void failedClientSendNeverRequestsBackendDismount() {
        var s=session(false);var mount=s.player().vehicleData;var exits=new ArrayList<InteractPacket>();
        s.setBackendVehicleExitTransport(p->{exits.add(p);return true;});s.setVehicleHardCorrectionTransport(p->false);
        assertFalse(s.correctVehicle(new Vec3(8,64,8),Vec3.ZERO,90,true,0F));
        assertTrue(exits.isEmpty());assertFalse(s.vehicleHardSetback().pending());assertSame(mount,s.player().vehicleData);assertFalse(s.player().disconnectRequested());
    }
    @Test void backendTransportFailureCannotLeaveAnActiveGhostRide() {
        var s=session(false);s.setVehicleHardCorrectionTransport(p->true);s.setBackendVehicleExitTransport(p->false);
        assertFalse(s.correctVehicle(new Vec3(8,64,8),Vec3.ZERO,90,true,0F));
        assertTrue(s.player().disconnectRequested());assertFalse(s.vehicleHardSetback().pending());
    }
    @Test void unrelatedRemovalCannotFinishTheSetbackAndRemovalOfActorCan() {
        var s=session(false);var mount=s.player().vehicleData;
        s.setVehicleHardCorrectionTransport(p->true);s.setBackendVehicleExitTransport(p->true);s.setPredictionMotionTransport(p->true);
        var moves=AuthInputPacketsTest.transport(s);
        assertTrue(s.correctVehicle(new Vec3(8,64,8),Vec3.ZERO,90,true,0F));
        assertFalse(s.vehicleHardSetback().removed(999,mount));assertTrue(moves.isEmpty());
        var remove=new RemoveEntityPacket();remove.setUniqueEntityId(420);s.observeBackend(remove);ack(s);
        assertNull(s.player().vehicleData);assertEquals(1,moves.size());assertTrue(s.teleports().isHardTeleporting());
    }
    @Test void newServerMountSupersedesOldSetbackAndItsDelayedRemoval() {
        var s=session(false);s.setVehicleHardCorrectionTransport(p->true);s.setBackendVehicleExitTransport(p->true);
        var moves=AuthInputPacketsTest.transport(s);
        assertTrue(s.correctVehicle(new Vec3(80,64,8),Vec3.ZERO,90,true,0F));
        var spawn=new AddEntityPacket();spawn.setRuntimeEntityId(43);spawn.setUniqueEntityId(430);spawn.setIdentifier("minecraft:pig");
        spawn.setPosition(Vector3f.from(9,64,8));spawn.setMotion(Vector3f.ZERO);spawn.setRotation(Vector2f.ZERO);
        spawn.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.WIDTH,.9F);
        spawn.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.HEIGHT,.9F);
        s.observeBackend(spawn);ack(s);assertNotNull(s.entity(43));
        var other=new SetEntityLinkPacket();other.setEntityLink(new EntityLinkData(430,70,EntityLinkData.Type.RIDER,false,false,0));
        s.player().getLatencyUtil().queue(12345,true);
        var oldRemoval=new SetEntityLinkPacket();oldRemoval.setEntityLink(new EntityLinkData(420,70,EntityLinkData.Type.REMOVE,false,false,0));
        s.observeBackend(oldRemoval);s.observeBackend(other);
        var premature=input(s,10,9,64,8);premature.setPredictedVehicle(43);s.observeClient(premature);
        assertTrue(s.vehicleTransitions().blocked());assertEquals(42,s.player().vehicleData.vehicleRuntimeId);
        ack(s);assertEquals(43,s.player().vehicleData.vehicleRuntimeId);assertFalse(s.vehicleHardSetback().pending());
        assertFalse(s.vehicleHardSetback().waitingForRemoval());
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        assertEquals(43,s.player().vehicleData.vehicleRuntimeId);assertTrue(moves.isEmpty());
    }
    @Test void missingBackendConfirmationTimesOutWithoutAcceptingClientCoordinates() {
        var s=session(false);s.setVehicleHardCorrectionTransport(p->true);s.setBackendVehicleExitTransport(p->true);
        var clock=new AtomicLong();var setback=new VehicleHardSetback(s,clock::get);
        assertTrue(setback.request(new Vec3(8,64,8),90,true));
        clock.set(s.maxLatencyWait()*1_000_000L+1);setback.tick();
        assertTrue(s.player().disconnectRequested());assertFalse(setback.pending());assertEquals(8,s.player().position().x);
    }
    @Test void failedOrThrowingPlacementTransportCannotLeaveAnUntrackedDetachedPlayer() {
        for(boolean motionFailure:new boolean[]{false,true})for(boolean throwing:new boolean[]{false,true}) {
            var s=session(false);s.setVehicleHardCorrectionTransport(p->true);s.setBackendVehicleExitTransport(p->true);
            s.setPredictionMotionTransport(p->{if(motionFailure&&throwing)throw new IllegalStateException("motion failed");return !motionFailure;});
            s.setTeleportTransport(p->{if(throwing)throw new IllegalStateException("placement failed");return false;});
            assertTrue(s.correctVehicle(new Vec3(8,64,8),Vec3.ZERO,90,true,0F));
            link(s,70,EntityLinkData.Type.REMOVE);ack(s);
            assertTrue(s.player().disconnectRequested());assertFalse(s.vehicleHardSetback().pending());
            assertTrue(s.teleports().getQueuedTeleports().isEmpty());assertNull(s.player().vehicleData);
        }
    }
}
