package ac.navro.proxy.collision;

import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NativePlantProperties;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.math.vector.Vector3i;
import java.util.List;


public final class PlantCollision {
    private PlantCollision() { }

    public static List<Box> bamboo(final Vector3i position, final NetworkBlockState state) {
        final boolean thick = state.hasProperty("bamboo_stalk_thickness")
                ? "thick".equals(state.stringProperty("bamboo_stalk_thickness", "thin"))
                : NativePlantProperties.bambooThick(state);
        final float max = thick ? 0.6875F : 0.625F;
        return List.of(new Box(0.5F, 0, 0.5F, max, 1, max)
                .offset(horizontalOffset(position, 0.25F)));
    }

    public static List<Box> pointedDripstone(final Vector3i position, final NetworkBlockState state) {
        final String thickness = state.stringProperty("dripstone_thickness",
                NativePlantProperties.dripstoneThickness(state));
        final boolean hanging = state.booleanProperty("hanging",
                NativePlantProperties.dripstoneHanging(state));
        final Box shape = switch (thickness) {
            case "frustum" -> new Box(0.25F, 0, 0.25F, 0.75F, 1, 0.75F);
            case "middle" -> new Box(0.1875F, 0, 0.1875F, 0.8125F, 1, 0.8125F);
            case "base" -> new Box(0.125F, 0, 0.125F, 0.875F, 1, 0.875F);
            case "merge", "tip_merge" -> new Box(0.3125F, 0, 0.3125F, 0.6875F, 1, 0.6875F);
            default -> new Box(0.3125F, hanging ? 0.3125F : 0, 0.3125F,
                    0.6875F, hanging ? 1 : 0.6875F, 0.6875F);
        };
        return List.of(shape.offset(horizontalOffset(position, 0.125F)));
    }

    private static Vec3 horizontalOffset(final Vector3i position, final float limit) {


        long seed = (long) (position.getX() * 3129871) ^ position.getZ() * 116129781L;
        seed = (int) ((seed * (seed * 42317861L + 11L)) >>> 16);
        final OffsetRandom random = new OffsetRandom(seed);
        final float x = quantize(random.nextFloat(), limit);
        random.nextFloat();
        final float z = quantize(random.nextFloat(), limit);
        return new Vec3(x, 0, z);
    }

    private static float quantize(final float value, final float limit) {
        return -limit + (int) (value * 16F) * ((limit + limit) / 15F);
    }


    private static final class OffsetRandom {
        private long low, high;

        private OffsetRandom(final long seed) {
            final long initial = seed ^ 0x6A09E667F3BCC909L;
            low = mix(initial);
            high = mix(initial + 0x9E3779B97F4A7C15L);
            if ((low | high) == 0) {
                low = 0x9E3779B97F4A7C15L;
                high = 0x6A09E667F3BCC909L;
            }
        }

        private float nextFloat() {
            final long value = Long.rotateLeft(low + high, 17) + low;
            final long difference = low ^ high;
            low = Long.rotateLeft(low, 49) ^ difference ^ difference << 21;
            high = Long.rotateLeft(difference, 28);
            return (value >>> 40) * 5.9604645e-08F;
        }

        private static long mix(long value) {
            value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
            value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
            return value ^ value >>> 31;
        }
    }
}
