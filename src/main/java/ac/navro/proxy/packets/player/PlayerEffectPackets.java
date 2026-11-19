package ac.navro.proxy.packets.player;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;


public final class PlayerEffectPackets {
    private PlayerEffectPackets() { }

    public static void onPacket(WirePlayerState player, BedrockPacket packet) {
        if (!(packet instanceof MobEffectPacket effect) || effect.getRuntimeEntityId() != player.runtimeId()) return;

        final var event = effect.getEvent();
        final int effectId = effect.getEffectId();
        final int amplifier = effect.getAmplifier();
        final int rawDuration = effect.getDuration();
        final int duration = rawDuration < 0 || rawDuration == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : rawDuration + 1;
        player.queueAcknowledgment(packet, () -> {
            switch (event) {
                case ADD, MODIFY -> player.addEffect(effectId, amplifier, duration);
                case REMOVE -> player.removeEffect(effectId);
                default -> { }
            }
        });
    }
}
