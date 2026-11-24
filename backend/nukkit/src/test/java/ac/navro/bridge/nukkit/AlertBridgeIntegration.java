package ac.navro.bridge.nukkit;
import cn.nukkit.*;
import cn.nukkit.command.*;
import cn.nukkit.event.server.*;
import cn.nukkit.network.protocol.*;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import static ac.navro.bridge.nukkit.NativeStateSyncIntegration.*;
public class AlertBridgeIntegration {
 public static class Staff extends Peer {
  boolean permitted=true; List<String> messages=new ArrayList<>(); UUID uuid=UUID.randomUUID();
  Staff(){super();}
  @Override public boolean hasPermission(String permission){return permitted;}
  @Override public void sendMessage(String message){messages.add(message);}
  @Override public UUID getUniqueId(){return uuid;}
 }
 static void receive(NativeStateSync sync,Player player,String value) {
  var packet=new ScriptMessagePacket();packet.channel=NativeStateSync.CHANNEL;packet.message=value;
  var event=new DataPacketReceiveEvent(player,packet);sync.onReceive(event);check(event.isCancelled(),"Bridge channel consumed");
 }
 public static void main(String[] args)throws Exception {
  var server=(Server)U.allocateInstance(Server.class);set(null,Server.class,"instance",server);
  var plugin=new NavroFireworkBridge();set(plugin,PluginBase.class,"server",server);
  set(plugin,NavroFireworkBridge.class,"trustedProxies",Set.of("127.0.0.1","::1"));
  set(plugin,NavroFireworkBridge.class,"staffChatAlerts",true);
  var directory=Files.createTempDirectory("nukkit-alerts-");var file=directory.resolve("muted-alerts.json").toFile();
  set(plugin,NavroFireworkBridge.class,"mutedPlayers",new Config(file,Config.JSON));
  var sync=new NativeStateSync(plugin);var source=peer(sync,server);
  var staff=(Staff)U.allocateInstance(Staff.class);staff.messages=new ArrayList<>();staff.uuid=UUID.randomUUID();staff.permitted=true;
  set(server,Server.class,"playerList",new HashMap<>(Map.of(staff.uuid,staff)));
  String nonce="12345678-1234-1234-1234-123456789012";
  set(source,Player.class,"rawSocketAddress",new InetSocketAddress("192.0.2.10",19133));
  set(source,Player.class,"socketAddress",new InetSocketAddress("127.0.0.1",19133));
  receive(sync,source,"hello|"+nonce);check(!sync.hasSession(source)&&source.delivered.isEmpty(),"Forwarded IP cannot impersonate trusted raw peer");
  set(source,Player.class,"rawSocketAddress",new InetSocketAddress("127.0.0.1",19133));
  receive(sync,source,"hello|"+nonce);check(sync.hasSession(source),"Trusted raw source negotiates");
  check(((ScriptMessagePacket)source.delivered.get(0)).message.equals("nk_ready|"+nonce),"Engine-specific readiness");
  receive(sync,source,"alert|wrong|ignored");check(staff.messages.isEmpty(),"Wrong nonce rejected");
  receive(sync,source,"alert|"+nonce+"|§cNoSlow\nVL 1");check(staff.messages.equals(List.of("§cNoSlow VL 1")),"Chat alert preserves formatting and strips controls");
  staff.permitted=false;receive(sync,source,"alert|"+nonce+"|no permission");check(staff.messages.size()==1,"Permission required");staff.permitted=true;
  var command=new Command("navroalerts") {public boolean execute(CommandSender s,String label,String[] arguments){return false;}};
  check(plugin.onCommand(staff,command,"navroalerts",new String[0]),"Toggle accepted");
  check(new Config(file,Config.JSON).exists(staff.uuid.toString()),"Muted preference persisted");
  int count=staff.messages.size();receive(sync,source,"alert|"+nonce+"|muted");check(staff.messages.size()==count,"Muted staff excluded");
  plugin.onCommand(staff,command,"navroalerts",new String[0]);check(!new Config(file,Config.JSON).exists(staff.uuid.toString()),"Enabled preference persisted");
  count=staff.messages.size();receive(sync,source,"alert|"+nonce+"|"+"界".repeat(1366));check(staff.messages.size()==count,"UTF-8 byte limit enforced");
  receive(sync,source,"alert|"+nonce+"|"+"界".repeat(1365));check(staff.messages.size()==count+1,"Within byte limit accepted");
  set(plugin,NavroFireworkBridge.class,"staffChatAlerts",false);count=staff.messages.size();receive(sync,source,"alert|"+nonce+"|disabled");check(staff.messages.size()==count,"Global chat switch respected");
  set(plugin,NavroFireworkBridge.class,"staffChatAlerts",true);receive(sync,source,"hello|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");receive(sync,source,"alert|"+nonce+"|stale");check(staff.messages.size()==count,"Old connection nonce rejected");
  check(!plugin.onCommand(staff,command,"navroalerts",new String[]{"extra"}),"Extra arguments rejected");
  System.out.println("ALERT_BRIDGE_INTEGRATION_PASSED raw trust, readiness, nonce, permissions, persistent toggle, UTF-8 bounds, control stripping, chat switch");
 }
}
