package ac.navro.anticheat.data.vanilla;

import java.util.HashMap;
import java.util.Map;


public class AttributeInstance implements Cloneable {
    private float baseValue;
    private float value;
    private boolean dirty = true;
    private final Map<String, AttributeModifierData> modifiers = new HashMap<>();

    public AttributeInstance(float baseValue) { this.baseValue = baseValue; }
    public float getBaseValue() { return baseValue; }
    public Map<String, AttributeModifierData> getModifiers() { return Map.copyOf(modifiers); }
    public float getValue() { if (dirty) { value = computeValue(); dirty = false; } return value; }
    public void setBaseValue(float value) { if (baseValue == value) return; baseValue = value; dirty = true; }
    public void setValue(float value) { this.value = value; dirty = false; }
    public void setDirty() { dirty = true; }
    public void clearModifiers() { if (!modifiers.isEmpty()) { modifiers.clear(); update(); } }
    public void removeModifier(String id) { if (modifiers.remove(id) != null) update(); }
    public void addTemporaryModifier(AttributeModifierData modifier) { if (modifiers.putIfAbsent(modifier.id(), modifier) == null) update(); }
    private void update() { value = computeValue(); }
    private float computeValue() {
        float base = baseValue;
        for (AttributeModifierData modifier : modifiers.values()) if (modifier.operation == AttributeOperation.ADDITION) base += modifier.amount;
        float result = base;
        for (AttributeModifierData modifier : modifiers.values()) if (modifier.operation == AttributeOperation.MULTIPLY_BASE) result += base * modifier.amount;
        for (AttributeModifierData modifier : modifiers.values()) if (modifier.operation == AttributeOperation.MULTIPLY_TOTAL) result *= 1.0F + modifier.amount;
        return result;
    }
    @Override public AttributeInstance clone() { AttributeInstance copy = new AttributeInstance(baseValue); copy.value=value; copy.dirty=dirty; copy.modifiers.putAll(modifiers); return copy; }
    public enum AttributeOperation { ADDITION, MULTIPLY_BASE, MULTIPLY_TOTAL }
    public record AttributeModifierData(String id, AttributeOperation operation, float amount) {}
}
