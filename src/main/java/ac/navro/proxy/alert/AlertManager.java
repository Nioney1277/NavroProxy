package ac.navro.proxy.alert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;


public final class AlertManager {
    public static final UUID CONSOLE_UUID = new UUID(0, 0);
    private final AlertSubscriptions subscriptions;
    private final Consumer<String> console;
    private final Supplier<Map<UUID, Consumer<String>>> online;
    private final Supplier<String> prefix;

    public AlertManager(Path file, String prefix, Consumer<String> console,
                         Supplier<Map<UUID, Consumer<String>>> online) {
        this(file, () -> prefix, console, online);
    }

    public AlertManager(Path file, Supplier<String> prefix, Consumer<String> console,
                         Supplier<Map<UUID, Consumer<String>>> online) {
        this.console = console;
        this.online = online;
        this.prefix = prefix;
        AlertSubscriptions loaded;
        try { loaded = new AlertSubscriptions(file); }
        catch (IOException error) {
            console.accept("Could not load alert subscriptions: " + error.getMessage());
            loaded = AlertSubscriptions.empty(file);
        }
        subscriptions = loaded;
    }

    public void alert(String verbose) {
        String message = getPrefix() + "§3" + verbose;
        subscriptions.forEachOnline(online.get(), recipient -> recipient.accept(message));
        if (subscriptions.has(CONSOLE_UUID)) console.accept(message);
    }

    public void alertToPlayers(List<Consumer<String>> sources, String verbose) {
        sources.forEach(source -> source.accept(getPrefix() + "§3" + verbose));
    }

    public String getPrefix() { return prefix.get().replace('&', '§'); }
    public boolean hasAlert(UUID id) { return subscriptions.has(id); }

    public boolean setAlert(UUID id, boolean enabled) {
        try { subscriptions.set(id, enabled); return true; }
        catch (IOException error) {
            console.accept("Could not save alert subscriptions: " + error.getMessage());
            return false;
        }
    }
}
