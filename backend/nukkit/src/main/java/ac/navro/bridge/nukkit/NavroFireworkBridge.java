package ac.navro.bridge.nukkit;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.item.EntityElytraFirework;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.MovementEffectType;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;




public final class NavroFireworkBridge extends PluginBase implements Listener {
    private final NativeStateSync stateSync=new NativeStateSync(this);
    private final Map<Player, Set<Long>> rockets = new IdentityHashMap<>();
    private long starts, stops, filteredMotion, forwardedMotion;
    private Set<String> trustedProxies=Set.of();
    private boolean consoleAlerts, staffChatAlerts;
    private Config mutedPlayers;

    @Override public void onEnable() {
        saveDefaultConfig();
        trustedProxies=new HashSet<>(getConfig().getStringList("trusted-proxy-addresses"));
        consoleAlerts=getConfig().getBoolean("console-alerts",true);
        staffChatAlerts=getConfig().getBoolean("staff-chat-alerts",true);
        mutedPlayers=new Config(new File(getDataFolder(),"muted-alerts.json"),Config.JSON);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(stateSync, this);
        getServer().getScheduler().scheduleRepeatingTask(this,()-> {stateSync.tick();tickRockets();},1);
        getServer().callDataPkSendEv=true;
        getLogger().info("NATIVE_STATE_SYNC_READY negotiated server tick and post-event inventory supplements");
        getLogger().info("FIREWORK_BRIDGE_READY standard GLIDE_BOOST; only direct native rocket motion is filtered");
    }

    public boolean trusts(Player player) {
        var remote=player.getRawSocketAddress();
        if(remote==null||remote.getAddress()==null)return false;
        for(String address:trustedProxies) {
            if(!address.contains(":")&&!address.matches("[0-9]+(?:\\.[0-9]+){3}"))continue;
            try {if(remote.getAddress().equals(InetAddress.getByName(address)))return true;}
            catch(UnknownHostException ignored) { }
        }
        return false;
    }

    public void displayAlert(String message) {
        message=message.replaceAll("\\p{Cc}"," ");
        if(message.isBlank())return;
        if(consoleAlerts)getLogger().info(TextFormat.clean(message));
        if(staffChatAlerts)for(Player player:getServer().getOnlinePlayers().values()) {
            if(player.hasPermission("navrobridge.alerts")&&!mutedPlayers.exists(player.getUniqueId().toString()))
                player.sendMessage(message);
        }
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!command.getName().equals("navroalerts")||args.length!=0)return false;
        if(!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in game to toggle your chat alerts.");return true;
        }
        if(!player.hasPermission("navrobridge.alerts")) {
            player.sendMessage("You do not have permission to receive Navro chat alerts.");return true;
        }
        if(!staffChatAlerts) {
            player.sendMessage("Navro chat alerts are disabled in the server configuration.");return true;
        }
        String uuid=player.getUniqueId().toString();
        boolean enabled=mutedPlayers.exists(uuid);
        if(enabled)mutedPlayers.remove(uuid);else mutedPlayers.set(uuid,true);
        mutedPlayers.save();
        player.sendMessage(enabled?"Navro chat alerts enabled.":"Navro chat alerts disabled.");
        return true;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player=event.getPlayer();
        if(trusts(player))getServer().getScheduler().scheduleDelayedTask(this,()-> {
            if(player.isOnline())player.sendCommandData();
        },1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPacketSend(DataPacketSendEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        if (player.protocol < ProtocolInfo.v1_21_40 || !stateSync.hasSession(player)) return;
        if (event.getPacket() instanceof AddEntityPacket add) {
            var entity = player.getLevel().getEntity(add.entityRuntimeId);
            if (!(entity instanceof EntityElytraFirework rocket)
                    || !isActiveRocket(rocket,player)) return;
            long id = rocket.getId();
            var level=player.getLevel();

            PostSend.schedule(this, player, event, () -> {
                if (event.isCancelled() || !player.isOnline() || !stateSync.hasSession(player)
                        || event.getPacket()!=add || player.getLevel()!=level || !isActiveRocket(rocket,player)) return;
                Set<Long> active = rockets.computeIfAbsent(player, ignored -> new HashSet<>());
                if (active.contains(id)) return;
                if (active.isEmpty()) {
                    if (!sendEffect(player, 1_000_000)) {rockets.remove(player);return;}
                    starts++;
                }
                active.add(id);
            });
        } else if (event.getPacket() instanceof RemoveEntityPacket remove) {
            long id = remove.eid;
            PostSend.schedule(this, player, event, () -> {
                var active = rockets.get(player);
                if (active == null || !active.remove(id) || !active.isEmpty()) return;
                stopBoost(player);
            });
        } else if (event.getPacket() instanceof SetEntityMotionPacket motion
                && motion.eid == player.getId()) {
            var active = rockets.get(player);
            if (active != null && !active.isEmpty() && player.isAlive() && player.isGliding()
                    && directNativeRocketMotion()) {
                event.setCancelled(true);
                filteredMotion++;
            } else {
                forwardedMotion++;
            }
        }
    }

    private static boolean isActiveRocket(EntityElytraFirework rocket,Player player) {
        return !rocket.isClosed() && player.isAlive() && player.isGliding()
                && rocket.getLevel()==player.getLevel() && rocket.getFollowingPlayer()==player;
    }

    private void stopBoost(Player player) {
        if(!player.isOnline()) {rockets.remove(player);return;}
        if(sendEffect(player,0)) {rockets.remove(player);stops++;}
    }

    void tickRockets() {
        for(var player:new ArrayList<>(rockets.keySet())) {
            if(!player.isOnline()) {rockets.remove(player);continue;}
            var active=rockets.get(player);
            active.removeIf(id->!(player.getLevel().getEntity(id) instanceof EntityElytraFirework rocket)
                    ||!isActiveRocket(rocket,player));
            if(active.isEmpty())stopBoost(player);
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
