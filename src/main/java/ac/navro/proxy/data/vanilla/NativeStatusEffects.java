package ac.navro.proxy.data.vanilla;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;


public final class NativeStatusEffects {
    private static final Set<Integer> IDS = load();
    private NativeStatusEffects() { }

    public static boolean contains(int id) { return IDS.contains(id); }

    private static Set<Integer> load() {
        var resource = NativeStatusEffects.class.getResourceAsStream("/navro/status-effects.json");
        if (resource == null) throw new IllegalStateException("Missing native status effect registry");
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var ids = new HashSet<Integer>();
            for (var value : JsonParser.parseReader(reader).getAsJsonArray()) ids.add(value.getAsInt());
            if (ids.isEmpty()) throw new IllegalStateException("Empty native status effect registry");
            return Set.copyOf(ids);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load native status effect registry", e);
        }
    }
}
