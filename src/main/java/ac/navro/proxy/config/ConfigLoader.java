package ac.navro.proxy.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;


public final class ConfigLoader {
    public static final Path DEFAULT_FILE=Path.of("plugins","Navro","config.yml");
    private ConfigLoader() { }
    public static RuntimeConfiguration loadAll(Path file,CheckConfig defaults)throws IOException {
        CheckConfig checks=load(file,defaults);
        Path messagesFile=file.resolveSibling("messages.yml");
        boolean migratePrefix=!Files.exists(messagesFile);
        ensureResource(messagesFile,"messages.yml");
        ensureResource(file.resolveSibling("punishments.yml"),"punishments.yml");
        var legacy=read(file);
        if(migratePrefix && legacy.containsKey("prefix")) {
            String source=Files.readString(messagesFile);
            var dumper=new org.yaml.snakeyaml.DumperOptions();
            dumper.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED);
            String encoded=new Yaml(dumper).dump(checks.prefix()).strip();
            Files.writeString(messagesFile,source.replaceFirst("(?m)^prefix:.*$",
                    java.util.regex.Matcher.quoteReplacement("prefix: "+encoded)));
        }
        Map<?,?> messageValues=read(messagesFile);
        var templates=new LinkedHashMap<>(MessageConfig.defaults(checks.prefix()).templates());
        for(var entry:messageValues.entrySet()) {
            if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value))
                throw new IOException("messages.yml must contain text templates");
            templates.put(key,value);
        }
        if(migratePrefix && legacy.containsKey("prefix"))templates.put("prefix",checks.prefix());
        MessageConfig messages=new MessageConfig(templates);
        var punishments=readPunishments(file.resolveSibling("punishments.yml"));
        if(migratePrefix && legacy.containsKey("prefix")) {
            Files.writeString(file,Files.readString(file).replaceFirst("(?m)^prefix:[^\\r\\n]*(?:\\r?\\n)?", ""));
        }
        checks=new CheckConfig(messages.prefix(),checks.rewindHistory(),checks.smoothSetback(),
                checks.acceptanceThreshold(),checks.alertThreshold(),checks.disabledChecks(),checks.maxLatencyWait(),checks.maxBalanceAdvantage());
        return new RuntimeConfiguration(checks,messages,punishments);
    }
    private static void ensureResource(Path file,String resourceName)throws IOException {
        if(Files.exists(file))return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        try(var resource=ConfigLoader.class.getResourceAsStream("/"+resourceName)) {
            if(resource==null)throw new IOException("Missing bundled "+resourceName);
            Files.copy(resource,file);
        }
    }
    private static Map<?,?> read(Path file)throws IOException {
        try(var reader=Files.newBufferedReader(file)) {
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
            Object parsed=new Yaml(new SafeConstructor(options)).load(reader);
            if(parsed==null)return Map.of();
            if(!(parsed instanceof Map<?,?> map))throw new IllegalArgumentException("Root must be a mapping");
            return map;
        }catch(RuntimeException error){throw new IOException("Could not load "+file.getFileName()+": "+error.getMessage(),error);}
    }
    private static PunishmentConfig readPunishments(Path file)throws IOException {
        try {
            Object root=read(file).get("Punishments");
            if(!(root instanceof Map<?,?> groups))throw new IllegalArgumentException("Punishments must be a mapping");
            var result=new ArrayList<PunishmentConfig.Group>();
            for(var entry:groups.entrySet()) {
                if(!(entry.getKey() instanceof String name)||!(entry.getValue() instanceof Map<?,?> group))
                    throw new IllegalArgumentException("Each punishment group must have a name and settings");
                Number seconds=number(group,"remove-violations-after",300);
                long window=Math.multiplyExact(seconds.longValue(),1000);
                if(window<1000||seconds.doubleValue()!=seconds.longValue())throw new IllegalArgumentException("Violation window must be positive whole seconds");
                Object checks=group.get("checks"),commands=group.get("commands");
                if(!(checks instanceof List<?> selectors)||selectors.isEmpty()||selectors.stream().anyMatch(v->!(v instanceof String s)||s.isBlank()||s.equals("!")))
                    throw new IllegalArgumentException("checks must be a non-empty list of names");
                if(!(commands instanceof List<?> actions))throw new IllegalArgumentException("commands must be a list");
                var parsed=new ArrayList<PunishmentConfig.Command>();
                for(Object action:actions) {
                    if(!(action instanceof String text))throw new IllegalArgumentException("Punishment commands must be text");
                    parsed.add(PunishmentConfig.Command.parse(text));
                }
                result.add(new PunishmentConfig.Group(name,window,selectors.stream().map(String.class::cast).toList(),parsed));
            }
            return new PunishmentConfig(result);
        }catch(RuntimeException error){throw new IOException("Could not load punishments.yml: "+error.getMessage(),error);}
    }
    public static CheckConfig load(Path file, CheckConfig defaults) throws IOException {
        if (!Files.exists(file)) {
            Path parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);
            try(var resource=ConfigLoader.class.getResourceAsStream("/config.yml")) {
                if(resource==null)throw new IOException("Missing bundled Navro config.yml");
                Files.copy(resource,file);
            }
        }
        try (var reader=Files.newBufferedReader(file)) {
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
            Object parsed=new Yaml(new SafeConstructor(options)).load(reader);
            if(parsed!=null && !(parsed instanceof Map))throw new IllegalArgumentException("Config root must be a mapping");
            Map<?,?> m=parsed==null?Map.of():(Map<?,?>)parsed;
            Object disabled=m.containsKey("disabled-checks")?m.get("disabled-checks"):new ArrayList<>(defaults.disabledChecks());
            if(!(disabled instanceof List<?> list) || list.stream().anyMatch(v->!(v instanceof String)))
                throw new IllegalArgumentException("disabled-checks must be a list of names");
            Set<String> names=new LinkedHashSet<>();((List<?>)disabled).forEach(v->names.add((String)v));
            return new CheckConfig(string(m,"prefix",defaults.prefix()),
                    number(m,"player-rewind-history-size-ticks",defaults.rewindHistory()).intValue(),
                    bool(m,"smooth-setback",defaults.smoothSetback()),
                    number(m,"player-position-acceptance-threshold",defaults.acceptanceThreshold()).floatValue(),
                    number(m,"differ-till-alert",defaults.alertThreshold()).floatValue(), names,
                    number(m,"max-latency-wait",defaults.maxLatencyWait()).longValue(),
                    number(m,"max-balance-advantage",defaults.maxBalanceAdvantage()).longValue());
        } catch(RuntimeException e) { throw new IOException("Could not load Navro config: "+e.getMessage(),e); }
    }
    private static Object value(Map<?,?> m,String k,Object fallback) { return m.containsKey(k)?m.get(k):fallback; }
    private static Number number(Map<?,?> m,String k,Number fallback) {
        Object v=value(m,k,fallback);if(v instanceof Number n)return n;throw new IllegalArgumentException(k+" must be numeric");
    }
    private static String string(Map<?,?> m,String k,String fallback) {
        Object v=value(m,k,fallback);if(v instanceof String s)return s;throw new IllegalArgumentException(k+" must be text");
    }
    private static boolean bool(Map<?,?> m,String k,boolean fallback) {
        Object v=value(m,k,fallback);if(v instanceof Boolean b)return b;throw new IllegalArgumentException(k+" must be boolean");
    }
}
