package ac.navro.proxy.check.impl.timer;

import ac.navro.proxy.state.WirePlayerState;
import java.util.function.LongSupplier;

/** Supplies recorded arrival times; both installed checks retain their real accounting and actions. */
public final class RecordedArrivalClock {
    private RecordedArrivalClock() { }

    /** Time is an external fixture condition. Preserve the actual marker, tasks,
     * ordering and checks; substitute only its captured send timestamp. */
    public static void sentAt(WirePlayerState player, long recordedNanos) throws Exception {
        var last = player.getLatencyUtil().sentQueue().peekLast();
        if (last == null) throw new IllegalStateException("No outbound marker to timestamp");
        var field = ac.navro.proxy.util.LatencyUtil.Latency.class.getDeclaredField("ns");
        field.setAccessible(true);
        field.setLong(last, recordedNanos);
    }
    public static void install(WirePlayerState player, LongSupplier clock) {
        player.getCheckHolder().put(Timer.class, new Timer(player, clock, null));
        player.getCheckHolder().put(NegativeTimer.class, new NegativeTimer(player, clock, null));
    }
}
