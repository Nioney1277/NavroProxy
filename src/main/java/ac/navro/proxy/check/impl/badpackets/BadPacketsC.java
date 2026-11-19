package ac.navro.proxy.check.impl.badpackets;

import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.util.function.LongSupplier;


@CheckInfo(name = "Bad Packet", type = "C")
public final class BadPacketsC extends PacketCheck {
    private static final long NO_PENDING_TICK = Long.MIN_VALUE;
    private long pendingTick = NO_PENDING_TICK;
    private long pendingSinceAuthInput;
    private long pendingGapMs;
    private final LongSupplier clock;

    public BadPacketsC(WirePlayerState player) { this(player, System::currentTimeMillis); }

    BadPacketsC(WirePlayerState player, LongSupplier clock) {
        super(player);
        this.clock = clock;
    }

    @Override public boolean onPacketReceive(BedrockPacket input, boolean cancelled) {
        if (cancelled || !(input instanceof PlayerAuthInputPacket packet)) return cancelled;
        long claimedTick = packet.getTick();
        pendingTick = NO_PENDING_TICK;
        if (claimedTick < 0L || claimedTick <= player.tick()) {
            fail("expected>" + player.tick() + ",actual=" + claimedTick);
            return true;
        }
        long distanceSincePrevious = clock.getAsLong() - player.sinceAuthInput;
        long nextTick;
        if (distanceSincePrevious < 50L) nextTick = player.tick() + 1L;
        else nextTick = player.tick() + Math.min(claimedTick - player.tick(), distanceSincePrevious / 30L);
        if (nextTick != claimedTick) {
            fail("expected=" + nextTick + ",actual=" + claimedTick + ",arrivalMs=" + distanceSincePrevious);
            return true;
        }
        pendingTick = nextTick;
        pendingSinceAuthInput = clock.getAsLong();
        pendingGapMs = distanceSincePrevious;
        return false;
    }

    public void commit() {
        if (pendingTick == NO_PENDING_TICK) return;
        player.commitInputTick(pendingTick);
        player.sinceAuthInput = pendingSinceAuthInput;
        player.lastAuthInputGapMs = pendingGapMs;
        pendingTick = NO_PENDING_TICK;
    }

    public void discard() { pendingTick = NO_PENDING_TICK; }
}
