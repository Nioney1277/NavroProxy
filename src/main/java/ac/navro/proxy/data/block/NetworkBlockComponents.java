package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.util.ItemUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NetworkBlockComponents {
    private static final String COLLISION = "minecraft:collision_box";
    private static final String TRANSFORMATION = "minecraft:transformation";
    private static final String FRICTION = "minecraft:friction";
    private final Map<String, NbtMap> definitions;
    private final int protocol;

    public NetworkBlockComponents(List<BlockPropertyData> properties) {
        this(properties, Integer.MAX_VALUE);
    }

    public NetworkBlockComponents(List<BlockPropertyData> properties, int protocol) {
        this.protocol = protocol;
        var definitions = new HashMap<String, NbtMap>();
        for (var property : properties) {
            if (definitions.putIfAbsent(property.getName(), ItemUtil.copyTag(property.getProperties())) != null) {
                throw new IllegalArgumentException("Duplicate block component definition: " + property.getName());
            }
        }
        this.definitions = Map.copyOf(definitions);
    }

    public boolean isEmpty() { return definitions.isEmpty(); }

    public boolean contains(String identifier) { return definitions.containsKey(identifier); }

    public List<Box> collision(NetworkBlockState state) {
        NbtMap components = effectiveComponents(state, Set.of(COLLISION));
        if (components == null) return null;
        var boxes = collisionBoxes(components.get(COLLISION));
        if (boxes == null || boxes.isEmpty()) return boxes;
        NbtMap transformComponents = effectiveComponents(state, Set.of(TRANSFORMATION));
        if (transformComponents == null) return null;
        Object transformation = transformComponents.get(TRANSFORMATION);
        return transformation == null ? boxes : BlockTransformation.apply(transformation, boxes);
    }

    public Float slipperiness(NetworkBlockState state) {
        var components = effectiveComponents(state, Set.of(FRICTION));
        if (components == null || !(components.get(FRICTION) instanceof NbtMap friction)) return null;
        Float value = scalar(friction.get("value"));
        return value == null || value < 0F || value > 1F ? null : 1F - value;
    }

    private NbtMap effectiveComponents(NetworkBlockState state, Set<String> relevant) {
        NbtMap definition = definitions.get(state.identifier());
        if (definition == null) return null;
        Object rawComponents = definition.get("components");
        if (!(rawComponents instanceof NbtMap components)) return null;
        var effective = components.toBuilder();
        var unknown = new HashSet<String>();
        Object rawPermutations = definition.get("permutations");
        if (rawPermutations != null) {
            if (!(rawPermutations instanceof List<?> permutations)) return null;
            for (var rawPermutation : permutations) {
                if (!(rawPermutation instanceof NbtMap permutation)
                        || !(permutation.get("components") instanceof NbtMap overrides)) return null;
                if (relevant.stream().noneMatch(overrides::containsKey)) continue;
                Boolean matches = permutation.get("condition") instanceof String expression
                        ? StateCondition.evaluate(expression, state.stateTag().getCompound("states"),
                                definition.get("molangVersion") instanceof Integer version ? version : null, protocol) : null;
                for (String component : relevant) {
                    if (!overrides.containsKey(component)) continue;
                    if (matches == null) {
                        unknown.add(component);
                    } else if (matches) {
                        effective.put(component, overrides.get(component));
                        unknown.remove(component);
                    }
                }
            }
        }
        return unknown.isEmpty() ? effective.build() : null;
    }

    public List<NbtMap> states() {
        var result = new ArrayList<NbtMap>();
        for (var definition : definitions.entrySet()) {
            List<NbtMap> values = List.of(NbtMap.EMPTY);
            Object rawProperties = definition.getValue().get("properties");
            if (rawProperties != null) {
                if (!(rawProperties instanceof List<?> properties)) continue;
                var names = new java.util.HashSet<String>();
                boolean complete = true;
                for (Object rawProperty : properties) {
                    if (!(rawProperty instanceof NbtMap property) || !(property.get("name") instanceof String name)
                            || !names.add(name) || !(property.get("enum") instanceof List<?> choices) || choices.isEmpty()
                            || choices.stream().anyMatch(value -> !(value instanceof Byte || value instanceof Integer || value instanceof String))) {
                        complete = false;
                        break;
                    }
                    if ((long) values.size() * choices.size() + result.size() > 131072) {
                        throw new IllegalArgumentException("Custom block state limit exceeded");
                    }
                    var expanded = new ArrayList<NbtMap>();
                    for (var state : values) for (var choice : choices) {
                        var builder = state.toBuilder();
                        builder.put(name, choice);
                        expanded.add(builder.build());
                    }
                    values = expanded;
                }
                if (!complete) continue;
            }
            for (var properties : values) {
                result.add(NbtMap.builder().putString("name", definition.getKey()).putCompound("states", properties).build());
                if (result.size() > 131072) throw new IllegalArgumentException("Custom block state limit exceeded");
            }
        }
        return List.copyOf(result);
    }

    private List<Box> collisionBoxes(Object value) {
        if (!(value instanceof NbtMap component)) return null;
        if (component.containsKey("enabled")) {
            Float enabled = scalar(component.get("enabled"));
            if (enabled == null || enabled != 0F && enabled != 1F) return null;
            if (enabled == 0F) return List.of();
        }
        var boxes = new ArrayList<Box>();
        if (component.containsKey("boxes")) {
            if (!(component.get("boxes") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 4096) return null;
            for (Object entry : entries) {
                if (!(entry instanceof NbtMap box)) return null;
                Float minX = scalar(box.get("minX")), minY = scalar(box.get("minY")), minZ = scalar(box.get("minZ"));
                Float maxX = scalar(box.get("maxX")), maxY = scalar(box.get("maxY")), maxZ = scalar(box.get("maxZ"));
                if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) return null;
                if (maxX < minX || maxY < minY || maxZ < minZ) return null;
                if (!validBounds(minX, minY, minZ, maxX, maxY, maxZ)) return null;
                if (maxX > minX && maxY > minY && maxZ > minZ) {
                    boxes.add(new Box(minX / 16F, minY / 16F, minZ / 16F, maxX / 16F, maxY / 16F, maxZ / 16F));
                }
            }
        } else {
            if (!(component.get("origin") instanceof List<?> origin) || !(component.get("size") instanceof List<?> size)
                    || origin.size() != 3 || size.size() != 3) return null;
            Float originX = scalar(origin.get(0)), originY = scalar(origin.get(1)), originZ = scalar(origin.get(2));
            Float sizeX = scalar(size.get(0)), sizeY = scalar(size.get(1)), sizeZ = scalar(size.get(2));
            if (originX == null || originY == null || originZ == null || sizeX == null || sizeY == null || sizeZ == null
                    || sizeX < 0 || sizeY < 0 || sizeZ < 0) return null;
            float minX = originX + 8F, minY = originY, minZ = originZ + 8F;
            float maxX = minX + sizeX, maxY = minY + sizeY, maxZ = minZ + sizeZ;
            if (!Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)) return null;
            if (!validBounds(minX, minY, minZ, maxX, maxY, maxZ)) return null;
            if (sizeX > 0 && sizeY > 0 && sizeZ > 0) {
                boxes.add(new Box(minX / 16F, minY / 16F, minZ / 16F, maxX / 16F, maxY / 16F, maxZ / 16F));
            }
        }
        return List.copyOf(boxes);
    }

    private boolean validBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return minX >= 0F && minY >= 0F && minZ >= 0F && maxX <= 16F && maxY <= (protocol >= 898 ? 24F : 16F) && maxZ <= 16F;
    }

    private static Float scalar(Object value) {
        if (!(value instanceof Number number)) return null;
        float result = number.floatValue();
        return Float.isFinite(result) ? result : null;
    }

}
