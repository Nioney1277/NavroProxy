package ac.navro.proxy.command;

import ac.navro.proxy.check.api.CheckViolation;
import ac.navro.proxy.config.PunishmentConfig;
import ac.navro.proxy.config.RuntimeConfiguration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

final class PunishmentTracker {
    private static final class Window {
        final ArrayDeque<Long> violations=new ArrayDeque<>();
        final Set<Integer> executed=new HashSet<>();
        final Map<Integer,Long> lastCount=new HashMap<>();
    }
    private RuntimeConfiguration current;
    private final Map<String,Window> windows=new HashMap<>();
    void record(RuntimeConfiguration settings,CheckViolation violation,long now,
                BiConsumer<PunishmentConfig.Command,Integer> execute) {
        if(current!=settings){windows.clear();current=settings;}
        for(var group:settings.punishments().groups()) {
            if(!group.matches(violation.key(),violation.baseName(),violation.displayName()))continue;
            Window window=windows.computeIfAbsent(group.name(),ignored->new Window());
            while(!window.violations.isEmpty() && now-window.violations.peekFirst()>=group.windowMillis())window.violations.removeFirst();
            if(window.violations.isEmpty()){window.executed.clear();window.lastCount.clear();}
            window.violations.addLast(now);
            int count=window.violations.size();
            for(int index=0;index<group.commands().size();index++) {
                var command=group.commands().get(index);
                if(count<command.threshold())continue;
                if(command.interval()==0) {
                    if(!window.executed.add(index))continue;
                } else {
                    long bucket=(count-command.threshold())/command.interval();
                    if(bucket<=window.lastCount.getOrDefault(index,-1L))continue;
                    window.lastCount.put(index,bucket);
                }
                execute.accept(command,count);
            }
        }
    }
}
