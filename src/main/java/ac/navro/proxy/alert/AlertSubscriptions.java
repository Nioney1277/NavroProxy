package ac.navro.proxy.alert;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


final class AlertSubscriptions {
    private static final Gson JSON = new Gson();
    private final Path file;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    AlertSubscriptions(Path file) throws IOException {
        this(file, Set.of());
        if (!Files.exists(this.file)) return;
        try {
            UUID[] ids = JSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), UUID[].class);
            if (ids == null) throw new IllegalArgumentException("Expected a UUID array");
            for (UUID id : ids) {
                if (id == null) throw new IllegalArgumentException("Null subscriber UUID");
                enabled.add(id);
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid alert subscription file", e);
        }
    }

    private AlertSubscriptions(Path file, Set<UUID> initial) {
        this.file = file.toAbsolutePath();
        enabled.addAll(initial);
    }

    static AlertSubscriptions empty(Path file) {
        return new AlertSubscriptions(file, Set.of());
    }

    boolean has(UUID id) { return enabled.contains(id); }

    synchronized void set(UUID id, boolean subscribe) throws IOException {
        Set<UUID> next = new HashSet<>(enabled);
        if (!(subscribe ? next.add(id) : next.remove(id))) return;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "alert-subscriptions-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(next.stream().map(UUID::toString).sorted().toList()) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        if (subscribe) enabled.add(id); else enabled.remove(id);
    }

    <T> void forEachOnline(Map<UUID, T> online, Consumer<T> action) {
        for (UUID id : enabled) {
            T currentSession = online.get(id);
            if (currentSession != null) action.accept(currentSession);
        }
    }
}
