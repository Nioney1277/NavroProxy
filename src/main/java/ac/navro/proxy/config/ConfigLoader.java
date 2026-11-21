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
