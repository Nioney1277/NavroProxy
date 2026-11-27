package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SupportedCodecs;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static ac.navro.proxy.prediction.vehicle.VehiclePredictionTest.session;
import static org.junit.jupiter.api.Assertions.*;

class VehicleSmallTeleportRegressionTest {
    @Test void encodedSmallOffsetEndsTheRideWithoutAnyIntermediatePosition() {
        var s=session(false);s.player().setProtocolVersion(2225);
        var sent=new ArrayList<BedrockPacket>();
        s.setBackendVehicleExitTransport(p->true);
        s.setVehicleHardCorrectionTransport(p->{sent.addAll(p);return true;});
        assertTrue(s.correctVehicle(new Vec3(150.615F,4,163.6645F),Vec3.ZERO,149.26674F,true,0F));
        assertEquals(3,sent.size());
        var codec=SupportedCodecs.forProtocol(2225);
        var decoded=new ArrayList<BedrockPacket>();
        for(var packet:sent) {
            var buffer=Unpooled.buffer();
            try {
                codec.tryEncode(codec.createHelper(),buffer,packet);
                decoded.add(codec.tryDecode(codec.createHelper(),buffer,codec.getPacketDefinition(packet.getClass()).getId()));
            } finally {buffer.release();}
        }
        var unlink=assertInstanceOf(SetEntityLinkPacket.class,decoded.getFirst()).getEntityLink();
        assertEquals(org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE,unlink.getType());
        assertEquals(420,unlink.getFrom());assertEquals(70,unlink.getTo());
        assertEquals(Vector3f.from(150.615F,4.375F,163.6645F),assertInstanceOf(MoveEntityAbsolutePacket.class,decoded.get(1)).getPosition());
        assertEquals(Vector3f.ZERO,assertInstanceOf(SetEntityMotionPacket.class,decoded.getLast()).getMotion());
        assertTrue(s.vehicleHardSetback().waitingForRemoval());
        assertEquals(1,decoded.stream().filter(MoveEntityAbsolutePacket.class::isInstance).count());
    }
}
