package ac.navro.proxy.util;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;


public final class EntityMetadataUtil {


    private static final class FlagIds extends Bedrock_v2193 {
        static int id(EntityFlag flag) { return ENTITY_FLAGS.getId(flag); }
        static EntityFlag flag(int id) { return ENTITY_FLAGS.getTypeUnsafe(id); }
    }

    public static int nativeFlagId(EntityFlag flag) { return FlagIds.id(flag); }

    public static Set<Integer> copyFlags(EntityDataMap metadata) {
        if (metadata == null || metadata.getFlags() == null) return null;
        Set<Integer> flags = new HashSet<>();
        metadata.getFlags().forEach((flag, value) -> {
            int id = nativeFlagId(flag);
            if (Boolean.TRUE.equals(value) && id >= 0 && id < 128) flags.add(id);
        });
        return hasBaseFlags(metadata) || hasExtendedFlags(metadata) ? flags : null;
    }

    public static boolean hasBaseFlags(EntityDataMap metadata) { return hasFlagWord(metadata, 0); }
    public static boolean hasExtendedFlags(EntityDataMap metadata) { return hasFlagWord(metadata, 1); }

    private static boolean hasFlagWord(EntityDataMap metadata, int word) {


        return metadata != null && metadata.getFlags() != null && metadata.getFlags().keySet().stream()
                .mapToInt(EntityMetadataUtil::nativeFlagId).anyMatch(id -> id >= word * 64 && id < (word + 1) * 64);
    }

    public static void writeFlags(EntityDataMap metadata, Set<Integer> flags) {
        EnumMap<EntityFlag, Boolean> values = new EnumMap<>(EntityFlag.class);
        for (int id = 0; id < 128; id++) {
            EntityFlag flag = FlagIds.flag(id);
            if (flag != null) values.put(flag, flags.contains(id));
        }
        metadata.putFlags(values);
    }

    public static EntityDataMap copy(EntityDataMap source) {
        EntityDataMap copy = new EntityDataMap();
        source.forEach((type, value) -> copy.put(type,
                value instanceof EnumMap<?, ?> flags ? flags.clone()
                        : value instanceof byte[] bytes ? bytes.clone()
                        : value instanceof int[] ints ? ints.clone()
                        : value instanceof long[] longs ? longs.clone() : value));
        return copy;
    }

    public static void copyProperties(EntityDataMap source, EntityDataMap target) {
        source.forEach((type, value) -> {
            if (type != EntityDataTypes.FLAGS && type != EntityDataTypes.FLAGS_2) target.put(type, value);
        });
    }
    public interface Metadata {
        boolean exists(int nativeKey);
        float getFloat(int nativeKey);
        long getLong(int nativeKey);
    }
    private EntityMetadataUtil() {
    }

    public static Float getFloat(EntityDataMap metadata, EntityDataType<Float> key) {
        if (metadata == null || !metadata.containsKey(key)) return null;
        float value = metadata.get(key, Float.class);
        return Float.isFinite(value) ? value : null;
    }

    public static Float getFloat(final Metadata metadata, final int key) {
        if (metadata == null || !metadata.exists(key)) {
            return null;
        }

        final float value = metadata.getFloat(key);
        return Float.isFinite(value) ? value : null;
    }

    public static Set<Integer> copyFlags(final Metadata metadata) {
        if (metadata == null) {
            return null;
        }

        final boolean hasBaseFlags = metadata.exists(0);
        final boolean hasExtendedFlags = metadata.exists(92);
        if (!hasBaseFlags && !hasExtendedFlags) {
            return null;
        }

        final Set<Integer> flags = new HashSet<>();
        if (hasBaseFlags) {
            copyBits(flags, metadata.getLong(0), 0);
        }
        if (hasExtendedFlags) {
            copyBits(flags, metadata.getLong(92), Long.SIZE);
        }
        return flags;
    }

    public static boolean hasFlag(final Metadata metadata, final int flag) {
        if (metadata == null || flag < 0) {
            return false;
        }

        if (flag < Long.SIZE) {
            return metadata.exists(0)
                    && (metadata.getLong(0) & (1L << flag)) != 0;
        }

        final int extendedBit = flag - Long.SIZE;
        return extendedBit < Long.SIZE
                && metadata.exists(92)
                && (metadata.getLong(92) & (1L << extendedBit)) != 0;
    }


    public static Set<Integer> mergeFlags(final Set<Integer> current, final Set<Integer> update,
                                          boolean hasBaseFlags, boolean hasExtendedFlags) {
        final Set<Integer> flags = new HashSet<>(current);
        flags.removeIf(flag -> flag >= 0 && (flag < Long.SIZE ? hasBaseFlags
                : flag < 2 * Long.SIZE && hasExtendedFlags));
        for (int flag : update) {
            if (flag >= 0 && (flag < Long.SIZE ? hasBaseFlags
                    : flag < 2 * Long.SIZE && hasExtendedFlags)) flags.add(flag);
        }
        return flags;
    }

    private static void copyBits(final Set<Integer> flags, final long rawFlags, final int offset) {
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if ((rawFlags & (1L << bit)) != 0) {
                flags.add(offset + bit);
            }
        }
    }
}
