package ac.navro.proxy.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;


public final class EntityBatchEncoder extends ChannelOutboundHandlerAdapter implements AutoCloseable {
    private static final AtomicLong LATENCY_ID_COUNTER = new AtomicLong();
    private final BedrockSession session;
    private final LatencyUtil latency;
    private final Map<BedrockPacket, List<Runnable>> staged = new IdentityHashMap<>();
    private final Map<BedrockBatchWrapper, List<Marker>> prepared = new IdentityHashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private final Set<Long> ownMarkers = new HashSet<>();
    private boolean closed;
    private boolean flushScheduled;

    private EntityBatchEncoder(BedrockSession session, LatencyUtil latency) {
        this.session = session;
        this.latency = latency;
    }

    public static EntityBatchEncoder install(BedrockSession session, LatencyUtil latency) {
        Channel channel = session.getPeer().getChannel();
        if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Install on the session event loop");
        EntityBatchEncoder encoder = new EntityBatchEncoder(session, latency);
        channel.pipeline().addBefore(CompressionCodec.NAME, "navro-ack-publish", encoder.new Publisher());
        channel.pipeline().addAfter(CompressionCodec.NAME, "navro-ack-batch", encoder);
        return encoder;
    }

    public synchronized void track(BedrockPacket packet, Runnable task) {
        if (closed) throw new IllegalStateException("Batch ACK transport closed");
        staged.computeIfAbsent(packet, ignored -> new ArrayList<>()).add(task);
    }

    public synchronized void discard(BedrockPacket packet) { staged.remove(packet); }

    public synchronized void markPending(Runnable task) {
        if (closed) return;
        pending.add(task);
        if (!flushScheduled) {
            flushScheduled = true;
            session.getPeer().getChannel().eventLoop().execute(() -> {
                synchronized (EntityBatchEncoder.this) {
                    flushScheduled = false;
                    if (closed || pending.isEmpty()) return;
                }
                keepalive();
            });
        }
    }

    public synchronized boolean hasPending() {
        return !staged.isEmpty() || !pending.isEmpty() || !prepared.isEmpty();
    }

    public void keepalive() {
        synchronized (this) {
            if (closed) return;
            long id = nextLatencyId();
            ownMarkers.add(id);
            NetworkStackLatencyPacket marker = new NetworkStackLatencyPacket();
            marker.setTimestamp(id);
            marker.setFromServer(true);

            session.sendPacket(marker);
        }
    }

    private static long nextLatencyId() {
        return Math.floorMod(LATENCY_ID_COUNTER.getAndIncrement(), 999_999_999L) + 1L;
    }

    @Override public synchronized void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (closed || !(msg instanceof BedrockBatchWrapper batch)) {
            ctx.write(msg, promise);
            return;
        }
        List<Marker> wireMarkers = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        boolean gameData = false;
        for (BedrockPacketWrapper wrapper : batch.getPackets()) {
            BedrockPacket packet = wrapper.getPacket();
            List<Runnable> attached = staged.get(packet);
            if (attached != null) tasks.addAll(attached);
            if (packet instanceof NetworkStackLatencyPacket marker) {
                long id = marker.getTimestamp();
                wireMarkers.add(new Marker(id, ownMarkers.contains(id), null));
            } else gameData = true;
        }
        tasks.addAll(pending);
        if (gameData || !tasks.isEmpty()) {
            long id = nextLatencyId();
            NetworkStackLatencyPacket marker = new NetworkStackLatencyPacket();
            marker.setTimestamp(id);
            marker.setFromServer(true);
            ByteBuf frame = ctx.alloc().buffer();
            ByteBuf augmented = null;
            try {
                int packetId = session.getCodec().getPacketDefinition(NetworkStackLatencyPacket.class).getId();
                VarInts.writeUnsignedInt(frame, packetId);
                session.getCodec().tryEncode(session.getPeer().getCodecHelper(), frame, marker);
                ByteBuf raw = batch.getUncompressed();
                augmented = ctx.alloc().buffer(raw.readableBytes() + 5 + frame.readableBytes());
                augmented.writeBytes(raw, raw.readerIndex(), raw.readableBytes());
                VarInts.writeUnsignedInt(augmented, frame.readableBytes());
                augmented.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                batch.setUncompressed(augmented);
                augmented = null;
                batch.modify();
                List<Runnable> snapshot = List.copyOf(tasks);
                wireMarkers.add(new Marker(id, true, () -> {
                    for (Runnable task : snapshot) {
                        if (closed) return;
                        try { task.run(); } catch (Throwable ignored) { }
                    }
                }));
            } finally {
                frame.release();
                if (augmented != null) augmented.release();
            }
        }
        prepared.put(batch, wireMarkers);
        ChannelPromise completion = promise.unvoid();
        completion.addListener(future -> {
            if (!future.isSuccess()) {
                synchronized (EntityBatchEncoder.this) { prepared.remove(batch); }
            }
        });
        ctx.write(msg, completion);
    }

    private final class Publisher extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            synchronized (EntityBatchEncoder.this) {
                if (msg instanceof BedrockBatchWrapper batch) {
                    List<Marker> markers = prepared.remove(batch);
                    if (markers != null) {

                        for (BedrockPacketWrapper packet : batch.getPackets()) staged.remove(packet.getPacket());
                        pending.clear();
                        for (Marker marker : markers) {
                            ownMarkers.remove(marker.id());
                            latency.queue(marker.id(), marker.ours(), marker.task());
                        }
                    }
                }
                ctx.write(msg, promise);
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        staged.clear();
        prepared.clear();
        pending.clear();
        ownMarkers.clear();
        latency.close();
    }

    private record Marker(long id, boolean ours, Runnable task) { }
}
