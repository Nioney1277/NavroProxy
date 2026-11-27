package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SupportedCodecs;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.*;


class VehicleHardResetFrameTest {
    @Test void completionFlagSurvivesProtocol2225EncodingAndYawUsesWirePrecision() {
        var s=session(false);var sent=new ArrayList<BedrockPacket>();var tasks=new ArrayList<Runnable>();
        s.player().setTransportAcknowledgment((p,t)->tasks.add(t));
        s.setVehicleHardCorrectionTransport(p->{sent.addAll(p);return true;});
        assertTrue(s.synchronizeVehicleMount(new Vec3(8,64,8),new Vec3(.03F,-.0392F,0),73.72234F,true,0F));
        var codec=SupportedCodecs.forProtocol(2225);var buffer=Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(),buffer,sent.getFirst());
            var packet=(MoveEntityAbsolutePacket)codec.tryDecode(codec.createHelper(),buffer,
                    codec.getPacketDefinition(MoveEntityAbsolutePacket.class).getId());
            assertTrue(packet.isTeleported());assertTrue(packet.isForceMove());assertTrue(packet.isForceCompletion());
            tasks.forEach(Runnable::run);
            assertEquals(packet.getRotation().getY(),s.authInputPackets().vehicles().yaw());
            assertEquals(73.125F,s.authInputPackets().vehicles().yaw());
        } finally {buffer.release();}
    }
    @Test void ackRebasesToSentTargetInsteadOfContinuingThePreResetTimeline() {
        var s=session(false);var tasks=new ArrayList<Runnable>();
        s.observeClient(input(s,10,8,64,8));s.observeClient(input(s,11,8,64,8));
        s.player().setTransportAcknowledgment((p,t)->tasks.add(t));
        s.setVehicleHardCorrectionTransport(p->true);
        var target=new Vec3(8,64,8);var speed=new Vec3(.02F,-.0392F,0);
        assertTrue(s.synchronizeVehicleMount(target,speed,90,true,0F));
        var input=input(s,12,8,64,8);input.setMotion(org.cloudburstmc.math.vector.Vector2f.from(0,1));s.observeClient(input);
        assertTrue(s.authInputPackets().vehicles().currentPosition().z > 8,
                "Paddling at the fixture's 90 degree yaw advances Z before the ACK");
        tasks.getFirst().run();
        assertEquals(8,s.authInputPackets().vehicles().currentPosition().x);
        assertEquals(64,s.authInputPackets().vehicles().currentPosition().y);
        assertEquals(8,s.authInputPackets().vehicles().currentPosition().z);
        assertEquals(.02F,s.player().velocity().x);
        assertEquals(-.0392F,s.player().velocity().y);
        assertFalse(s.teleports().hasPendingCorrection());
        var next=input(s,13,8.0072F,64,8);s.observeClient(next);
        assertTrue(s.authInputPackets().lastResult().accepted());
        assertEquals(0,s.authInputPackets().lastResult().rawOffset(),1e-6);
    }
    @Test void synchronousAckRebasesOnlyAfterSuccessfulSend() {
        for(boolean sent:new boolean[]{false,true}) {
            var s=session(false);
            s.player().setTransportAcknowledgment((p,t)->t.run());
            s.setVehicleHardCorrectionTransport(p->sent);
            assertEquals(sent,s.synchronizeVehicleMount(new Vec3(80,64,8),Vec3.ZERO,73.72234F,true,0F));
            assertEquals(sent?80:8,s.authInputPackets().vehicles().currentPosition().x);
            assertEquals(sent?73.125F:90F,s.authInputPackets().vehicles().yaw());
            assertFalse(s.teleports().hasPendingCorrection());
        }
    }
    @Test void throwingSendCannotRebaseEvenWhenAckRunsSynchronously() {
        var s=session(false);
        s.player().setTransportAcknowledgment((p,t)->t.run());
        s.setVehicleHardCorrectionTransport(p->{throw new IllegalStateException("send failed");});
        assertThrows(IllegalStateException.class,()->s.synchronizeVehicleMount(new Vec3(80,64,8),Vec3.ZERO,73.72234F,true,0F));
        assertEquals(8,s.authInputPackets().vehicles().currentPosition().x);
        assertEquals(90,s.authInputPackets().vehicles().yaw());
        assertFalse(s.teleports().hasPendingCorrection());
    }
    @Test void failedSendCannotRebaseOrQuantizeTheLiveVehicleState() {
        var s=session(false);
        var origin=s.authInputPackets().vehicles().currentPosition();float yaw=s.authInputPackets().vehicles().yaw();
        s.setVehicleHardCorrectionTransport(p->false);
        assertFalse(s.synchronizeVehicleMount(new Vec3(80,90,100),new Vec3(1,2,3),73.72234F,true,0F));
        assertEquals(origin.x,s.authInputPackets().vehicles().currentPosition().x);
        assertEquals(origin.y,s.authInputPackets().vehicles().currentPosition().y);
        assertEquals(yaw,s.authInputPackets().vehicles().yaw());
        assertFalse(s.teleports().hasPendingCorrection());
    }
    @Test void delayedAckCannotRewriteAReplacedRide() {
        var s=session(false);var tasks=new ArrayList<Runnable>();
        s.player().setTransportAcknowledgment((p,t)->tasks.add(t));s.setVehicleHardCorrectionTransport(p->true);
        assertTrue(s.synchronizeVehicleMount(new Vec3(80,64,8),Vec3.ZERO,0,true,0F));
        var newRide=new ac.navro.proxy.player.data.VehicleData();newRide.vehicleRuntimeId=42;newRide.vehicleUniqueId=420;newRide.driver=true;
        s.player().vehicleData=newRide;s.discardVehicleCorrections();
        tasks.forEach(Runnable::run);
        assertNotEquals(80,s.player().position().x);
    }
}
