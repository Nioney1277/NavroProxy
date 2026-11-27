package ac.navro.proxy.command;

import ac.navro.proxy.alert.AlertManager;
import ac.navro.proxy.permission.*;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.SupportedCodecs;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertCommandTest {
    @TempDir Path directory;
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000007");
    static final String PREFIX="§aNavro §7>§r ";
    @Test void bareRootAndNamespacedRootDisplayUsageWithoutForwardingToTheBackend(){
        var messages=new ArrayList<String>();var manager=new AlertManager(directory.resolve("alerts.json"),PREFIX,s->{},Map::of);
        var sender=new NavroCommand.Sender(){public UUID identity(){return ID;}public boolean hasPermission(String n){return false;}public void sendMessage(String m){messages.add(m);}};
        assertTrue(NavroCommand.handle("/navro",sender,manager,null));
        assertTrue(NavroCommand.handle("/navro:navro",sender,manager,null));
        assertEquals(List.of(PREFIX+"Usage: /navro alert",PREFIX+"Usage: /navro reload",PREFIX+"Usage: /navro debug <player>",PREFIX+"Usage: /navro debug2 [player]",PREFIX+"Usage: /navro proxyping [player]",
                PREFIX+"Usage: /navro alert",PREFIX+"Usage: /navro reload",PREFIX+"Usage: /navro debug <player>",PREFIX+"Usage: /navro debug2 [player]",PREFIX+"Usage: /navro proxyping [player]"),messages);
        assertFalse(manager.hasAlert(ID));
    }
    @Test void originalPermissionDenialToggleAndOptInDeliverySurviveReconnectAndPermissionRemoval()throws Exception{
        var messages=new ArrayList<String>();var console=new ArrayList<String>();var online=new HashMap<UUID,java.util.function.Consumer<String>>();online.put(ID,messages::add);
        var manager=new AlertManager(directory.resolve("alerts.json"),PREFIX,console::add,()->online);var file=directory.resolve("permissions.json");var permissions=new ProxyPermissions(file,console::add);
        var sender=new NavroCommand.Sender(){public UUID identity(){return ID;}public boolean hasPermission(String node){return permissions.hasPermission(ID,node,false);}public void sendMessage(String s){messages.add(s);}};
        assertTrue(NavroCommand.handle("/navro alert",sender,manager,null));assertEquals(List.of("§cYou do not have permission to use this command."),messages);assertFalse(manager.hasAlert(ID));messages.clear();
        Files.writeString(file,"{\"players\":{\""+ID+"\":{\"navro.alert\":true}}}");assertTrue(NavroCommand.handle("/NAVRO \"alert\" ignored",sender,manager,null));assertEquals(PREFIX+"§fEnabled alerts.",messages.remove(0));
        Files.writeString(file,"{\"players\":{}}");manager.alert("test");assertEquals(PREFIX+"§3test",messages.remove(0));assertTrue(manager.hasAlert(ID));
        var restored=new AlertManager(directory.resolve("alerts.json"),PREFIX,console::add,()->online);assertTrue(restored.hasAlert(ID));
        Files.writeString(file,"{\"players\":{\""+ID+"\":{\"navro.alert\":true}}}");assertTrue(NavroCommand.handle("/navro:navro alert",sender,restored,null));assertEquals(PREFIX+"§fDisabled alerts.",messages.remove(0));restored.alert("hidden");assertTrue(messages.isEmpty());assertTrue(console.isEmpty());
    }
    @Test void operatorDefaultsExplicitDenialsAndMalformedConfigurationRemainDistinct()throws Exception{
        var file=directory.resolve("permissions.json");var permissions=new ProxyPermissions(file,s->{});assertTrue(permissions.hasPermission(ID,"navro.alert",true));assertFalse(permissions.hasPermission(ID,"navro.alert",false));assertFalse(permissions.hasPermission(ID,"navro.exempt",true));
        Files.writeString(file,"{\"players\":{\""+ID+"\":{\"navro.alert\":false}}}");assertFalse(permissions.hasPermission(ID,"navro.alert",true));Files.writeString(file,"{bad");assertFalse(permissions.hasPermission(ID,"navro.alert",true));assertFalse(permissions.hasPermission(null,"navro.alert",true));
    }
    @Test void failedSubscriptionSaveUsesTheOriginalMessageAndDoesNotClaimSuccess()throws Exception{
        Path blocker=directory.resolve("file");Files.writeString(blocker,"x");var manager=new AlertManager(blocker.resolve("alerts.json"),PREFIX,s->{},Map::of);var messages=new ArrayList<String>();
        var sender=new NavroCommand.Sender(){public UUID identity(){return ID;}public boolean hasPermission(String n){return true;}public void sendMessage(String m){messages.add(m);}};
        assertTrue(NavroCommand.handle("/navro alert",sender,manager,null));assertFalse(manager.hasAlert(ID));assertEquals(List.of(PREFIX+"§cCould not save alerts preference."),messages);assertFalse(NavroCommand.handle("/other alert",sender,manager,null));assertTrue(NavroCommand.handle("/navro unknown",sender,manager,null));
    }
    @Test void actualMotAndPmmpAbilityBytesUseOneProtocolPermissionBinding()throws Exception{
        int count=0;Set<String> engines=new HashSet<>();
        for(String resource:new String[]{"/native-abilities.jsonl","/pmmp-abilities.jsonl"})try(var reader=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(resource)))){
            for(String line;(line=reader.readLine())!=null;){var row=JsonParser.parseString(line).getAsJsonObject();var codec=SupportedCodecs.forProtocol(2193);var bytes=Unpooled.wrappedBuffer(Base64.getDecoder().decode(row.get("bytes").getAsString()));
                try{int id=VarInts.readUnsignedInt(bytes);var packet=(UpdateAbilitiesPacket)codec.tryDecode(codec.createHelper(),bytes,id);assertEquals(0,bytes.readableBytes());var player=new WirePlayerState();var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(9002);player.apply(start);var permissions=new ProtocolPermissions();permissions.observe(player,packet);assertEquals(row.get("player").getAsInt()==2||row.get("command").getAsInt()==1,permissions.isOperator());packet.setUniqueEntityId(7);permissions.observe(player,packet);permissions.observe(player,start);assertFalse(permissions.isOperator());}
                finally{bytes.release();}engines.add(row.get("engine").getAsString());count++;
            }
        }
        assertEquals(44,count);assertEquals(Set.of("nukkit","pmmp"),engines);
    }
    @Test void commandAdvertisementIsIdempotentAndEncodesWithTheProtocolCodec(){
        var packet=new AvailableCommandsPacket();NavroCommand.advertise(packet);NavroCommand.advertise(packet);assertEquals(1,packet.getCommands().size());var command=packet.getCommands().get(0);assertEquals("Navro anticheat management",command.getDescription());assertEquals(Set.of("alert","reload","debug","debug2","proxyping"),command.getOverloads()[0].getOverloads()[0].getEnumData().getValues().keySet());
        for(int protocol:new int[]{924,944,975,1001,2168,2169,2193,2225}){
            var codec=SupportedCodecs.forProtocol(protocol);var buffer=Unpooled.buffer();
            try{
                codec.tryEncode(codec.createHelper(),buffer,packet);assertTrue(buffer.readableBytes()>0,"protocol "+protocol);
                var decoded=(AvailableCommandsPacket)codec.tryDecode(codec.createHelper(),buffer,codec.getPacketDefinition(AvailableCommandsPacket.class).getId());
                assertEquals("navro",decoded.getCommands().get(0).getName(),"protocol "+protocol);assertEquals(0,buffer.readableBytes());
            }finally{buffer.release();}
        }
    }
}
