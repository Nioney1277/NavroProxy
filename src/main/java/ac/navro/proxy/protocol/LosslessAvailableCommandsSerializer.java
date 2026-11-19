package ac.navro.proxy.protocol;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongObjectPair;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.serializer.AvailableCommandsSerializer_v898;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.common.util.*;
import java.util.*;




public final class LosslessAvailableCommandsSerializer extends AvailableCommandsSerializer_v898 {
    private static final class LegacyParameters extends Bedrock_v898 {
        static TypeMap<CommandParam> parameters() { return COMMAND_PARAMS; }
    }
    private static final class ModernParameters extends Bedrock_v975 {
        static TypeMap<CommandParam> parameters() { return COMMAND_PARAMS; }
    }
    public LosslessAvailableCommandsSerializer(int protocol) {
        super(protocol >= 975 ? ModernParameters.parameters() : LegacyParameters.parameters());
    }
    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, AvailableCommandsPacket packet) {
        SequencedHashSet<String> enumValues = new SequencedHashSet<>();
        SequencedHashSet<String> subCommandValues = new SequencedHashSet<>();
        SequencedHashSet<String> postFixes = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> enums = new SequencedHashSet<>();
        SequencedHashSet<ChainedSubCommandData> subCommandData = new SequencedHashSet<>();
        SequencedHashSet<CommandEnumData> softEnums = new SequencedHashSet<>();
        SequencedHashSet<LongObjectPair<Set<CommandEnumConstraint>>> enumConstraints = new SequencedHashSet<>();


        for (CommandData data : packet.getCommands()) {
            if (data.getAliases() != null) {
                enumValues.addAll(data.getAliases().getValues().keySet());
                enums.add(data.getAliases());
            }

            for (ChainedSubCommandData subcommand : data.getSubcommands()) {
                if (subCommandData.contains(subcommand)) {
                    continue;
                }

                subCommandData.add(subcommand);
                for (ChainedSubCommandData.Value value : subcommand.getValues()) {
                    if (!subCommandValues.contains(value.getFirst())) {
                        if (value.getFirst() != null) subCommandValues.add(value.getFirst());
                    }

                    if (!subCommandValues.contains(value.getSecond())) {
                        if (value.getSecond() != null) subCommandValues.add(value.getSecond());
                    }
                }
            }

            for (CommandOverloadData overload : data.getOverloads()) {
                for (CommandParamData parameter : overload.getOverloads()) {
                    CommandEnumData commandEnumData = parameter.getEnumData();
                    if (commandEnumData != null) {
                        if (commandEnumData.isSoft()) {
                            softEnums.add(commandEnumData);
                        } else {
                            enums.add(commandEnumData);
                            int enumIndex = enums.indexOf(commandEnumData);
                            commandEnumData.getValues().forEach((key, constraints) -> {
                                enumValues.add(key);
                                if (!constraints.isEmpty()) {
                                    int valueIndex = enumValues.indexOf(key);
                                    enumConstraints.add(LongObjectPair.of(LongKeys.key(valueIndex, enumIndex), constraints));
                                }
                            });
                        }
                    }

                    String postfix = parameter.getPostfix();
                    if (postfix != null) {
                        postFixes.add(postfix);
                    }
                }
            }
        }

        helper.writeArray(buffer, enumValues, helper::writeString);
        helper.writeArray(buffer, subCommandValues, helper::writeString);
        helper.writeArray(buffer, postFixes, helper::writeString);

        this.writeEnums(buffer, helper, enumValues, enums);
        helper.writeArray(buffer, subCommandData, (buf, value) -> this.writeSubCommand(buffer, helper, subCommandValues, value));

        helper.writeArray(buffer, packet.getCommands(), (buf, command) -> {
            this.writeCommand(buffer, helper, command, enums, softEnums, postFixes, subCommandData);
        });

        helper.writeArray(buffer, softEnums, helper::writeCommandEnum);

        helper.writeArray(buffer, enumConstraints, this::writeEnumConstraint);
    }


    @Override protected void writeSubCommand(ByteBuf buffer, BedrockCodecHelper helper,
                                             List<String> values, ChainedSubCommandData data) {
        helper.writeString(buffer, data.getName());
        helper.writeArray(buffer, data.getValues(), (buf, value) -> {
            VarInts.writeUnsignedInt(buf, value.getFirst() == null ? -1 : values.indexOf(value.getFirst()));
            VarInts.writeUnsignedInt(buf, value.getSecond() == null ? -1 : values.indexOf(value.getSecond()));
        });
    }
}
