package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;

class VehicleTransitionsTest {
    static PlayerAuthInputPacket foot(long tick, float x, float y, float z, boolean handled) {
        var p = AuthInputPacketsTest.input(tick, x, y, z, Vec3.ZERO);
        if (handled) p.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        else p.setDelta(Vector3f.from(0,-.0784F,0));
        return p;
    }
    static MovePlayerPacket teleport(SessionState s, long marker, float x, float y, float z) {
        var p = new MovePlayerPacket();
        p.setRuntimeEntityId(7); p.setMode(MovePlayerPacket.Mode.TELEPORT);
        p.setPosition(Vector3f.from(x, y + 1.62001F, z)); p.setOnGround(true);
        s.player().getLatencyUtil().queue(marker, true); s.observeBackend(p);
        return p;
    }
    static void leave(SessionState s) {
        var p = new InteractPacket(); p.setAction(InteractPacket.Action.LEAVE_VEHICLE); p.setRuntimeEntityId(7);
        s.observeClient(p);
    }
    static void seat(SessionState s) {
        var p = new SetEntityDataPacket(); p.setRuntimeEntityId(7);
        p.getMetadata().put(EntityDataTypes.SEAT_OFFSET, Vector3f.from(0, .375F, 0));
        s.observeBackend(p); ack(s);
    }
    static void moveBoat(SessionState s, float x) {
        var p = new MoveEntityAbsolutePacket(); p.setRuntimeEntityId(42);
        p.setPosition(Vector3f.from(x,64.375F,8)); p.setTeleported(true);
        s.observeBackend(p); s.onBackendPacketQueued(p); ack(s);
        s.observeClient(input(s,10,x,64,8));
    }

    @Test void unknownExitAfterRemoveAckWaitsForPlacementAndAcceptsALaterServerTeleport() {
        var s=session(false);s.observeClient(input(s,10,8,64,8));leave(s);
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        s.observeClient(foot(11,100,64,8,false));
        assertTrue(s.vehicleTransitions().blocked());assertNull(s.authInputPackets().lastResult());
        assertEquals(8,s.player().position().x);
        teleport(s,100,9,64,8);ack(s);s.observeClient(foot(12,9,64,8,true));
        assertFalse(s.vehicleTransitions().blocked());assertFalse(s.vehicleTransitions().awaitingPlacement());
        assertEquals(9,s.player().position().x);
    }

