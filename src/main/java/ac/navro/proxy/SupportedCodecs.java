package ac.navro.proxy;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v729.Bedrock_v729;
import org.cloudburstmc.protocol.bedrock.codec.v776.Bedrock_v776;
import org.cloudburstmc.protocol.bedrock.codec.v786.Bedrock_v786;
import org.cloudburstmc.protocol.bedrock.codec.v800.Bedrock_v800;
import org.cloudburstmc.protocol.bedrock.codec.v819.Bedrock_v819;
import org.cloudburstmc.protocol.bedrock.codec.v860.Bedrock_v860;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;

import java.util.Comparator;
import java.util.List;


public final class SupportedCodecs {
    private static final List<BedrockCodec> CODECS = List.of(
            Bedrock_v729.CODEC,
            Bedrock_v776.CODEC,
            Bedrock_v786.CODEC,
            Bedrock_v800.CODEC,
            Bedrock_v819.CODEC,
            Bedrock_v860.CODEC,
            Bedrock_v898.CODEC,
            Bedrock_v924.CODEC,
            Bedrock_v944.CODEC,
            Bedrock_v975.CODEC,
            Bedrock_v1001.CODEC,
            Bedrock_v2168.CODEC,
            Bedrock_v2169.CODEC,
            Bedrock_v2193.CODEC,
            Bedrock_v2225.CODEC
    ).stream().map(codec -> codec.getProtocolVersion() < 898 ? codec : codec.toBuilder()
            .updateSerializer(org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket.class,
                    new ac.navro.proxy.protocol.LosslessAvailableCommandsSerializer(codec.getProtocolVersion())).build())
            .sorted(Comparator.comparingInt(BedrockCodec::getProtocolVersion)).toList();

    private SupportedCodecs() {
    }

    public static BedrockCodec forProtocol(int protocolVersion) {


        if (protocolVersion == 2192) {
            return forProtocol(2193);
        }
        return CODECS.stream()
                .filter(codec -> codec.getProtocolVersion() == protocolVersion)
                .findFirst()
                .orElse(null);
    }

    public static BedrockCodec fallback(int protocolVersion) {
        BedrockCodec configured = forProtocol(protocolVersion);
        return configured != null ? configured : CODECS.get(CODECS.size() - 1);
    }

    public static int defaultRaknetProtocol() {
        return Bedrock_v898.CODEC.getRaknetProtocolVersion();
    }
}
