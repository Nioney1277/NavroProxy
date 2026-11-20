package ac.navro.proxy.packets.input;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.input.TickData;
import ac.navro.proxy.packets.input.legacy.LegacyAuthInputPackets;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class PostAuthInputPackets {
    private PostAuthInputPackets() { }
    public static boolean onPacket(SessionState session, PlayerAuthInputPacket packet, boolean cancelled) {
        var player = session.player();
        if (player.isExempted()) return !cancelled;
        player.finishInput();
        if (!session.spawned()) return trace(session, packet, false, "not_spawned");
        if (session.awaitingBackendSpawnPosition()) return trace(session, packet, false, "awaiting_backend_spawn_position");


        if (player.awaitingRespawnHealth()) return trace(session, packet, false, "awaiting_respawn_health");

        session.teleports().getAuthInputHistory().put(packet.getTick(), TickData.capture(session, packet));
        var context = session.authInputPackets().context();
        context.thisTickRiptideLevel = 0;
        context.riptideTickStartState = null;
        context.thisTickSpinAttack = false;
        context.thisTickOnGroundSpinAttack = false;
        player.doingInventoryAction = false;
        context.hasDepthStrider = false;

        var pendingTarget = player.vehicleData == null ? session.teleports().pendingTeleportPosition() : null;
        packet.setPosition(pendingTarget != null ? pendingTarget.toVector3f()
                : player.position().up(session.playerYOffset()).toVector3f());
        LegacyAuthInputPackets.correctInputData(player, packet);
        if (session.teleports().isHardTeleporting()) return trace(session, packet, false, "hard_teleport");
        if (session.teleports().isTeleporting()) return !cancelled;
        if (player.tickSinceBlockResync > 0) player.tickSinceBlockResync--;
        session.pollRewindHistory();
        return !cancelled;
    }

    private static boolean trace(SessionState session, PlayerAuthInputPacket packet, boolean accepted, String reason) {
        var log = session.player().worldDiagnostics();
        if (log != null) log.event("post_input_cancelled", ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fields(
                "inputTick", packet.getTick(), "reason", reason, "position", String.valueOf(packet.getPosition()),
                "blockResync", session.player().tickSinceBlockResync));
        return accepted;
    }


    public static void finishInput(WirePlayerState player) {
        player.doingInventoryAction = false;
    }
}
