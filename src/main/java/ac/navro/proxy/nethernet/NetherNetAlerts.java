package ac.navro.proxy.nethernet;

import ac.navro.proxy.alert.AlertManager;
import ac.navro.proxy.permission.ProxyPermissions;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;




final class NetherNetAlerts {
    private static final Map<UUID,Consumer<String>> ONLINE=new ConcurrentHashMap<>();
    static final ProxyPermissions PERMISSIONS=new ProxyPermissions(Path.of("permissions.json"),System.out::println);
    private static volatile ac.navro.proxy.command.CommandRuntime commands;
    static synchronized ac.navro.proxy.command.CommandRuntime commands() {
        if(commands==null)commands=new ac.navro.proxy.command.CommandRuntime(Path.of("config.yml"),false,8500);
        return commands;
    }
    static final AlertManager ALERTS=new AlertManager(Path.of("alert-subscriptions.json"),()->commands().config().prefix(),
            System.out::println,()->Map.copyOf(ONLINE));
    static void register(UUID id,Consumer<String> recipient){ONLINE.put(id,recipient);}
    static void remove(UUID id,Consumer<String> recipient){ONLINE.remove(id,recipient);}
    static synchronized void configure(boolean smooth) {
        if(commands==null)commands=new ac.navro.proxy.command.CommandRuntime(Path.of("config.yml"),smooth,8500);
        else commands.useStartupDefaults(smooth,8500);
    }
    private NetherNetAlerts(){ }
}
