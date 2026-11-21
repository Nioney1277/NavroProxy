package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class PostSend {
    private static final Method NATIVE_HOOK = nativeHook();

    private PostSend() { }

    static void schedule(Plugin plugin, Player player, DataPacketSendEvent event, Runnable action) {
        if (event.isCancelled()) return;
        if (NATIVE_HOOK != null) {
            try {
                NATIVE_HOOK.invoke(event, action);
                return;
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Cannot register post-send action", exception);
            }
        }
        player.getServer().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    private static Method nativeHook() {
        try {
            return DataPacketSendEvent.class.getMethod("afterSend", Runnable.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
