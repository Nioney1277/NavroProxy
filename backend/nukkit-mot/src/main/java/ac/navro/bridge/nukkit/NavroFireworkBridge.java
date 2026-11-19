package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.entity.item.EntityElytraFirework;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.MovementEffectType;
import cn.nukkit.plugin.PluginBase;

import java.util.*;




public final class NavroFireworkBridge extends PluginBase implements Listener {
    private final NativeStateSync stateSync=new NativeStateSync();
    private final Map<Player, Set<Long>> rockets = new IdentityHashMap<>();
    private long starts, stops, filteredMotion, forwardedMotion;

    @Override public void onEnable() {


        try {
            DataPacketSendEvent.class.getMethod("afterSend", Runnable.class);
            EntityElytraFirework.class.getMethod("getFollowingPlayer");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This bridge requires Nukkit-MOT afterSend and attached firework API", e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(stateSync, this);
        getServer().getScheduler().scheduleRepeatingTask(this,stateSync::tick,1);
        getServer().callDataPkSendEv=true;
        getLogger().info("NATIVE_STATE_SYNC_READY negotiated server tick and missing post-transaction inventory slots");
        getLogger().info("FIREWORK_BRIDGE_READY standard GLIDE_BOOST; only direct native rocket motion is filtered");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPacketSend(DataPacketSendEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        if (player.protocol < ProtocolInfo.v1_21_40) return;
        if (event.getPacket() instanceof AddEntityPacket add) {
            var entity = player.getLevel().getEntity(add.entityRuntimeId);
            if (!(entity instanceof EntityElytraFirework rocket)
                    || rocket.isClosed() || rocket.getFollowingPlayer() != player) return;
            long id = rocket.getId();

            event.afterSend(() -> {
                if (rocket.isClosed() || !player.isOnline()) return;
                Set<Long> active = rockets.computeIfAbsent(player, ignored -> new HashSet<>());
                if (!active.add(id)) return;
                if (!sendEffect(player, 1_000_000)) {
                    active.remove(id);
                    if (active.isEmpty()) rockets.remove(player);
                    return;
                }
                starts++;
            });
        } else if (event.getPacket() instanceof RemoveEntityPacket remove) {
            long id = remove.eid;
            event.afterSend(() -> {
                var active = rockets.get(player);
                if (active == null || !active.remove(id) || !active.isEmpty()) return;
                rockets.remove(player);
                if (player.isOnline() && sendEffect(player, 0)) stops++;
            });
        } else if (event.getPacket() instanceof SetEntityMotionPacket motion
                && motion.eid == player.getId()) {
            var active = rockets.get(player);
            if (active != null && !active.isEmpty() && directNativeRocketMotion()) {
                event.setCancelled(true);
                filteredMotion++;
            } else {
                forwardedMotion++;
            }
        }
    }





    private static boolean directNativeRocketMotion() {
        var frames = Thread.currentThread().getStackTrace();
        for (int i = 0; i + 1 < frames.length; i++) {
            if (frames[i].getClassName().equals(Player.class.getName())
                    && frames[i].getMethodName().equals("setMotion")) {
                return frames[i + 1].getClassName().equals(EntityElytraFirework.class.getName())
                        && frames[i + 1].getMethodName().equals("onUpdate");
            }
        }
        return false;
    }

    private boolean sendEffect(Player player, int duration) {
        return player.dataPacket(new MovementEffectPacket(player.getId(),
                MovementEffectType.GLIDE_BOOST, duration, 0));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { rockets.remove(event.getPlayer());stateSync.remove(event.getPlayer()); }

    @Override public void onDisable() {
        for (Player player : new ArrayList<>(rockets.keySet())) {
            if (player.isOnline()) sendEffect(player, 0);
        }
        rockets.clear();stateSync.clear();
        getLogger().info("FIREWORK_BRIDGE_STOPPED starts=" + starts + " stops=" + stops
                + " filteredPropulsion=" + filteredMotion + " preservedMotion=" + forwardedMotion);
    }
}
