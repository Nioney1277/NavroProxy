package ac.navro.proxy.packets.server;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.attribute.AttributeModifierData;
import org.cloudburstmc.protocol.bedrock.data.attribute.AttributeOperation;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.function.Consumer;

public final class ServerRewindPackets {
    private static final String MOVEMENT = "minecraft:movement";
    private static final String SPRINT_MODIFIER = "D208FC00-42AA-4AAD-9276-D5446530DE43";
    private final EnumMap<EntityFlag, Boolean> serverFlags = new EnumMap<>(EntityFlag.class);
    private AttributeData serverMovement;

    public void capture(WirePlayerState player, BedrockPacket packet) {
        if (packet instanceof SetEntityDataPacket metadata && metadata.getRuntimeEntityId() == player.runtimeId()) {
            var flags = metadata.getMetadata().getFlags();
            if (flags != null) serverFlags.putAll(flags);
        } else if (packet instanceof UpdateAttributesPacket attributes && attributes.getRuntimeEntityId() == player.runtimeId()) {
            for (var attribute : attributes.getAttributes()) {
                if (MOVEMENT.equals(attribute.getName())) serverMovement = attribute;
            }
        }
    }

    public void onPacketSend(BedrockPacket packet) {
        switch (packet) {
            case MobEffectPacket value -> value.setTick(0L);
            case MovePlayerPacket value -> value.setTick(0L);
            case SetEntityDataPacket value -> value.setTick(0L);
            case SetEntityMotionPacket value -> value.setTick(0L);
            case UpdateAttributesPacket value -> value.setTick(0L);
            default -> { }
        }
    }

    public void syncCorrection(WirePlayerState player, long tick, Consumer<BedrockPacket> send) {
        if (player.vehicleData != null) return;
        if (!serverFlags.isEmpty()) {
            var flags = new EnumMap<>(serverFlags);
            flags.put(EntityFlag.SPRINTING, player.predictionSprinting());
            flags.put(EntityFlag.SNEAKING, player.sneaking());
            flags.put(EntityFlag.USING_ITEM, player.predictionFlag(EntityFlag.USING_ITEM));
            var metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(player.runtimeId());
            metadata.setTick(tick);
            metadata.getMetadata().putFlags(flags);
            send.accept(metadata);
        }
        var movement = player.attributes().get(MOVEMENT);
        if (movement == null) return;
        var rawModifiers = new HashMap<String, AttributeModifierData>();
        if (serverMovement != null) {
            for (var modifier : serverMovement.getModifiers()) rawModifiers.put(modifier.getId(), modifier);
        }
        var modifiers = new ArrayList<AttributeModifierData>();
        for (var modeled : movement.getModifiers().values()) {
            if (SPRINT_MODIFIER.equals(modeled.id())) {
                modifiers.add(new AttributeModifierData(modeled.id(), "Sprinting speed boost", modeled.amount(),
                        AttributeOperation.MULTIPLY_TOTAL, 2, false));
            } else if (rawModifiers.containsKey(modeled.id())) {
                modifiers.add(rawModifiers.get(modeled.id()));
            } else {
                return;
            }
        }
        float minimum = serverMovement == null ? 0F : serverMovement.getMinimum();
        float maximum = serverMovement == null ? Float.MAX_VALUE : serverMovement.getMaximum();
        var attributes = new UpdateAttributesPacket();
        attributes.setRuntimeEntityId(player.runtimeId());
        attributes.setTick(tick);
        attributes.getAttributes().add(new AttributeData(MOVEMENT, minimum, maximum, movement.getValue(),
                serverMovement == null ? minimum : serverMovement.getDefaultMinimum(),
                serverMovement == null ? maximum : serverMovement.getDefaultMaximum(), movement.getBaseValue(), modifiers));
        send.accept(attributes);
    }
}
