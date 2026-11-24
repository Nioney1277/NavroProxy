package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
class BadPacketsCTest {
    @Test void formerlyDNowCChecksRespawnWithoutCancellingBackendProcessing() {
        var player=new WirePlayerState();var alerts=new ArrayList<String>();player.setAlertSink(alerts::add);
        var check=new BadPacketsC(player);
        assertFalse(check.onPacketReceive(new RespawnPacket(),false));
        assertEquals(1,alerts.size());assertTrue(alerts.get(0).contains("BadPacketsC"));
        player.acknowledgedDead=true;check.onPacketReceive(new RespawnPacket(),false);
        assertEquals(1,alerts.size());
    }
}
