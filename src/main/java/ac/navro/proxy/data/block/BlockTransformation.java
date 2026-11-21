package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BlockTransformation {
    private static final Set<String> FIELDS = Set.of(
            "RX", "RY", "RZ", "SX", "SY", "SZ", "TX", "TY", "TZ",
            "RXP", "RYP", "RZP", "SXP", "SYP", "SZP");

    private BlockTransformation() { }

    static List<Box> apply(Object value, List<Box> boxes) {
        if (!(value instanceof NbtMap transform)) return null;
        float[] matrix = matrix(transform);
        if (matrix == null) return null;
        var result = new ArrayList<Box>();
        for (Box box : boxes) {
            float[] minimum = {box.minX - .5F, box.minY - .5F, box.minZ - .5F};
            float[] maximum = {box.maxX - .5F, box.maxY - .5F, box.maxZ - .5F};
            float[] extent = new float[3], center = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                extent[axis] = (maximum[axis] - minimum[axis]) * .5F;
                center[axis] = minimum[axis] + extent[axis];
            }
            for (int axis = 0; axis < 3; axis++) {

                float transformedCenter = (matrix[axis] * center[0] + matrix[4 + axis] * center[1])
                        + (matrix[8 + axis] * center[2] + matrix[12 + axis]);
                float transformedExtent = (Math.abs(matrix[axis]) * extent[0] + Math.abs(matrix[4 + axis]) * extent[1])
                        + Math.abs(matrix[8 + axis]) * extent[2];
                minimum[axis] = (transformedCenter - transformedExtent) + .5F;
                maximum[axis] = (transformedCenter + transformedExtent) + .5F;
                if (!Float.isFinite(minimum[axis]) || !Float.isFinite(maximum[axis])) return null;
            }
            var transformed = new Box(minimum[0], minimum[1], minimum[2], maximum[0], maximum[1], maximum[2]);
            if (transformed.isValid()) result.add(transformed);
        }
        return List.copyOf(result);
    }


    static float[] matrix(NbtMap transform) {
        if (!FIELDS.containsAll(transform.keySet())) return null;
        for (Object value : transform.values()) {
            if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) return null;
        }
        int[] rotations = new int[3];
        String[] axes = {"X", "Y", "Z"};
        for (int axis = 0; axis < 3; axis++) {
            Number value = (Number) transform.getOrDefault("R" + axes[axis], 0);
            double rotation = value.doubleValue();
            if (rotation != Math.rint(rotation) || rotation < Integer.MIN_VALUE || rotation > Integer.MAX_VALUE) return null;

            rotations[axis] = value.intValue() < 0 ? 0 : value.intValue() % 4;
        }
        float[] matrix = rotationMatrix(rotations);
        for (int axis = 0; axis < 3; axis++) {
            matrix[12 + axis] = component(transform, "T" + axes[axis], 0F)
                    + component(transform, "R" + axes[axis] + "P", 0F);
        }
        for (int row = 0; row < 3; row++) {
            float translation = matrix[12 + row];
            translation = (-component(transform, "RYP", 0F) * matrix[4 + row]
                    - component(transform, "RXP", 0F) * matrix[row])
                    - component(transform, "RZP", 0F) * matrix[8 + row] + translation;
            translation = (component(transform, "SXP", 0F) * matrix[row]
                    + component(transform, "SYP", 0F) * matrix[4 + row])
                    + component(transform, "SZP", 0F) * matrix[8 + row] + translation;
            matrix[row] *= component(transform, "SX", 1F);
            matrix[4 + row] *= component(transform, "SY", 1F);
            matrix[8 + row] *= component(transform, "SZ", 1F);
            matrix[12 + row] = (-component(transform, "SYP", 0F) * matrix[4 + row]
                    - component(transform, "SXP", 0F) * matrix[row])
                    - component(transform, "SZP", 0F) * matrix[8 + row] + translation;
        }
        for (float value : matrix) if (!Float.isFinite(value)) return null;
        return matrix;
    }

    private static float component(NbtMap transform, String key, float fallback) {
        return ((Number) transform.getOrDefault(key, fallback)).floatValue();
    }

    private static float[] rotationMatrix(int[] rotations) {
        float[] baked = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

        for (int axis = 2; axis >= 0; axis--) {
            int first = (axis + 1) % 3, second = (axis + 2) % 3;
            for (int turn = 0; turn < rotations[axis]; turn++) {
                for (int row = 0; row < 3; row++) {
                    float previous = baked[first * 4 + row];
                    baked[first * 4 + row] = baked[second * 4 + row];
                    baked[second * 4 + row] = -previous;
                }
            }
        }


        float[] candidates = {
                baked[0] + baked[5] + baked[10],
                baked[0] - baked[5] - baked[10],
                baked[5] - baked[0] - baked[10],
                baked[10] - baked[0] - baked[5]
        };
        int largest = 0;
        for (int index = 1; index < 4; index++) if (candidates[index] > candidates[largest]) largest = index;
        float biggest = (float) Math.sqrt(candidates[largest] + 1F) * .5F;
        float multiplier = .25F / biggest;
        float x, y, z, w;
        switch (largest) {
            case 0 -> {
                w = biggest;
                x = (baked[6] - baked[9]) * multiplier;
                y = (baked[8] - baked[2]) * multiplier;
                z = (baked[1] - baked[4]) * multiplier;
            }
            case 1 -> {
                x = biggest;
                w = (baked[6] - baked[9]) * multiplier;
                y = (baked[4] + baked[1]) * multiplier;
                z = (baked[8] + baked[2]) * multiplier;
            }
            case 2 -> {
                y = biggest;
                w = (baked[8] - baked[2]) * multiplier;
                x = (baked[4] + baked[1]) * multiplier;
                z = (baked[9] + baked[6]) * multiplier;
            }
            case 3 -> {
                z = biggest;
                w = (baked[1] - baked[4]) * multiplier;
                x = (baked[8] + baked[2]) * multiplier;
                y = (baked[9] + baked[6]) * multiplier;
            }
            default -> throw new IllegalStateException();
        }
        return new float[]{
                (1F - 2F * z * z) - 2F * y * y, 2F * x * y + 2F * w * z, 2F * x * z - 2F * w * y, 0,
                2F * x * y - 2F * w * z, (1F - 2F * z * z) - 2F * x * x, 2F * y * z + 2F * w * x, 0,
                2F * x * z + 2F * w * y, 2F * y * z - 2F * w * x, (1F - 2F * x * x) - 2F * y * y, 0,
                0, 0, 0, 1
        };
    }
}
