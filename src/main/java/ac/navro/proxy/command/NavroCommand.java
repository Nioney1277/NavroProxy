package ac.navro.proxy.command;

import ac.navro.proxy.alert.AlertManager;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import java.util.*;


public final class NavroCommand {
    private NavroCommand() { }
    public interface Sender {
        UUID identity();
        boolean hasPermission(String node);
        void sendMessage(String message);
        default boolean isPlayer() { return !AlertManager.CONSOLE_UUID.equals(identity()); }
        default String name() { return ""; }
    }
    public static void advertise(AvailableCommandsPacket packet) {

        packet.getCommands().removeIf(c->"navro".equalsIgnoreCase(c.getName()));
        var subcommand=new CommandParamData();subcommand.setName("subcommand");subcommand.setOptional(true);
        var values=new LinkedHashMap<String,Set<CommandEnumConstraint>>();
        for(String name:List.of("alert","reload","debug","debug2"))values.put(name,Set.of());
        subcommand.setEnumData(new CommandEnumData("NavroSubcommand",values,false));
        var player=new CommandParamData();player.setName("player");player.setType(CommandParam.STRING);player.setOptional(true);
        packet.getCommands().add(new CommandData("navro","Navro anticheat management",Set.of(),
                CommandPermission.ANY,null,List.of(),new CommandOverloadData[]{
                new CommandOverloadData(false,new CommandParamData[]{subcommand,player})}));
    }
    public static boolean handle(String command,Sender sender,AlertManager manager,CommandRuntime runtime) {
        if(command==null)return false;
        var args=CommandArguments.parseArguments(command.startsWith("/")?command.substring(1):command);
        if(args.isEmpty()||!("navro".equalsIgnoreCase(args.get(0))||"navro:navro".equalsIgnoreCase(args.get(0))))return false;
        args.remove(0);
        if(args.isEmpty()){usage(sender,manager,runtime);return true;}
        String sub=args.get(0);
        if("alert".equalsIgnoreCase(sub)) {
            if(!permission(sender,"navro.alert",manager,runtime))return true;
            boolean enabled=!manager.hasAlert(sender.identity());
            if(!manager.setAlert(sender.identity(),enabled))reply(sender,manager,runtime,"alerts-save-failed",Map.of());
            else reply(sender,manager,runtime,enabled?"alerts-enabled":"alerts-disabled",Map.of());
            return true;
        }
        if("reload".equalsIgnoreCase(sub)) {
            if(!permission(sender,"navro.reload",manager,runtime))return true;
            try { String config=runtime.reload();reply(sender,manager,runtime,"reloaded",Map.of("config",config)); }
            catch(java.io.IOException e) { reply(sender,manager,runtime,"reload-failed",Map.of("error",e.getMessage())); }
            return true;
        }
        if("debug".equalsIgnoreCase(sub)||"debug2".equalsIgnoreCase(sub)) {
            if(!permission(sender,"navro.debug",manager,runtime))return true;
            boolean second="debug2".equalsIgnoreCase(sub);String mode=second?"debug2":"debug";
            if(args.size()<2&&(!second||!sender.isPlayer())) {
                reply(sender,manager,runtime,"usage",Map.of("usage",mode+" <player>"));return true;
            }
            String name=args.size()>=2?args.get(1):sender.name();var target=runtime.find(name);
            if(target==null){reply(sender,manager,runtime,"player-not-found",Map.of());return true;}
            boolean enabled=target.state().toggleDebugSubscriber(second,sender.identity(),sender::sendMessage);
            reply(sender,manager,runtime,enabled?"debug-enabled":"debug-disabled",Map.of("mode",mode,"player",target.state().player().getName()));
            return true;
        }
        reply(sender,manager,runtime,"unknown-subcommand",Map.of());usage(sender,manager,runtime);return true;
    }
    private static boolean permission(Sender sender,String node,AlertManager manager,CommandRuntime runtime) {
        if(sender.hasPermission(node))return true;
        reply(sender,manager,runtime,"no-permission",Map.of());return false;
    }
    private static void usage(Sender sender,AlertManager manager,CommandRuntime runtime) {
        for(String line:List.of("alert","reload","debug <player>","debug2 [player]"))
            reply(sender,manager,runtime,"usage",Map.of("usage",line));
    }
    private static void reply(Sender sender,AlertManager manager,CommandRuntime runtime,String key,Map<String,?> values) {
        sender.sendMessage(runtime==null?ac.navro.proxy.config.MessageConfig.defaults(manager.getPrefix()).format(key,values)
                :runtime.message(key,values));
    }
}
