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
    private volatile RuntimeConfiguration settings;
    private final Map<SessionState,PunishmentTracker> punishments=new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    public CommandRuntime(Path file, boolean smooth, long maxBalance) {
        this(file,smooth,maxBalance,System::currentTimeMillis);
    }
    public CommandRuntime(Path file, boolean smooth, long maxBalance,java.util.function.LongSupplier clock) {
        this.clock=clock;configFile=file;defaults=CheckConfig.defaults(smooth,maxBalance);
        settings=new RuntimeConfiguration(defaults,MessageConfig.defaults(defaults.prefix()),PunishmentConfig.defaults());
        try { settings=ConfigLoader.loadAll(configFile,defaults); }
        catch(java.io.IOException e) { System.err.println(e.getMessage()); }
    }
    public CheckConfig config() { return settings.checks(); }
    public CommandSource commandSource() { return settings.commandSource(); }
    public void bridgeCommand(SessionState caller, String action, String target, java.util.function.Consumer<String> reply) {
        if (caller.commandSource()!=CommandSource.BRIDGE || !Set.of("debug","debug2","reload","proxyping").contains(action)) return;
        var senderTarget=targets.values().stream().filter(t->t.state()==caller).findFirst().orElse(null);
        if(senderTarget==null)return;
        NavroCommand.execute(target.isEmpty()?List.of(action):List.of(action,target),new NavroCommand.Sender(){
            public UUID identity(){return senderTarget.identity();}
            public String name(){return caller.player().getName();}

            public boolean hasPermission(String node){return node.equals(action.equals("reload")?"navro.reload":action.equals("proxyping")?"navro.proxyping":"navro.debug");}
            public void sendMessage(String message){reply.accept(message);}
        },null,this);
    }
    public MessageConfig messages() { return settings.messages(); }
    public String message(String key,Map<String,?> values){return settings.messages().format(key,values);}
    public void useStartupDefaults(boolean smooth,long maxBalance) {
        defaults=CheckConfig.defaults(smooth,maxBalance);
        try { settings=ConfigLoader.loadAll(configFile,defaults); }
        catch(java.io.IOException e) { System.err.println(e.getMessage()); }
    }
    public String reload() throws java.io.IOException {
        RuntimeConfiguration loaded=ConfigLoader.loadAll(configFile,defaults);settings=loaded;return loaded.checks().toString();
    }
    public void violation(SessionState session,ac.navro.proxy.check.api.CheckViolation violation) {
        RuntimeConfiguration snapshot=settings;
        var values=new HashMap<String,Object>();
        values.put("player",session.player().getName());values.put("check_name",violation.displayName());
        values.put("check",violation.key());values.put("check_vl",violation.checkVl());
        values.put("experimental",violation.experimental()?" (Experimental)":"");
        values.put("verbose",violation.verbose());values.put("message",violation.legacyMessage());
        punishments.computeIfAbsent(session,ignored->new PunishmentTracker()).record(snapshot,violation,clock.getAsLong(),(command,count)->{
            values.put("vl",count);
            switch(command.action()) {
                case ALERT -> session.player().formattedAlert(snapshot.messages().format("alerts-format",values));
                case LOG -> session.player().punishmentLog(snapshot.messages().format("alerts-format",values));
                case KICK -> session.player().requestDisconnect(command.text().isBlank()
                        ?snapshot.messages().format("kick-message",values):snapshot.messages().render(command.text(),values));
            }
        });
    }
    public void attach(UUID id,SessionState state) {
        targets.put(id,new Target(id,state));
        state.setCommandRuntime(this);
    }
    public void detach(UUID id,SessionState state) {
        boolean removed=targets.remove(id,new Target(id,state));state.clearDebugSubscriptions();
        punishments.remove(state);
        if(!removed)return;

        targets.values().forEach(t->t.state().removeDebugSubscriber(id));
    }
    public Target find(String name) {
        return targets.values().stream().filter(t->t.state().player().getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
}
