package ac.navro.proxy.config;

import java.util.List;
import java.util.Locale;

public record PunishmentConfig(List<Group> groups) {
    public PunishmentConfig { groups=List.copyOf(groups); }
    public static PunishmentConfig defaults() {
        return new PunishmentConfig(List.of("Timer","NegativeTimer","BadPacketsA","BadPacketsB","BadPacketsC",
                "Sprint-A","Sprint-B","Sprint-C","Sprint-D","MultiActions-A","MultiActions-B","Elytra-A",
                "RotationPlace","FarPlace","AirLiquidPlace","Phase","GroundSpoof","NoSlow","AntiKB","Collisions","Horizonal","Vertical")
                .stream().map(name->new Group(name,300000,List.of(name),List.of(Command.parse("1:1 [alert]")))).toList());
    }
    public enum Action { ALERT, LOG, KICK }
    public record Command(long threshold,long interval,Action action,String text) {
        public static Command parse(String source) {
            var matcher=java.util.regex.Pattern.compile("(\\d+):(\\d+)\\s+(.+)").matcher(source.trim());
            if(!matcher.matches())throw new IllegalArgumentException("Expected threshold:interval action: "+source);
            long threshold=Long.parseLong(matcher.group(1)),interval=Long.parseLong(matcher.group(2));
            if(threshold<1)throw new IllegalArgumentException("Punishment threshold must be positive");
            String action=matcher.group(3);
            if(action.equals("[alert]"))return new Command(threshold,interval,Action.ALERT,"");
            if(action.equals("[log]"))return new Command(threshold,interval,Action.LOG,"");
            if(action.equals("kick %player%"))return new Command(threshold,interval,Action.KICK,"");
            if(action.startsWith("kick %player% "))return new Command(threshold,interval,Action.KICK,action.substring(14));
            throw new IllegalArgumentException("Supported punishment actions: [alert], [log], kick %player% [message]");
        }
    }
    public record Group(String name,long windowMillis,List<String> checks,List<Command> commands) {
        public Group { checks=List.copyOf(checks);commands=List.copyOf(commands); }
        public boolean matches(String key,String base,String display) {
            boolean included=false;
            for(String selector:checks) {
                boolean exclude=selector.startsWith("!");String value=exclude?selector.substring(1):selector;
                if(matches(value,key)||matches(value,base)||matches(value,display)) {
                    if(exclude)return false;
                    included=true;
                }
            }
            return included;
        }
        private static boolean matches(String selector,String value) {
            String[] parts=selector.toLowerCase(Locale.ROOT).split("\\*",-1);
            String pattern=String.join(".*",java.util.Arrays.stream(parts).map(java.util.regex.Pattern::quote).toList());
            return value.toLowerCase(Locale.ROOT).matches(pattern);
        }
    }
}
