package ac.navro.proxy.prediction.vehicle;

import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;

class VehicleSetbackModeTest {
    @Test void hardModeEndsTheRideOnlyAfterBackendRemovalThenRequiresWalkingTeleportConfirmation() {
        var s=session(false);var sent=new ArrayList<BedrockPacket>();var exits=new ArrayList<InteractPacket>();
        s.setBackendVehicleExitTransport(p->{exits.add(p);return true;});
        s.setPredictionMotionTransport(p->true);
        var teleports=ac.navro.proxy.packets.input.AuthInputPacketsTest.transport(s);
        s.setVehicleHardCorrectionTransport(p->{sent.addAll(p);return true;});
        s.setSoftCorrectionTransport(p->{fail("Hard mode must not send a soft correction");return false;});
        s.observeClient(input(s,10,800,64,8));
        assertEquals(3,sent.size());
        var unlink=assertInstanceOf(SetEntityLinkPacket.class,sent.getFirst()).getEntityLink();
        assertEquals(org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE,unlink.getType());
        assertEquals(420,unlink.getFrom());assertEquals(70,unlink.getTo());
        var actor=assertInstanceOf(MoveEntityAbsolutePacket.class,sent.get(1));
        assertTrue(actor.isTeleported());assertEquals(42,actor.getRuntimeEntityId());
        assertEquals(org.cloudburstmc.math.vector.Vector3f.ZERO,assertInstanceOf(SetEntityMotionPacket.class,sent.get(2)).getMotion());
        assertEquals(1,exits.size());assertEquals(InteractPacket.Action.LEAVE_VEHICLE,exits.getFirst().getAction());
        assertEquals(42,exits.getFirst().getRuntimeEntityId());
        assertNotNull(s.player().vehicleData);assertTrue(teleports.isEmpty());
        s.observeClient(input(s,11,800,64,8));assertTrue(s.vehicleTransitions().blocked());assertEquals(1,exits.size());
        link(s,70,org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.RIDER);ack(s);
        assertTrue(s.vehicleHardSetback().waitingForRemoval());assertTrue(teleports.isEmpty());
        link(s,70,org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE);ack(s);
        assertNull(s.player().vehicleData);assertEquals(1,teleports.size());assertTrue(s.teleports().isHardTeleporting());
        assertEquals(org.cloudburstmc.math.vector.Vector3f.from(8,65.62001F,8),teleports.getFirst().getPosition());
        ack(s);
        var missing=ac.navro.proxy.packets.input.AuthInputPacketsTest.input(12,8,64,8,ac.navro.anticheat.util.math.Vec3.ZERO);
        s.observeClient(missing);assertTrue(s.teleports().isHardTeleporting());
        var wrong=ac.navro.proxy.packets.input.AuthInputPacketsTest.input(13,80,64,8,ac.navro.anticheat.util.math.Vec3.ZERO);
        wrong.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);s.observeClient(wrong);
        assertTrue(s.teleports().isHardTeleporting());
        var right=ac.navro.proxy.packets.input.AuthInputPacketsTest.input(14,8,64,8,ac.navro.anticheat.util.math.Vec3.ZERO);
        right.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);s.observeClient(right);
        assertFalse(s.teleports().isHardTeleporting());assertFalse(s.vehicleHardSetback().pending());assertNull(s.player().vehicleData);
        var interact=new InteractPacket();interact.setAction(InteractPacket.Action.INTERACT);interact.setRuntimeEntityId(42);
        assertFalse(s.receiveClient(interact,8500));
        for(int tick=15;tick<26;tick++)s.observeClient(ac.navro.proxy.packets.input.AuthInputPacketsTest.input(tick,8,64,8,new ac.navro.anticheat.util.math.Vec3(0,-.0784F,0)));
        assertFalse(s.vehicleHardSetback().blocks(interact));
        link(s,70,org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.RIDER);ack(s);assertNotNull(s.player().vehicleData);
    }
    @Test void smoothTrueKeepsOriginalPredictionCorrection() {
        var s=session(false);var soft=new ArrayList<CorrectPlayerMovePredictionPacket>();s.setSmoothSetback(true);
        s.setVehicleHardCorrectionTransport(packets->{fail("Smooth mode must not send hard correction");return false;});
        s.setSoftCorrectionTransport(p->{soft.add(p);return true;});
        s.observeClient(input(s,10,800,64,8));assertEquals(1,soft.size());
        assertEquals(org.cloudburstmc.protocol.bedrock.data.PredictionType.VEHICLE,soft.getFirst().getPredictionType());
        assertFalse(s.teleports().isHardTeleporting());
    }
}
