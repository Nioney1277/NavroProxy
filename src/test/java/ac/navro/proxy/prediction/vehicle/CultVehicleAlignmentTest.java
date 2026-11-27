package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;
import static ac.navro.proxy.prediction.vehicle.VehicleTransitionsTest.*;

class CultVehicleAlignmentTest {
    private static int block(SessionState s,String name) {
        return NetworkBlockStateRegistry.forProtocol(s.player().protocolVersion(),false).states().values().stream()
                .filter(v->v.is(name)).findFirst().orElseThrow().networkId();
    }
    private static List<MovePlayerPacket> placements(SessionState s) {
        var packets=new ArrayList<MovePlayerPacket>();
        s.setPredictionMotionTransport(packet->{
            assertEquals(s.player().runtimeId(),packet.getRuntimeEntityId());
            assertEquals(Vector3f.ZERO,packet.getMotion());return true;
        });
        s.setTeleportTransport(packet->{packets.add(packet);s.player().getLatencyUtil().queue(2000+packets.size(),true);s.onProxyTeleport(packet);return true;});
        return packets;
    }
    @Test void boatCenterPastStoneEdgeUsesAirFrictionWhileHullStillTouchesFloor() {
        var s=session(false);
        for(int x=8;x<16;x++)for(int z=0;z<16;z++)s.world().updateBlock(x,63,z,0,s.world().airNetworkId());
        s.observeClient(input(s,10,8,64,8));s.observeClient(input(s,11,8,64,8));
        var force=new MoveEntityAbsolutePacket();force.setRuntimeEntityId(42);
        force.setPosition(Vector3f.from(8.2F,64.375F,8));force.setTeleported(true);
        s.observeBackend(force);s.onBackendPacketQueued(force);ack(s);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(42);motion.setMotion(Vector3f.from(.1F,-.01F,0));
        s.observeBackend(motion);ack(s);
        var p=input(s,12,8.281F,64,8);s.observeClient(p);
        assertEquals(8.281F,s.player().position().x,1e-6);
        assertTrue(s.player().onGround());
        assertEquals(.081F,s.player().velocity().x,1e-6);
        assertEquals(.9F,new VehicleEnvironment(s).friction(null));
    }
    @Test void movingPlayerMountDoesNotSeedBoatAndMountResyncClearsPriorBoatVelocity() {
        var s=AuthInputPacketsTest.session();s.player().setVelocity(new Vec3(.8F,.2F,-.6F));
        var spawn=new AddEntityPacket();spawn.setRuntimeEntityId(42);spawn.setUniqueEntityId(420);
        spawn.setIdentifier("minecraft:boat");spawn.setPosition(Vector3f.from(8,64.375F,8));
        spawn.setRotation(Vector2f.from(0,90));spawn.setMotion(Vector3f.from(.3F,0,0));s.observeBackend(spawn);ack(s);
        s.setSmoothSetback(true);var corrections=new ArrayList<CorrectPlayerMovePredictionPacket>();s.setSoftCorrectionTransport(p->{corrections.add(p);return true;});
        link(s,70,EntityLinkData.Type.RIDER);assertEquals(.8F,s.player().velocity().x);ack(s);
        assertEquals(0,s.player().velocity().lengthSquared());assertEquals(1,corrections.size());
        assertEquals(Vector3f.ZERO,corrections.getFirst().getDelta());
        s.observeClient(input(s,10,8,64,8));assertEquals(8,s.player().position().x);
    }
    @Test void leaveRequestNeverGuessesAnOffsetBeforeServerConfirmsRemoval() {
        var s=session(false);seat(s);for(int t=10;t<=13;t++)s.observeClient(input(s,t,8,64,8));
        leave(s);s.observeClient(foot(14,8,64.001F,7,false));
        assertNotNull(s.player().vehicleData);assertTrue(s.vehicleTransitions().blocked());assertNull(s.authInputPackets().lastResult());
        var sent=placements(s);link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        assertNull(s.player().vehicleData);assertEquals(1,sent.size());
        assertEquals(8,sent.getFirst().getPosition().getZ());
        assertEquals(0,s.player().velocity().lengthSquared());
        ack(s);s.observeClient(foot(15,8,64,8,true));
        assertFalse(s.teleports().isTeleporting());assertFalse(s.vehicleTransitions().blocked());
    }
    @Test void pendingMountThenRemovalInOneAckEndsTheCorrectRide() {
        var s=session(false);link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        var sent=placements(s);link(s,70,EntityLinkData.Type.RIDER);link(s,70,EntityLinkData.Type.REMOVE);
        ack(s);assertNull(s.player().vehicleData);assertEquals(1,sent.size());
    }
    @Test void remountInSameAckUsesNewRideAndResetsAngularMomentum() {
        var s=session(false);var sent=placements(s);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(42);motion.setMotion(Vector3f.from(.2F,0,0));
        s.observeBackend(motion);ack(s);s.observeClient(input(s,10,8.072F,64,8));
        link(s,70,EntityLinkData.Type.REMOVE);link(s,70,EntityLinkData.Type.RIDER);ack(s);
        assertNotNull(s.player().vehicleData);assertEquals(0,s.player().velocity().lengthSquared());
        assertTrue(s.teleports().getQueuedTeleports().isEmpty());
    }
    @Test void clientReportedExitPositionCannotSelectTheResyncDestination() {
        var s=session(false);s.observeClient(input(s,10,8,64,8));leave(s);
        s.observeClient(foot(11,300,80,300,false));var sent=placements(s);
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        assertEquals(8,sent.getFirst().getPosition().getX());assertEquals(8,sent.getFirst().getPosition().getZ());
    }
    @Test void removingTheVehicleBeforePendingMountAckStillEndsTheRide() {
        var s=session(false);link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        var sent=placements(s);
        link(s,70,EntityLinkData.Type.RIDER);
        var remove=new RemoveEntityPacket();remove.setUniqueEntityId(420);
        s.player().getLatencyUtil().queue(87654,true);s.observeBackend(remove);ack(s);
        assertNull(s.player().vehicleData);assertNull(s.entity(42));assertEquals(1,sent.size());
    }
    @Test void playerImpulseHandledBeforeMountCannotLeakBackAfterDismount() {
        var s=AuthInputPacketsTest.session();
        var impulse=new SetEntityMotionPacket();impulse.setRuntimeEntityId(7);impulse.setMotion(Vector3f.from(.7F,.2F,0));
        s.player().getLatencyUtil().queue(7654,true);s.observeBackend(impulse);
        var spawn=new AddEntityPacket();spawn.setRuntimeEntityId(42);spawn.setUniqueEntityId(420);
        spawn.setIdentifier("minecraft:boat");spawn.setPosition(Vector3f.from(8,64.375F,8));
        spawn.setRotation(Vector2f.ZERO);spawn.setMotion(Vector3f.ZERO);s.observeBackend(spawn);
        link(s,70,EntityLinkData.Type.RIDER);ack(s);
        assertNull(s.authInputPackets().context().certainVelocity);
        var sent=placements(s);link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        assertEquals(0,s.player().velocity().lengthSquared());
        assertNull(s.authInputPackets().context().certainVelocity);assertEquals(1,sent.size());
    }
    @Test void teleportFlagAloneCannotAcceptAnUnrelatedDismountPosition() {
        var s=session(false);s.observeClient(input(s,10,8,64,8));
        teleport(s,98765,9,64,8);ack(s);
        s.observeClient(foot(11,40,64,8,true));
        assertNull(s.player().vehicleData);
        assertFalse(s.vehicleTransitions().skipTravel());
        assertNotEquals(40,s.player().position().x);
    }
}
