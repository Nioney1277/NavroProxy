package ac.navro.proxy.backend;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.protocol.bedrock.packet.ScriptMessagePacket;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class NukkitBridgeParityTest {
 static ScriptMessagePacket message(String text) {var p=new ScriptMessagePacket();p.setChannel(BackendStateSync.CHANNEL);p.setMessage(text);return p;}
 @Test void nukkitReadinessEnablesAlertsAndCommandsWithoutPocketMineRules() {
  var state=AuthInputPacketsTest.session();var sync=state.backendSync();String nonce=sync.begin().getMessage().substring(6);
  sync.receive(message("nk_ready|wrong"));assertFalse(state.hasBackendCommands());assertNull(sync.alert("test"));
  sync.receive(message("nk_ready|"+nonce));assertTrue(state.hasBackendCommands());assertFalse(state.isPocketMineBackend());assertNull(sync.alert("test"));
  sync.receive(message("tick|"+nonce+"|0|100"));assertEquals("alert|"+nonce+"|test",sync.alert("test").getMessage());
  assertNotNull(sync.alert("界".repeat(1365)));assertNull(sync.alert("界".repeat(1366)));
  sync.begin();assertFalse(state.hasBackendCommands());sync.receive(message("nk_ready|"+nonce));assertFalse(state.hasBackendCommands());assertNull(sync.alert("old"));
 }
 @Test void expiredClockDoesNotForwardAlerts() {
  var state=AuthInputPacketsTest.session();var clock=new AtomicLong(0);var sync=new BackendStateSync(state,clock::get);
  String nonce=sync.begin().getMessage().substring(6);sync.receive(message("nk_ready|"+nonce));sync.receive(message("tick|"+nonce+"|0|100"));
  assertNotNull(sync.alert("fresh"));clock.set(1_000_000_001L);assertNull(sync.alert("stale"));
 }
}
