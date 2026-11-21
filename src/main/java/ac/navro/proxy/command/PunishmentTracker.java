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
        final Map<Integer,Long> lastExecution=new HashMap<>();
        long sequence;
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
            if(window.violations.isEmpty()){window.executed.clear();window.lastExecution.clear();window.sequence=0;}
            window.violations.addLast(now);
            window.sequence++;
            int count=window.violations.size();
            for(int index=0;index<group.commands().size();index++) {
                var command=group.commands().get(index);
                if(count<command.threshold())continue;
                if(command.interval()==0) {
                    if(!window.executed.add(index))continue;
                } else {
                    Long previous=window.lastExecution.get(index);
                    if(previous!=null && window.sequence-previous<command.interval())continue;
                    window.lastExecution.put(index,window.sequence);
                }
                execute.accept(command,count);
            }
        }
    }
}
