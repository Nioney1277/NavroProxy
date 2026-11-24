package ac.navro.proxy.nethernet;

import ac.navro.proxy.command.CommandRuntime;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.state.WirePlayerState;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NetherNetPunishmentTest {
    @TempDir Path folder;
    @CheckInfo(name="PunishmentProbe")
    static class Probe extends PacketCheck {
        Probe(WirePlayerState player){super(player);}
        @Override public boolean onPacketReceive(BedrockPacket packet,boolean cancelled){
            if(packet instanceof ClientCacheStatusPacket)fail("probe");
            return cancelled;
        }
    }
    @Test void thresholdKickIsEncodedToTheClientAndTriggerPacketIsNotForwarded()throws Exception {
        var runtime=new CommandRuntime(folder.resolve("config.yml"),false,8500);
        Files.writeString(folder.resolve("punishments.yml"),"Punishments:\n  Probe:\n    checks: [PunishmentProbe]\n    commands: ['1:0 kick %player% Threshold reached']\n");runtime.reload();
        try(var session=new NetherNetBatchSession(null,null,true)) {
            session.process(false,HexFormat.of().parseHex("06c101000008b1"));
            session.process(true,HexFormat.of().parseHex("0c8f0101000000000000000000"));
            session.state().player().setName("Test");runtime.attach(UUID.randomUUID(),session.state());
            session.state().player().getCheckHolder().put(Probe.class,new Probe(session.state().player()));
            var packet=new ClientCacheStatusPacket();packet.setSupported(false);
            var result=session.process(false,session.encode(packet));
            assertTrue(result.backendbound().isEmpty());assertNull(result.forwarded());
            var codec=ac.navro.proxy.SupportedCodecs.forProtocol(2225);boolean found=false;
            for(byte[] bytes:result.clientbound()) {
                var buffer=Unpooled.wrappedBuffer(bytes);
                try {
                    assertEquals(255,buffer.readUnsignedByte());int size=VarInts.readUnsignedInt(buffer);
                    var data=buffer.readSlice(size);int id=VarInts.readUnsignedInt(data);
                    var decoded=codec.tryDecode(codec.createHelper(),data,id);
                    if(decoded instanceof DisconnectPacket disconnect){
                        assertEquals("Threshold reached",disconnect.getKickMessage());found=true;
                    }
                }finally{buffer.release();}
            }
            assertTrue(found);assertThrows(java.io.IOException.class,session::tick);
        }
    }
}
