package ac.navro.proxy.packets.other;

import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.LatencyUtil;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;


public final class NetworkLatencyPackets {
    public static final long LATENCY_MAGNITUDE = 1_000_000L;
    public static final long PS5_LATENCY_MAGNITUDE = 1_000L;
    private static final int DEVICE_OS_PLAYSTATION = 11;
    private static final int V1_19_60 = 567;

    private NetworkLatencyPackets() { }

    public static void onPacketSend(WirePlayerState player, NetworkStackLatencyPacket packet) {
        LatencyUtil latency = player.getLatencyUtil();
        if (player.getEntityBatchEncoder() != null) {
            packet.setFromServer(true);
            return;
        }
        boolean alreadyQueuedAsOurs = latency.hasPending(packet.getTimestamp(), true);
        packet.setFromServer(true);
        if (!alreadyQueuedAsOurs) latency.queue(packet.getTimestamp(), false);
    }

    public static boolean onPacket(WirePlayerState player, NetworkStackLatencyPacket packet) {
        LatencyUtil latency = player.getLatencyUtil();
        long id = responseId(player,packet);
        return player.protocolVersion() < V1_19_60
                ? latency.onOrderedResponse() : latency.onResponse(id);
    }

    public static long responseId(WirePlayerState player, NetworkStackLatencyPacket packet) {
        long magnitude = player.deviceOs() == DEVICE_OS_PLAYSTATION
                ? PS5_LATENCY_MAGNITUDE : LATENCY_MAGNITUDE;
        return packet.getTimestamp() / magnitude;
    }
}
