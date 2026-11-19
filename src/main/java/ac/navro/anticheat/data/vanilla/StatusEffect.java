package ac.navro.anticheat.data.vanilla;


public final class StatusEffect {
    private final int effectId;
    private final int amplifier;
    private int duration;

    public StatusEffect(int effectId, int amplifier, int duration) {
        this.effectId = effectId;
        this.amplifier = amplifier;
        this.duration = duration;
    }

    public int effectId() { return effectId; }
    public int amplifier() { return amplifier; }
    public int duration() { return duration; }
    public void tick() { if (duration > 0) duration--; }
    public StatusEffect copy() { return new StatusEffect(effectId, amplifier, duration); }
}
