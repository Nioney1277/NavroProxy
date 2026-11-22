package ac.navro.proxy.listener;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.impl.timer.NegativeTimer;
import ac.navro.proxy.check.impl.timer.Timer;
import ac.navro.proxy.packets.input.AuthInputClock;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.DeathInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHealthPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;



public final class PacketListener {
    private PacketListener() { }

    public static boolean onPacketReceived(SessionState state, BedrockPacket packet,
                                            long maxBalanceAdvantageMS) {
        WirePlayerState player = state.player();
        NegativeTimer negativeTimer = (NegativeTimer) player.getCheckHolder().get(NegativeTimer.class);
        if ((packet instanceof RespawnPacket respawn && respawn.getState() == RespawnPacket.State.CLIENT_READY
                || packet instanceof PlayerActionPacket action && action.getAction() == PlayerActionType.RESPAWN)
                && player.acknowledgedDead) {
            negativeTimer.onRespawn();
        }
        if(packet instanceof PlayerAuthInputPacket input && !AuthInputClock.begin(state,input))return false;
        if (player.isExempted()) return true;
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket sync) {
            state.observePredictionSync(sync);
        }
        player.setMaxBalanceAdvantageMs(maxBalanceAdvantageMS);
        if (packet instanceof PlayerAuthInputPacket input) {
            Timer timer = (Timer) player.getCheckHolder().get(Timer.class);
            if (player.checkEnabled(Timer.class) && timer.isInvalid()) {
                state.observeRejectedClient(packet, "Timer", "rejected tick=" + input.getTick());
                state.correctRejectedTimerInput();
                return false;
            }
            if(player.checkEnabled(NegativeTimer.class))negativeTimer.onAuthInput(input);
        }
        for (PacketCheck check : player.getCheckHolder().packetChecks()) {
            if (check.onPacketReceive(packet, false)) {
                state.observeRejectedClient(packet, check.getClass().getSimpleName(), "cancelled by packet check");
                return false;
            }
        }
        return true;
    }

    public static void onPacketSent(WirePlayerState player, BedrockPacket packet) {
        if (packet instanceof DeathInfoPacket
                || packet instanceof RespawnPacket respawn && respawn.getState() == RespawnPacket.State.SERVER_SEARCHING) {
            player.queueAcknowledgment(packet, () -> onDeath(player));
        } else if (packet instanceof RespawnPacket respawn && respawn.getState() == RespawnPacket.State.SERVER_READY) {
            player.queueAcknowledgment(packet, () -> onRespawn(player));
        } else if (packet instanceof SetHealthPacket health) {
            int value = health.getHealth();
            player.queueAcknowledgment(packet, () -> onHealth(player, value));
        } else if (packet instanceof UpdateAttributesPacket attributes
                && attributes.getRuntimeEntityId() == player.runtimeId()) {
            for (AttributeData attribute : attributes.getAttributes()) {
                if (!"minecraft:health".equals(attribute.getName())) continue;
                float value = attribute.getValue();
                player.queueAcknowledgment(packet, () -> onHealth(player, value));
            }
        }
    }

    private static void onDeath(WirePlayerState player) {
        player.acknowledgedDead = true;
        player.acknowledgeMovementHealth(false);
        var timer = player.getCheckHolder().get(NegativeTimer.class);
        if (timer instanceof NegativeTimer negativeTimer) negativeTimer.onDeath();
    }

    private static void onHealth(WirePlayerState player, float value) {
        if (value <= 0) {
            onDeath(player);
        } else {
            player.acknowledgeMovementHealth(true);
            onRespawn(player);
        }
    }

    private static void onRespawn(WirePlayerState player) {
        if (!player.acknowledgedDead) return;
        player.acknowledgedDead = false;
        var timer = player.getCheckHolder().get(NegativeTimer.class);
        if (timer instanceof NegativeTimer negativeTimer) negativeTimer.onRespawn();
    }

}
