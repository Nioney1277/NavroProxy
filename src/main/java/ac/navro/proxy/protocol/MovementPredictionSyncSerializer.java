package ac.navro.proxy.protocol;

import ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.HashSet;

public final class MovementPredictionSyncSerializer implements BedrockPacketSerializer<MovementPredictionSyncPacket> {
    private final int protocol;

    public MovementPredictionSyncSerializer(int protocol) {
        this.protocol = protocol;
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, MovementPredictionSyncPacket packet) {
        var flags = new HashSet<Integer>();
        int encodedByteIndex = 0;
        int value;
        do {
            value = buffer.readUnsignedByte();
            for (int bit = 0; bit < 7; bit++) {
                if ((value & (1 << bit)) != 0) flags.add(encodedByteIndex * 7 + bit);
            }
            if (++encodedByteIndex > 32) throw new IllegalArgumentException("Actor flags VarInt is too large");
        } while ((value & 0x80) != 0);
        ((ClientMovementPredictionSyncPacket) packet).captureFlags(flags);
        packet.setBoundingBox(helper.readVector3f(buffer));
        packet.setSpeed(buffer.readFloatLE());
        packet.setUnderwaterSpeed(buffer.readFloatLE());
        packet.setLavaSpeed(buffer.readFloatLE());
        packet.setJumpStrength(buffer.readFloatLE());
        packet.setHealth(buffer.readFloatLE());
        packet.setHunger(buffer.readFloatLE());
        packet.setUnknown1(protocol >= 975 ? buffer.readFloatLE() : 0F);
        packet.setUnknown2(protocol >= 975 ? buffer.readFloatLE() : 0F);
        packet.setUnknown3(protocol >= 975 ? buffer.readFloatLE() : 0F);
        packet.setUniqueEntityId(VarInts.readLong(buffer));
        packet.setFlying(protocol >= 786 && buffer.readBoolean());
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, MovementPredictionSyncPacket packet) {
        var flags = ClientMovementPredictionSyncPacket.copyFlags(packet);
        int highest = flags.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (highest >= 224) throw new IllegalArgumentException("Actor flags VarInt is too large");
        int byteCount = highest / 7 + 1;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            int value = byteIndex + 1 < byteCount ? 0x80 : 0;
            for (int bit = 0; bit < 7; bit++) {
                if (flags.contains(byteIndex * 7 + bit)) value |= 1 << bit;
            }
            buffer.writeByte(value);
        }
        helper.writeVector3f(buffer, packet.getBoundingBox());
        buffer.writeFloatLE(packet.getSpeed());
        buffer.writeFloatLE(packet.getUnderwaterSpeed());
        buffer.writeFloatLE(packet.getLavaSpeed());
        buffer.writeFloatLE(packet.getJumpStrength());
        buffer.writeFloatLE(packet.getHealth());
        buffer.writeFloatLE(packet.getHunger());
        if (protocol >= 975) {
            buffer.writeFloatLE(packet.getUnknown1());
            buffer.writeFloatLE(packet.getUnknown2());
            buffer.writeFloatLE(packet.getUnknown3());
        }
        VarInts.writeLong(buffer, packet.getUniqueEntityId());
        if (protocol >= 786) buffer.writeBoolean(packet.isFlying());
    }
}