    @Test void playerCanStandOnTheBoatAtItsPredictedLocationAfterDismount() {
        var s=session(false);
        var collidable=new SetEntityDataPacket();collidable.setRuntimeEntityId(42);collidable.getMetadata().setFlag(EntityFlag.COLLIDABLE,true);s.observeBackend(collidable);ack(s);
        for(int tick=10;tick<30;tick++) {
            var p=input(s,tick,8,64,8);p.setInputMode(org.cloudburstmc.protocol.bedrock.data.InputMode.TOUCH);
            p.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CLASSIC);
            p.getInputData().add(PlayerAuthInputData.PADDLE_LEFT);s.observeClient(p);
        }
        var origin=s.player().position().clone();
        assertTrue(origin.distanceTo(new Vec3(8,64,8))>.05F);
        assertEquals(origin.x,s.entity(42).position().x());
        assertEquals(origin.z,s.entity(42).position().z());
        teleport(s,100,origin.x,64.455F,origin.z);link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        s.observeClient(foot(30,origin.x,64.455F,origin.z,true));
        s.observeClient(foot(31,origin.x,64.455F,origin.z,false));
        assertEquals(64.455F,s.player().position().y,1e-5);
        assertTrue(s.player().onGround());
        assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4);
        assertFalse(s.player().verticalCollision());
    }

    @Test void reenteringTheSameBoatKeepsItsPositionAndHeadingButResyncsMotion() {
        var s=session(false);
        for(int tick=10;tick<30;tick++) {
            var p=input(s,tick,8,64,8);p.setInputMode(org.cloudburstmc.protocol.bedrock.data.InputMode.TOUCH);
            p.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CLASSIC);
            p.getInputData().add(PlayerAuthInputData.PADDLE_LEFT);s.observeClient(p);
        }
        var origin=s.player().position().clone();float heading=s.authInputPackets().vehicles().yaw();
        assertTrue(Math.abs(heading-90)>10);
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        link(s,70,EntityLinkData.Type.RIDER);ack(s);
        s.observeClient(input(s,30,origin.x,origin.y,origin.z));
        assertTrue(s.player().position().distanceTo(origin)<.1F);
        assertEquals(heading,s.authInputPackets().vehicles().yaw(),1e-5);
        assertTrue(s.authInputPackets().vehicles().yaw()<heading+3);
    }

    @Test void acknowledgedRemoteMoveSupersedesTheRetainedLocalBoatPose() {
        var s=session(false);s.observeClient(input(s,10,8,64,8));
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        var move=new MoveEntityAbsolutePacket();move.setRuntimeEntityId(42);
        move.setPosition(Vector3f.from(20,64.375F,8));move.setRotation(Vector3f.from(0,25,0));
        s.player().getLatencyUtil().queue(100,true);s.observeBackend(move);s.onBackendPacketQueued(move);ack(s);
        link(s,70,EntityLinkData.Type.RIDER);ack(s);s.observeClient(input(s,11,20,64,8));
        assertEquals(20,s.player().position().x);assertEquals(25,s.authInputPackets().vehicles().yaw());
    }

    @Test void dismountAckRebuildsTheStaleRiderBoxAndClearsBoatVelocity() {
        var s = session(false); moveBoat(s,22);
        assertEquals(8,s.player().boundingBox().feetCenter().x);
        teleport(s,100,22.5F,64,8); link(s,70,EntityLinkData.Type.REMOVE); ack(s);
        assertNull(s.player().vehicleData);
        assertEquals(22.5F,s.player().position().x);
        assertEquals(22.5F,s.player().boundingBox().feetCenter().x);
        assertEquals(0,s.player().velocity().lengthSquared());
        s.observeClient(foot(11,22.5F,64,8,true));
        assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4);
        assertEquals(0,s.player().velocity().lengthSquared());
    }

    @Test void onFootInputWaitsForTeleportAckWithoutBeingInterpretedAsBoatCoordinates() {
        var s = session(false); s.observeClient(input(s,10,8,64,8)); leave(s);
        teleport(s,100,9,64,8);
        s.observeClient(foot(11,9,64,8,true));
        assertTrue(s.vehicleTransitions().blocked());
        assertEquals(8,s.player().position().x); assertNotNull(s.player().vehicleData);
        assertTrue(s.vehicleTransitions().blocked()); assertNull(s.authInputPackets().lastResult());
        ack(s); s.observeClient(foot(12,9,64,8,false));
        assertNull(s.player().vehicleData); assertEquals(9,s.player().position().x);
        assertEquals(9,s.player().boundingBox().feetCenter().x);

        assertEquals(-.0784F,s.player().velocity().y);
    }

    @Test void teleportCanEndRideBeforeRemoveAckAndLateRemoveCannotResetWalking() {
        var s = session(false); s.observeClient(input(s,10,8,64,8));
        teleport(s,100,9,64,8); ack(s);
        link(s,70,EntityLinkData.Type.REMOVE);
        s.observeClient(foot(11,9,64,8,true));
        assertNull(s.player().vehicleData); assertEquals(9,s.player().position().x);
        s.player().setVelocity(new Vec3(.25F,.1F,0));
        ack(s); assertEquals(.25F,s.player().velocity().x);
        assertTrue(s.teleports().getQueuedTeleports().isEmpty());
    }

    @Test void removeWithoutTeleportRebuildsPlayerBoxAtCurrentPositionInsteadOfRideStart() {
        var s = session(false); moveBoat(s,22);
        link(s,70,EntityLinkData.Type.REMOVE); ack(s);
        assertNull(s.player().vehicleData); assertEquals(22,s.player().boundingBox().feetCenter().x);
        assertEquals(0,s.player().velocity().lengthSquared());
    }

    @Test void movingOrBlockedLocalExitWaitsForServerPlacement() {
        var s = session(false); seat(s);
        s.observeClient(input(s,10,8,64,8)); leave(s);
        s.observeClient(foot(11,9,64,8,false));
        assertTrue(s.vehicleTransitions().blocked());
        assertNotNull(s.player().vehicleData);
        s = session(false); seat(s);
        for(int tick=10;tick<=12;tick++)s.observeClient(input(s,tick,8,64,8));
        s.world().updateBlock(8,64,7,0,block("minecraft:stone"));
        leave(s); s.observeClient(foot(13,8,64.001F,7,false));
        assertTrue(s.vehicleTransitions().blocked());
        assertNotNull(s.player().vehicleData);
    }

    @Test void removalFromAnOlderRideCannotClearARemountOfTheSameActor() {
        var s = session(false); s.observeClient(input(s,10,8,64,8));
        teleport(s,100,9,64,8); ack(s);
        link(s,70,EntityLinkData.Type.REMOVE);
        var old = s.player().vehicleData;
        s.observeClient(foot(11,9,64,8,true)); assertNull(s.player().vehicleData);

        var replacement = new ac.navro.proxy.player.data.VehicleData();
        replacement.vehicleRuntimeId=42; replacement.vehicleUniqueId=420; replacement.driver=true;
        s.player().vehicleData=replacement; s.vehicleTransitions().mounted(replacement);
        ack(s); assertSame(replacement,s.player().vehicleData); assertNotSame(old,replacement);
    }

    @Test void acceptedTeleportFollowedByVehicleInputIsNotReusedAsAnExit() {
        var s = session(false); s.observeClient(input(s,10,8,64,8));
        teleport(s,100,30,64,8); ack(s);
        s.observeClient(input(s,11,8,64,8)); assertNull(s.vehicleTransitions().lastTeleport());
        link(s,70,EntityLinkData.Type.REMOVE); ack(s);
        assertEquals(8,s.player().position().x);
    }
}
