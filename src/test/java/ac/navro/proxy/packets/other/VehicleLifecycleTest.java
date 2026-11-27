package ac.navro.proxy.packets.other;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.packets.other.VehicleReferenceTest.*;

class VehicleLifecycleTest {
    @Test void linksInActorSpawnCommitWithThatSpawnsClosingAck() {
        var state = session();
        var spawn = new AddEntityPacket();
        spawn.setRuntimeEntityId(45);
        spawn.setUniqueEntityId(450);
        spawn.setIdentifier("minecraft:chicken");
        spawn.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);
        spawn.setMotion(org.cloudburstmc.math.vector.Vector3f.ZERO);
        spawn.getMetadata().put(EntityDataTypes.WIDTH, .4F);
        spawn.getMetadata().put(EntityDataTypes.HEIGHT, .7F);
        spawn.getEntityLinks().add(link(420, 450, 1).getEntityLink());
        queued(state, 20, spawn);
        assertFalse(state.entity(45).inVehicle());
        spawn.getEntityLinks().clear();
        ack(state, 20);
        assertTrue(state.entity(45).inVehicle());
    }

    @Test void linksInPlayerSpawnUseTheSameUniqueIdResolutionAsStandaloneLinks() {
        var state = session();
        var spawn = new AddPlayerPacket();
        spawn.setRuntimeEntityId(45);
        spawn.setUniqueEntityId(450);
        spawn.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);
        spawn.setMotion(org.cloudburstmc.math.vector.Vector3f.ZERO);
        spawn.getEntityLinks().add(link(420, 450, 1).getEntityLink());
        queued(state, 20, spawn);
        assertFalse(state.entity(45).inVehicle());
        ack(state, 20);
        assertTrue(state.entity(45).inVehicle());
        assertNull(state.player().vehicleData);
    }

    static void teleport(SessionState s) {s.teleports().getQueuedTeleports().add(new TeleportData(Vec3.ZERO));}
    static void ack(SessionState s,long marker) {assertTrue(s.player().getLatencyUtil().onResponse(marker));}
    static InteractPacket exit(long target,InteractPacket.Action action) {var p=new InteractPacket();p.setRuntimeEntityId(target);p.setAction(action);return p;}
    static RemoveEntityPacket remove(long unique) {var p=new RemoveEntityPacket();p.setUniqueEntityId(unique);return p;}

    @Test void mountClearsOldTeleportsAndDismountPreservesUnrelatedTeleports() {
        var s=session();teleport(s);queued(s,10,link(420,70,1));
        assertNull(s.player().vehicleData);assertEquals(1,s.teleports().getQueuedTeleports().size());assertEquals(1.62001F,s.playerYOffset());
        assertFalse(s.player().getLatencyUtil().onResponse(999));assertNull(s.player().vehicleData);
        ack(s,10);assertEquals(42,s.player().vehicleData.vehicleRuntimeId);assertTrue(s.player().vehicleData.canWeControlThisVehicle);
        assertTrue(s.teleports().getQueuedTeleports().isEmpty());assertEquals(.375F,s.playerYOffset());
        s.authInputPackets().context().begin();assertSame(s.player().vehicleData,s.authInputPackets().context().vehicleData);
        teleport(s);queued(s,11,link(420,70,0));assertEquals(.375F,s.playerYOffset());assertEquals(1,s.teleports().getQueuedTeleports().size());
        ack(s,11);assertNull(s.player().vehicleData);assertEquals(1,s.teleports().getQueuedTeleports().size());assertEquals(1.62001F,s.playerYOffset());
    }
    @Test void unrelatedRemovalAndRuntimeIdDoNotInventALocalMountOrClearTeleports() {
        var s=session();queued(s,10,link(420,70,1));ack(s,10);var mounted=s.player().vehicleData;teleport(s);
        queued(s,11,link(999,70,0));ack(s,11);assertSame(mounted,s.player().vehicleData);assertEquals(1,s.teleports().getQueuedTeleports().size());
        queued(s,12,link(430,7,1));ack(s,12);assertSame(mounted,s.player().vehicleData);assertEquals(1,s.teleports().getQueuedTeleports().size());
    }
    @Test void remoteRiderFlagsUseUniqueIdLookupEvenWhenTheVehicleIsUnknown() {
        var s=session();teleport(s);queued(s,10,link(999,430,2));assertFalse(s.entity(43).inVehicle());
        ack(s,10);assertTrue(s.entity(43).inVehicle());assertFalse(s.entity(44).inVehicle());assertNull(s.player().vehicleData);
        assertEquals(1,s.teleports().getQueuedTeleports().size());queued(s,11,link(999,430,0));ack(s,11);assertFalse(s.entity(43).inVehicle());
    }
    @Test void localExitRequestKeepsTheRideUntilItsPlacementIsKnown() {
        var s=session();queued(s,10,link(420,70,1));ack(s,10);var mounted=s.player().vehicleData;teleport(s);
        s.observeClient(exit(70,InteractPacket.Action.LEAVE_VEHICLE));assertSame(mounted,s.player().vehicleData);
        s.observeClient(exit(99,InteractPacket.Action.LEAVE_VEHICLE));assertSame(mounted,s.player().vehicleData);
        s.observeClient(exit(7,InteractPacket.Action.MOUSEOVER));assertSame(mounted,s.player().vehicleData);
        assertTrue(s.receiveClient(exit(7,InteractPacket.Action.LEAVE_VEHICLE),200));assertSame(mounted,s.player().vehicleData);
        assertEquals(1,s.teleports().getQueuedTeleports().size());assertEquals(.375F,s.playerYOffset());
    }
    @Test void linkPayloadIsSnapshottedWhileUniqueIdsResolveAtTheClosingAck() {
        var s=session();var p=link(450,70,1);queued(s,10,p);
        p.setEntityLink(link(999,70,2).getEntityLink());spawn(s,45,450,false);
        ack(s,10);assertEquals(45,s.player().vehicleData.vehicleRuntimeId);assertEquals(0,s.playerYOffset());
        assertTrue(s.player().vehicleData.driver);
        queued(s,11,link(450,70,2));ack(s,11);assertFalse(s.player().vehicleData.driver);
    }
    @Test void entityIndexPreservesNativeLastUniqueBindingAndRuntimeReuseRemovalRules() {
        var s=session();spawn(s,43,420,false);assertEquals(43L,s.entityRuntimeIdByUniqueId(420));
        queued(s,10,remove(420));assertNotNull(s.entity(43));ack(s,10);
        assertNull(s.entity(43));assertNotNull(s.entity(42));assertNull(s.entityRuntimeIdByUniqueId(420));
        spawn(s,42,420,true);spawn(s,42,440,false);assertEquals(42L,s.entityRuntimeIdByUniqueId(420));
        queued(s,11,remove(420));ack(s,11);assertNull(s.entity(42));assertEquals(42L,s.entityRuntimeIdByUniqueId(440));
        teleport(s);queued(s,12,link(440,70,1));ack(s,12);assertNull(s.player().vehicleData);assertEquals(1,s.teleports().getQueuedTeleports().size());
    }
    @Test void entityRemovalResolvesTheUniqueIdBeforeClearingTheLocalVehicle() {
        var s=session();queued(s,10,link(420,70,1));ack(s,10);
        queued(s,11,remove(420));assertNotNull(s.player().vehicleData);assertNotNull(s.entity(42));ack(s,11);
        assertNull(s.entity(42));assertNull(s.player().vehicleData);assertEquals(1.62001F,s.playerYOffset());
    }
    @Test void mountedMetadataGateIsDecidedAtEnqueueWhileEarlierMetadataStillCommits() {
        var s=session();var w=s.player();queued(s,10,link(420,70,1));
        var before=new SetEntityDataPacket();before.setRuntimeEntityId(7);before.getMetadata().put(EntityDataTypes.WIDTH,.58F);queued(s,11,before);
        ack(s,11);assertNotNull(w.vehicleData);assertEquals(.58F,w.dimensions().width());long revision=w.latestPoseMetadataRevision();
        var ignored=new SetEntityDataPacket();ignored.setRuntimeEntityId(7);ignored.getMetadata().put(EntityDataTypes.WIDTH,9F);ignored.getMetadata().setFlag(EntityFlag.USING_ITEM,true);
        queued(s,12,ignored);ack(s,12);assertEquals(revision,w.latestPoseMetadataRevision());assertEquals(.58F,w.dimensions().width());
        assertFalse(w.hasFlag(4));assertNull(s.authInputPackets().itemUse().tracker().getLastAcknowledgedMetadataUsing());
        var remote=new SetEntityDataPacket();remote.setRuntimeEntityId(43);remote.getMetadata().put(EntityDataTypes.WIDTH,2F);
        queued(s,13,remote);ack(s,13);assertEquals(2F,s.entity(43).getDimensions().width());
    }
    @Test void playerSpawnsRegisterUniqueIdsAndRespawnPreservesTheirEntityCache() {
        var s=session();var p=new AddPlayerPacket();p.setRuntimeEntityId(9);p.setUniqueEntityId(90);p.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);p.setMotion(org.cloudburstmc.math.vector.Vector3f.ZERO);s.observeBackend(p);
        assertEquals(9L,s.entityRuntimeIdByUniqueId(90));assertNotNull(s.entity(9));
        var respawn=new RespawnPacket();respawn.setState(RespawnPacket.State.SERVER_READY);respawn.setPosition(org.cloudburstmc.math.vector.Vector3f.ZERO);s.observeBackend(respawn);
        assertNotNull(s.entity(9));assertEquals(9L,s.entityRuntimeIdByUniqueId(90));assertEquals(42L,s.entityRuntimeIdByUniqueId(420));
    }
}
