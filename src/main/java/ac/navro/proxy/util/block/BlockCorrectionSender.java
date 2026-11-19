package ac.navro.proxy.util.block;

import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;


public final class BlockCorrectionSender {
    private final BedrockServerSession client;
    private final ProtocolDefinitions definitions;
    private final WirePlayerState player;
    private final BooleanSupplier available;
    private final Consumer<UpdateBlockPacket> observe;

    public BlockCorrectionSender(BedrockServerSession client, ProtocolDefinitions definitions,
                                 WirePlayerState player, BooleanSupplier available,
                                 Consumer<UpdateBlockPacket> observe) {
        this.client = Objects.requireNonNull(client);
        this.definitions = Objects.requireNonNull(definitions);
        this.player = Objects.requireNonNull(player);
        this.available = Objects.requireNonNull(available);
        this.observe = Objects.requireNonNull(observe);
    }


    public void restoreCorrectBlock(Vector3i position, int layerZeroNetworkId, int layerOneNetworkId) {
        if (position == null) return;
        execute(() -> BlockUtil.restoreCorrectBlock(this::send, position,
                definitions.blockDefinition(layerZeroNetworkId), definitions.blockDefinition(layerOneNetworkId)));
    }


    public void restoreCorrectBlock(Vector3i position, int networkId) {
        if (position == null) return;
        execute(() -> BlockUtil.restoreCorrectBlock(this::send, position, definitions.blockDefinition(networkId)));
    }

    private void execute(Runnable task) {
        var loop = client.getPeer().getChannel().eventLoop();
        Runnable guarded = () -> {
            if (!available.getAsBoolean() || !client.isConnected() || !client.getPeer().isConnected()) return;
            if (player.getEntityBatchEncoder() == null)
                throw new IllegalStateException("Block corrections require the installed batch ACK transport");
            task.run();
        };
        if (loop.inEventLoop()) guarded.run();
        else loop.execute(guarded);
    }

    private void send(UpdateBlockPacket packet) {
        var encoder = player.getEntityBatchEncoder();
        try {
            observe.accept(packet);

            client.sendPacket(packet);
        } catch (RuntimeException | Error failure) {
            encoder.discard(packet);
            throw failure;
        }
    }
}
