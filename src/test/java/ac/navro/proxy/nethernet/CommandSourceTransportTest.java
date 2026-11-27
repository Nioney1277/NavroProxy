package ac.navro.proxy.nethernet;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.backend.BackendStateSync;
import ac.navro.proxy.command.CommandRuntime;
import ac.navro.proxy.command.NavroCommand;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandSourceTransportTest {
    @TempDir Path folder;
    private static ScriptMessagePacket message(String text) {
        var p=new ScriptMessagePacket();p.setChannel(BackendStateSync.CHANNEL);p.setMessage(text);return p;
    }
    private static BedrockPacket decode(byte[] batch) {
        var input=Unpooled.wrappedBuffer(batch,1,batch.length-1);
        try {
            var frame=input.readSlice(VarInts.readUnsignedInt(input));int header=VarInts.readUnsignedInt(frame);
            var codec=SupportedCodecs.forProtocol(2225);var helper=codec.createHelper();new ProtocolDefinitions().configure(helper,true);
            return codec.tryDecode(helper,frame,header&1023);
        } finally { input.release(); }
    }
    private CommandRuntime runtime(String mode) throws Exception {
        Files.writeString(folder.resolve("config.yml"),"command-source: "+mode+"\n");
        return new CommandRuntime(folder.resolve("config.yml"),false,8500);
    }
    private static void negotiate(NetherNetBatchSession session) throws Exception {
        session.process(false,HexFormat.of().parseHex("06c101000008b1"));
        session.process(true,HexFormat.of().parseHex("0c8f0101000000000000000000"));
    }
    @Test void bridgeModePreservesBackendCommandPacketAndEncodesPrivateDebugReplies() throws Exception {
        try(var session=new NetherNetBatchSession(null,null,true)) {
            negotiate(session);
            var runtime=runtime("bridge");runtime.attach(UUID.randomUUID(),session.state());
            var target=new SessionState();target.player().setName("Target");runtime.attach(UUID.randomUUID(),target);
            String nonce=session.state().backendSync().begin().getMessage().substring(6);
            session.process(true,session.encode(message("nk_ready|"+nonce)));
            var negotiated=session.process(true,session.encode(message("commands_ready|"+nonce)));
            assertNull(negotiated.forwarded());
            assertEquals("commands|"+nonce+"|bridge",((ScriptMessagePacket)decode(negotiated.backendbound().getFirst())).getMessage());
            var table=new AvailableCommandsPacket();NavroCommand.advertise(table);
            byte[] wire=session.encode(table);session.setOperatorCommandTable(true);
            assertArrayEquals(wire,session.process(true,wire).forwarded());
            String payload=Base64.getEncoder().encodeToString("Target".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response=session.process(true,session.encode(message("command|"+nonce+"|0|debug|"+payload)));
            assertNull(response.forwarded());assertTrue(response.clientbound().isEmpty());
            assertTrue(target.hasDebugSubscribers());
            assertTrue(((ScriptMessagePacket)decode(response.backendbound().getFirst())).getMessage().startsWith("command_reply|"+nonce+"|debug|"));
            Thread publisher=new Thread(()->target.sendDebug(false,"from target thread"));publisher.start();publisher.join();
            var output=session.tick();assertEquals(1,output.backendbound().size());
            assertTrue(((ScriptMessagePacket)decode(output.backendbound().getFirst())).getMessage().startsWith("command_reply|"+nonce+"|debug|"));
            assertTrue(output.clientbound().isEmpty());
            var forged=session.process(false,session.encode(message("command|"+nonce+"|1|debug|"+payload)));
            assertNull(forged.forwarded());assertTrue(target.hasDebugSubscribers());
        }
    }
    @Test void proxyModeStillAddsNavroAfterBridgeReadiness() throws Exception {
        try(var session=new NetherNetBatchSession(null,null,true)) {
            negotiate(session);var runtime=runtime("proxy");runtime.attach(UUID.randomUUID(),session.state());
            String nonce=session.state().backendSync().begin().getMessage().substring(6);
            session.process(true,session.encode(message("nk_ready|"+nonce)));
            session.process(true,session.encode(message("commands_ready|"+nonce)));
            var result=session.process(true,session.encode(new AvailableCommandsPacket()));
            var packet=(AvailableCommandsPacket)decode(result.forwarded());
            assertEquals(List.of("navro"),packet.getCommands().stream().map(c->c.getName()).toList());
        }
    }
}
