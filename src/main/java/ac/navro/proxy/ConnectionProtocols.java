package ac.navro.proxy;

import java.util.Set;


public final class ConnectionProtocols {
    private static final Set<Integer> VERSIONS = Set.of(
            291, 313, 332, 340, 354, 361, 388, 389, 390, 407, 408, 419, 422, 428,
            431, 440, 448, 465, 471, 475, 486, 503, 527, 534, 544, 545, 554, 557,
            560, 567, 568, 575, 582, 589, 594, 618, 622, 630, 649, 662, 671, 685,
            686, 712, 729, 748, 766, 776, 786, 800, 818, 819, 827, 844, 859, 860,
            898, 924, 944, 975, 1001, 2168, 2169, 2192, 2193, 2225);

    private ConnectionProtocols() { }

    public static boolean accepts(int protocol) { return VERSIONS.contains(protocol); }
    public static Set<Integer> all() { return VERSIONS; }

    public static org.cloudburstmc.protocol.bedrock.codec.BedrockCodec codec(int protocol) {
        if (!accepts(protocol)) throw new IllegalArgumentException("Unsupported Bedrock protocol " + protocol);
        int revision = protocol == 2192 ? 2193 : protocol;
        return CodecCache.VALUES.computeIfAbsent(revision, version -> {
            try {
                return (org.cloudburstmc.protocol.bedrock.codec.BedrockCodec) Class.forName(
                        "org.cloudburstmc.protocol.bedrock.codec.v" + version + ".Bedrock_v" + version)
                        .getField("CODEC").get(null);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Missing connection codec " + version, error);
            }
        });
    }

    private static final class CodecCache {
        static final java.util.concurrent.ConcurrentMap<Integer, org.cloudburstmc.protocol.bedrock.codec.BedrockCodec>
                VALUES = new java.util.concurrent.ConcurrentHashMap<>();
    }
}
