package ac.navro.proxy.packets.server;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import ac.navro.proxy.data.vanilla.GameType;
import java.util.EnumSet;
import java.util.Set;


public final class ServerDataPackets {
    private ServerDataPackets() { }
    public static void onPacket(WirePlayerState player, BedrockPacket packet) {
        onPacket(player, packet, false);
    }

    public static void onPacket(WirePlayerState player, BedrockPacket packet, boolean uniqueAbilityEntityId) {
        if (packet instanceof StartGamePacket start) {

            player.queueAcknowledgment(packet, () -> player.acknowledgeGameType(start.getPlayerGameType() == null
                    ? GameType.from(0) : nativeGameType(start.getPlayerGameType())));
        } else if (packet instanceof UpdateAbilitiesPacket update) {
            long entityId = uniqueAbilityEntityId ? player.uniqueId() : player.runtimeId();
            if (update.getUniqueEntityId() != entityId) return;
            Set<Ability> values = EnumSet.noneOf(Ability.class);
            for (var layer : update.getAbilityLayers()) values.addAll(layer.getAbilityValues());
            Set<Ability> snapshot = Set.copyOf(values);
            player.queueAcknowledgment(packet, () -> player.acknowledgeAbilities(snapshot));
        } else if (packet instanceof UpdatePlayerGameTypePacket update) {
            if (update.getEntityId() != player.runtimeId() || update.getGameType() == null) return;
            player.gameTypeEvent("gameTypeUpdateQueued", "gameMode", nativeGameType(update.getGameType()),
                    "source", "update-player-game-type");
            player.queueAcknowledgment(packet, () -> player.acknowledgeGameType(nativeGameType(update.getGameType())));
        } else if (packet instanceof SetPlayerGameTypePacket update) {
            player.gameTypeEvent("gameTypeUpdateQueued", "gameMode", update.getGamemode());
            player.queueAcknowledgment(packet, () -> {
                player.acknowledgeGameType(GameType.from(update.getGamemode()));
                player.gameTypeEvent("gameTypeUpdateAcknowledged", "gameMode", update.getGamemode());
            });
        }
    }

    private static GameType nativeGameType(org.cloudburstmc.protocol.bedrock.data.GameType value) {
        return value == null ? null : GameType.from(value.ordinal());
    }
}
