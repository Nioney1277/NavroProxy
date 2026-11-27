package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.input.PredictionData;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import ac.navro.proxy.teleport.data.RewindHistory;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;

class VehicleAckRegressionTest {
    @Test void mountDoesNotCreateAnUnanswerablePlayerTeleport() {
        var s=session(false);var packets=new ArrayList<BedrockPacket>();var tasks=new ArrayList<Runnable>();
        s.setVehicleHardCorrectionTransport(p->{packets.addAll(p);return true;});
        s.player().setTransportAcknowledgment((packet,task)->tasks.add(task));
        s.authInputPackets().vehicles().mounted(s.entity(42));
        assertEquals(2,packets.size());assertTrue(packets.stream().noneMatch(MovePlayerPacket.class::isInstance));
        assertFalse(s.teleports().isHardTeleporting());
        tasks.forEach(Runnable::run);assertFalse(s.teleports().hasPendingCorrection());
        for(int tick=10;tick<40;tick++) {
            var input=input(s,tick,8,64,8);
            assertFalse(input.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT));
            assertTrue(s.receiveClient(input,8500));
        }
        assertNotNull(s.authInputPackets().lastResult());
    }
    @Test void dismountDiscardsOldVehiclePlayerResetWithoutDroppingUnrelatedTeleport() {
        var s=session(false);s.observeClient(input(s,10,8,64,8));
        var frame=new RewindHistory(10,new Vec3(8,64.375F,8),new PredictionData(Vec3.ZERO,Vec3.ZERO,Vec3.ZERO),null,.375F,false);
        var stale=new CorrectionResetData(frame);stale.bindVehicle(s.entity(42),Vec3.ZERO,0,false,0F);
        s.teleports().getQueuedTeleports().add(stale);assertTrue(s.teleports().isHardTeleporting());
        link(s,70,EntityLinkData.Type.REMOVE);ack(s);
        assertNull(s.player().vehicleData);assertFalse(s.teleports().getQueuedTeleports().contains(stale));
        assertFalse(s.teleports().isHardTeleporting());
    }
}
