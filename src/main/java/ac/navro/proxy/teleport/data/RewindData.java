package ac.navro.proxy.teleport.data;

import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.anticheat.data.input.InputPosition;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.state.WirePlayerState;


public final class RewindData extends TeleportData {
    private final long tick;
    private long inputTick;
    private final Vec3 tickEnd;
    private final RewindHistory frame;
    private final float fallbackOffset;
    private final long externalMotionSequence;

    public RewindData(long tick, Vec3 position, Vec3 tickEnd, boolean ground,
                      RewindHistory frame, float fallbackOffset) {
        this(tick, position, tickEnd, ground, frame, fallbackOffset, -1);
    }

    public RewindData(long tick, Vec3 position, Vec3 tickEnd, boolean ground,
                      RewindHistory frame, float fallbackOffset, long externalMotionSequence) {
        this(tick,position,tickEnd,ground,frame,fallbackOffset,externalMotionSequence,tick);
    }
    public RewindData(long tick, Vec3 position, Vec3 tickEnd, boolean ground,
                      RewindHistory frame, float fallbackOffset, long externalMotionSequence,long inputTick) {
        super(position, ground);
        this.inputTick=inputTick;
        this.externalMotionSequence = externalMotionSequence;
        this.tick = tick;
        this.tickEnd = tickEnd.clone();
        this.frame = frame != null && frame.simulationFrame() == tick && frame.matches(position, tickEnd, ground) ? frame : null;
        this.fallbackOffset = fallbackOffset;
    }
    @Override public boolean isHard() { return false; }
    public long tick() { return tick; }
    public long inputTick() { return inputTick; }
    public void observeInputFrame(long internalTick,long clientFrame) {
        if(inputTick<0 && clientFrame==tick)inputTick=internalTick;
    }
    public Vec3 tickEnd() { return tickEnd.clone(); }
    public long externalMotionSequence() { return externalMotionSequence; }
    public float yOffset() { return yOffset(fallbackOffset); }
    public float yOffset(float fallback) { return frame == null ? fallback : frame.yOffset(); }
    public void restoreAabb(WirePlayerState player, MovementAabbSnapshot history) {
        restoreAabb(player, history, fallbackOffset);
    }
    public void restoreAabb(WirePlayerState player, MovementAabbSnapshot history, float sourceOffset) {
        if (frame != null && frame.aabb() != null) {
            player.restoreAabb(frame.aabb(), frame.aabb().position());
        } else {
            var observation = InputPosition.capture(getPosition().toVector3f(), yOffset(sourceOffset));
            if (history == null) player.rebasePosition(observation.decoded(), false);
            else player.restoreAabb(history, observation.physicalTarget(history.box().feetCenter()));
        }
    }
}
