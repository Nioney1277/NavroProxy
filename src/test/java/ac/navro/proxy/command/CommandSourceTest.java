package ac.navro.proxy.command;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.config.CommandSource;
import ac.navro.proxy.backend.BackendStateSync;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandSourceTest {
    @TempDir Path folder;
    static ScriptMessagePacket message(String text) {
        var p=new ScriptMessagePacket();p.setChannel(BackendStateSync.CHANNEL);p.setMessage(text);return p;
    }
    CommandRuntime runtime(String mode) throws Exception {
        Files.writeString(folder.resolve("config.yml"),"command-source: "+mode+"\n");
        return new CommandRuntime(folder.resolve("config.yml"),false,8500);
    }
    static String handshake(SessionState s,List<ScriptMessagePacket> out) {
        s.setBackendSyncTransport(p->out.add((ScriptMessagePacket)p));
        String nonce=s.backendSync().begin().getMessage().substring(6);
        s.consumeBackendSync(message("nk_ready|"+nonce));
        s.consumeBackendSync(message("commands_ready|"+nonce));
        return nonce;
    }
    static String request(String nonce,long sequence,String action,String target) {
        return "command|"+nonce+"|"+sequence+"|"+action+"|"+
                Base64.getEncoder().encodeToString(target.getBytes(StandardCharsets.UTF_8));
    }
    @Test void bridgeProxypingUsesTheCallerAndReturnsThroughItsNonce() throws Exception {
        var runtime=runtime("bridge");var s=new SessionState();s.player().setName("Observer");runtime.attach(UUID.randomUUID(),s);
        s.forwardingLatency().record(false,1_250_000);
        var out=new ArrayList<ScriptMessagePacket>();String nonce=handshake(s,out);out.clear();
        s.consumeBackendSync(message(request(nonce,0,"proxyping","")));
        assertEquals(1,out.size());
        assertTrue(out.getFirst().getMessage().startsWith("command_reply|"+nonce+"|proxyping|"));
        String text=new String(Base64.getDecoder().decode(out.getFirst().getMessage().split("\\|",4)[3]),StandardCharsets.UTF_8);
        assertTrue(text.contains("Observer 代理处理耗时"));assertTrue(text.contains("平均 1.250"));
    }
    @Test void defaultsValidationAndReloadHaveExplicitConnectionScope() throws Exception {
        var runtime=runtime("proxy");assertEquals(CommandSource.PROXY,runtime.commandSource());
        var first=new SessionState();runtime.attach(UUID.randomUUID(),first);assertTrue(first.proxyCommands());
        Files.writeString(folder.resolve("config.yml"),"command-source: bridge\n");runtime.reload();
        assertTrue(first.proxyCommands());
        var second=new SessionState();runtime.attach(UUID.randomUUID(),second);assertFalse(second.proxyCommands());
        Files.writeString(folder.resolve("config.yml"),"command-source: auto\n");
        assertThrows(java.io.IOException.class,runtime::reload);assertEquals(CommandSource.BRIDGE,runtime.commandSource());
        Files.writeString(folder.resolve("config.yml"),"smooth-setback: false\n");runtime.reload();
        assertEquals(CommandSource.PROXY,runtime.commandSource());
    }
    @Test void bridgeDebugReplyAndStreamStayBoundToTheAuthenticatedObserver() throws Exception {
        var runtime=runtime("bridge");var observer=new SessionState();observer.player().setName("Observer");
        var target=new SessionState();target.player().setName("A Player");
        UUID id=UUID.randomUUID();runtime.attach(id,observer);runtime.attach(UUID.randomUUID(),target);
        var out=new ArrayList<ScriptMessagePacket>();String nonce=handshake(observer,out);
        assertEquals("commands|"+nonce+"|bridge",out.removeFirst().getMessage());
        observer.consumeBackendSync(message(request(nonce,0,"debug","A Player")));
        assertTrue(target.hasDebugSubscribers());assertFalse(observer.hasDebugSubscribers());
        String reply=out.removeFirst().getMessage();assertTrue(reply.startsWith("command_reply|"+nonce+"|debug|"));
        target.sendDebug(false,"line one\nline two");
        String encoded=out.removeFirst().getMessage().split("\\|",4)[3];
        assertEquals("line one\nline two",new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8));
        observer.consumeBackendSync(message(request(nonce,0,"debug","A Player")));
        assertTrue(target.hasDebugSubscribers());assertTrue(out.isEmpty());
        observer.consumeBackendSync(message(request("wrong",1,"debug","A Player")));assertTrue(target.hasDebugSubscribers());
        observer.consumeBackendSync(message(request(nonce,1,"debug","A Player")));assertFalse(target.hasDebugSubscribers());out.clear();
        observer.consumeBackendSync(message(request(nonce,2,"debug2","")));assertTrue(observer.hasDebugSubscribers());out.clear();
        observer.backendSync().begin();observer.sendDebug(true,"stale");assertTrue(out.isEmpty());
        runtime.detach(id,observer);assertFalse(observer.hasDebugSubscribers());
    }
    @Test void proxyModeDoesNotAcceptBridgeCommandsOrDuplicateAlertDelivery() throws Exception {
        var runtime=runtime("proxy");var s=new SessionState();runtime.attach(UUID.randomUUID(),s);
        var out=new ArrayList<ScriptMessagePacket>();String nonce=handshake(s,out);
        assertEquals("commands|"+nonce+"|proxy",out.removeFirst().getMessage());
        s.consumeBackendSync(message(request(nonce,0,"debug2","")));assertFalse(s.hasDebugSubscribers());
        s.consumeBackendSync(message("tick|"+nonce+"|0|10"));s.forwardBackendAlert("alert");
        assertEquals("alert|"+nonce+"|alert",out.removeFirst().getMessage());
    }
    @Test void onlyNegotiatedCommandsCanExecuteAndTokensDoNotBecomeArguments() throws Exception {
        var runtime=runtime("bridge");var s=new SessionState();runtime.attach(UUID.randomUUID(),s);
        var quoted=new SessionState();quoted.player().setName("A \" B");runtime.attach(UUID.randomUUID(),quoted);
        var out=new ArrayList<ScriptMessagePacket>();s.setBackendSyncTransport(p->out.add((ScriptMessagePacket)p));
        String nonce=s.backendSync().begin().getMessage().substring(6);
        s.consumeBackendSync(message(request(nonce,0,"debug2","")));assertFalse(s.hasDebugSubscribers());
        s.consumeBackendSync(message("nk_ready|"+nonce));s.consumeBackendSync(message("commands_ready|"+nonce));out.clear();
        s.consumeBackendSync(message(request(nonce,1,"alert","")));assertTrue(out.isEmpty());
        s.consumeBackendSync(message(request(nonce,2,"debug","A \" B")));assertTrue(quoted.hasDebugSubscribers());
    }
    @Test void proxyAdvertisementReplacesBridgeNamesAndKeepsUnrelatedCommands() {
        var table=new AvailableCommandsPacket();NavroCommand.advertise(table);
        var original=table.getCommands().getFirst();
        table.getCommands().add(new CommandData("bridge:navro",original.getDescription(),Set.of(),CommandPermission.ANY,
                null,List.of(),original.getOverloads()));
        table.getCommands().add(new CommandData("help","Help",Set.of(),CommandPermission.ANY,null,List.of(),new CommandOverloadData[0]));
        NavroCommand.advertise(table);
        assertEquals(List.of("help","navro"),table.getCommands().stream().map(CommandData::getName).toList());
    }
}
