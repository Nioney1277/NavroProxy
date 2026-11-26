package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

final class PostSend {
    private static final Method NATIVE_HOOK = nativeHook();
    private static final ThreadLocal<Consumer<Runnable>> TEST_SCHEDULER = new ThreadLocal<>();

    private PostSend() { }

    static void schedule(Plugin plugin, Player player, DataPacketSendEvent event, Runnable action) {
        if (event.isCancelled()) return;
        Consumer<Runnable> testScheduler = TEST_SCHEDULER.get();
        if (testScheduler != null) {
            testScheduler.accept(() -> { if (!event.isCancelled() && player.isOnline()) action.run(); });
            return;
        }
        if (NATIVE_HOOK != null) {
            try {
                NATIVE_HOOK.invoke(event, action);
                return;
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Cannot register post-send action", exception);
            }
        }
        player.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!event.isCancelled() && player.isOnline()) action.run();
        }, 1);
    }

    static Consumer<Runnable> setTestScheduler(Consumer<Runnable> scheduler) {
        Consumer<Runnable> previous = TEST_SCHEDULER.get();
        if (scheduler == null) TEST_SCHEDULER.remove(); else TEST_SCHEDULER.set(scheduler);
        return previous;
    }

    private static Method nativeHook() {
        try {
            return DataPacketSendEvent.class.getMethod("afterSend", Runnable.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
