package ac.navro.proxy.command;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.config.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public final class CommandRuntime {
    public record Target(UUID identity,SessionState state) { }
    private final Map<UUID,Target> targets=new ConcurrentHashMap<>();
    private final Path configFile;
    private CheckConfig defaults;
    private volatile CheckConfig config;
    public CommandRuntime(Path file, boolean smooth, long maxBalance) {
        configFile=file;defaults=CheckConfig.defaults(smooth,maxBalance);config=defaults;
        try { config=ConfigLoader.load(configFile,defaults); }
        catch(java.io.IOException e) { System.err.println(e.getMessage()); }
    }
    public CheckConfig config() { return config; }
    public void useStartupDefaults(boolean smooth,long maxBalance) {
        defaults=CheckConfig.defaults(smooth,maxBalance);
        try { config=ConfigLoader.load(configFile,defaults); }
        catch(java.io.IOException e) { System.err.println(e.getMessage()); }
    }
    public String reload() throws java.io.IOException {
        CheckConfig loaded=ConfigLoader.load(configFile,defaults);config=loaded;return loaded.toString();
    }
    public void attach(UUID id,SessionState state) {
        targets.put(id,new Target(id,state));
        state.setCommandRuntime(this);
    }
    public void detach(UUID id,SessionState state) {
        boolean removed=targets.remove(id,new Target(id,state));state.clearDebugSubscriptions();
        if(!removed)return;

        targets.values().forEach(t->t.state().removeDebugSubscriber(id));
    }
    public Target find(String name) {
        return targets.values().stream().filter(t->t.state().player().getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
}
