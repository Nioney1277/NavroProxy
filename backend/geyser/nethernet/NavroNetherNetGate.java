package org.geysermc.geyser.network.bedrock.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.bedrock.InvalidPacketHandler;
import org.geysermc.geyser.network.bedrock.nethernet.codec.NetherNetFrameCodec;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class NavroNetherNetGate extends ChannelDuplexHandler {
    private static final int MAGIC = 0x4e564235;
    private static final int MAX_BATCH = 16 * 1024 * 1024;
    private static final int MAX_REPLY = 2 * MAX_BATCH;
    private static final int MAX_GROUP_COUNT = 64;
    private static final int MAX_GROUP_BYTES = 2 * 1024 * 1024;
    private static final int CLIENT = 0;
    private static final int BACKEND = 1;
    private static final int TICK = 2;
    private static final int HIGH_WATER_COUNT = 256;
    private static final int LOW_WATER_COUNT = 64;
    private static final int MAX_PENDING_COUNT = 4096;
    private static final int HIGH_WATER_BYTES = 2 * 1024 * 1024;
    private static final int LOW_WATER_BYTES = 512 * 1024;
    private static final int MAX_PENDING_BYTES = 3 * MAX_BATCH;
    private static final byte[] EMPTY = new byte[0];
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new DefaultThreadFactory("Navro NetherNet checks", true));

    private final GeyserImpl geyser;
    private final Socket socket = new Socket();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private DataInputStream input;
    private DataOutputStream output;
    private ScheduledFuture<?> ticks;
    private Predicate<Boolean> readPressure;
    private List<Pending> inFlight = List.of();
    private int pendingBytes;
    private boolean connected;
    private boolean pumpScheduled;
    private boolean tickPending;
    private boolean throttled;
    private boolean stopped;

    private NavroNetherNetGate(GeyserImpl geyser) {
        this.geyser = geyser;
    }

    static void install(Channel channel, GeyserImpl geyser) {
        install(channel, geyser, null);
    }

    static void install(Channel channel, GeyserImpl geyser, Predicate<Boolean> readPressure) {
        var gate = new NavroNetherNetGate(geyser);
        gate.readPressure = readPressure;
        channel.pipeline().addBefore(NetherNetFrameCodec.NAME, "navro-nethernet-checks", gate);
        if (channel.pipeline().get(InvalidPacketHandler.class) != null) {
            channel.pipeline().replace(InvalidPacketHandler.class, InvalidPacketHandler.NAME, new InvalidPacketHandler() {
                @Override
                public void setSession(GeyserSession session) {
                    super.setSession(session);
                    gate.readPressure = new JavaReadPressure(() -> {
                        var downstream = session.getDownstream();
                        return downstream == null || !downstream.isState(ProtocolState.GAME)
                                ? null : downstream.getSession().getChannel();
                    });
                    gate.updateBackpressure();
                }
            });
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        var player = context.channel().attr(NetherNetChildChannel.PLAYER_INFO).get();
        submit(context, () -> {
            var key = player == null ? null : player.clientPublicKey();
            byte[] encodedKey = key == null ? EMPTY : key.getEncoded();
            if (encodedKey == null || encodedKey.length == 0 || encodedKey.length > 512) {
                throw new IOException("Missing or invalid NetherNet transport identity");
            }
            int port = Integer.getInteger("navro.nethernet.adapter-port", 19138);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(8000);
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeInt(MAGIC);
            output.writeInt(encodedKey.length);
            output.write(encodedKey);
            output.flush();
            if (input.readInt() != MAGIC) throw new IOException("Navro did not enable NetherNet checks");
            return true;
        }, ignored -> {
            connected = true;
            ticks = context.executor().scheduleWithFixedDelay(() -> enqueueTick(context), 50, 50, TimeUnit.MILLISECONDS);
            context.fireChannelActive();
            schedulePump(context);
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        enqueue(context, CLIENT, message, null);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        enqueue(context, BACKEND, message, promise);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext context) {
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        stop(new ClosedChannelException());
        context.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        stop(new ClosedChannelException());
    }

    private void enqueue(ChannelHandlerContext context, int direction, Object message, ChannelPromise promise) {
        try {
            if (stopped) throw new ClosedChannelException();
            if (!(message instanceof ByteBuf buffer) || !buffer.isReadable() || buffer.readableBytes() > MAX_BATCH) {
                throw new IOException("Invalid NetherNet batch");
            }
            int size = buffer.readableBytes();
            if (pendingCount() >= MAX_PENDING_COUNT || size > MAX_PENDING_BYTES - pendingBytes) {
                throw new IOException("NetherNet bridge backlog exceeded its safety limit ("
                        + pendingCount() + " queued, " + pendingBytes + " bytes, "
                        + inFlight.size() + " in flight, next batch " + size + " bytes)");
            }
            byte[] bytes = new byte[size];
            buffer.getBytes(buffer.readerIndex(), bytes);
            pending.addLast(new Pending(direction, bytes, promise));
            pendingBytes += size;
            updateBackpressure();
            schedulePump(context);
        } catch (Exception error) {
            if (promise != null) promise.tryFailure(error);
            fail(context, error);
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    private void enqueueTick(ChannelHandlerContext context) {
        if (stopped || tickPending || pendingCount() >= MAX_PENDING_COUNT) return;
        tickPending = true;
        pending.addLast(new Pending(TICK, EMPTY, null));
        schedulePump(context);
    }

    private int pendingCount() {
        return pending.size() + inFlight.size();
    }

    private void schedulePump(ChannelHandlerContext context) {
        if (stopped || !connected || pumpScheduled || !inFlight.isEmpty() || pending.isEmpty()) return;
        pumpScheduled = true;
        context.executor().execute(() -> pump(context));
    }

    private void pump(ChannelHandlerContext context) {
        pumpScheduled = false;
        if (stopped || pending.isEmpty()) return;
        List<Pending> group = new ArrayList<>();
        int size = 0;
        while (group.size() < MAX_GROUP_COUNT && !pending.isEmpty()) {
            Pending next = pending.peekFirst();
            if (!group.isEmpty() && next.bytes().length > MAX_GROUP_BYTES - size) break;
            group.add(pending.removeFirst());
            size += next.bytes().length;
            if (size > MAX_GROUP_BYTES) break;
        }
        inFlight = group;
        submit(context, () -> exchange(group), replies -> complete(context, group, replies));
    }

    private <Result> void submit(ChannelHandlerContext context, Callable<Result> task, Consumer<Result> complete) {
        try {
            WORKERS.execute(() -> {
                try {
                    Result result = task.call();
                    post(context, () -> complete.accept(result));
                } catch (Exception error) {
                    post(context, () -> fail(context, error));
                }
            });
        } catch (RejectedExecutionException error) {
            fail(context, new IOException("NetherNet bridge has too many active connections", error));
        }
    }

    private void post(ChannelHandlerContext context, Runnable completion) {
        try {
            context.executor().execute(() -> {
                if (stopped) return;
                try {
                    completion.run();
                } catch (Exception error) {
                    fail(context, error);
                }
            });
        } catch (RejectedExecutionException ignored) {
            closeSocket();
        }
    }

    private List<Reply> exchange(List<Pending> group) throws IOException {
        output.writeByte(group.size());
        for (Pending request : group) {
            output.writeByte(request.direction());
            output.writeInt(request.bytes().length);
            output.write(request.bytes());
        }
        output.flush();

        var limits = new ReplyLimits(MAX_PENDING_BYTES);
        var replies = new ArrayList<Reply>(group.size());
        for (Pending request : group) {
            Reply reply = readReply(limits);
            if (request.direction() == TICK && reply.forwarded() != null) throw new IOException("Unexpected forwarded tick batch");
            replies.add(reply);
        }
        return replies;
    }

    private Reply readReply(ReplyLimits limits) throws IOException {
        limits.replyBytes = MAX_REPLY;
        int size = input.readInt();
        byte[] forwarded = size == -1 ? null : readBatch(size, limits);
        return new Reply(forwarded, readOutputs(limits), readOutputs(limits));
    }

    private List<byte[]> readOutputs(ReplyLimits limits) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 4096) throw new IOException("Invalid Navro output count");
        List<byte[]> outputs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            outputs.add(readBatch(input.readInt(), limits));
        }
        return outputs;
    }

    private byte[] readBatch(int size, ReplyLimits limits) throws IOException {
        if (size <= 0 || size > MAX_BATCH || size > limits.replyBytes || size > limits.groupBytes) {
            throw new IOException("Invalid Navro batch size");
        }
        limits.replyBytes -= size;
        limits.groupBytes -= size;
        byte[] batch = input.readNBytes(size);
        if (batch.length != size) throw new EOFException("Incomplete Navro batch");
        return batch;
    }

    private static ByteBuf buffer(byte[] batch) {
        return Unpooled.wrappedBuffer(batch);
    }

    private void complete(ChannelHandlerContext context, List<Pending> group, List<Reply> replies) {
        boolean read = false;
        boolean wrote = false;
        for (int index = 0; index < group.size() && !stopped; index++) {
            Pending request = group.get(index);
            Reply reply = replies.get(index);
            pendingBytes -= request.bytes().length;
            if (request.direction() == TICK) tickPending = false;
            if (request.direction() == BACKEND) {
                if (reply.forwarded() == null) request.promise().trySuccess();
                else {
                    context.write(buffer(reply.forwarded()), request.promise());
                    wrote = true;
                }
            } else if (reply.forwarded() != null) {
                context.fireChannelRead(buffer(reply.forwarded()));
                read = true;
            }
            for (byte[] extra : reply.clientbound()) {
                if (stopped) break;
                context.write(buffer(extra), context.voidPromise());
                wrote = true;
            }
            for (byte[] extra : reply.backendbound()) {
                if (stopped) break;
                context.fireChannelRead(buffer(extra));
                read = true;
            }
        }
        if (stopped) return;
        if (wrote) context.flush();
        if (read) context.fireChannelReadComplete();
        inFlight = List.of();
        updateBackpressure();
        schedulePump(context);
    }

    private void updateBackpressure() {
        if (readPressure == null || stopped) return;
        int count = pendingCount();
        if (!throttled && (count >= HIGH_WATER_COUNT || pendingBytes >= HIGH_WATER_BYTES)) {
            setBackpressure(true);
        } else if (throttled && count <= LOW_WATER_COUNT && pendingBytes <= LOW_WATER_BYTES) {
            setBackpressure(false);
        }
    }

    private void setBackpressure(boolean pause) {
        if (throttled == pause || readPressure == null) return;
        boolean applied = readPressure.test(pause);
        throttled = pause && applied;
    }

    private void fail(ChannelHandlerContext context, Exception error) {
        if (stopped) return;
        stop(error);
        context.close();
        if (geyser != null) {
            geyser.getLogger().warning("Closing NetherNet session: Navro checks unavailable or rejected the batch ("
                    + error.getClass().getSimpleName() + ": " + error.getMessage() + ").");
        }
    }

    private void stop(Exception error) {
        if (stopped) return;
        stopped = true;
        if (ticks != null) ticks.cancel(false);
        closeSocket();
        try {
            setBackpressure(false);
        } catch (RejectedExecutionException ignored) {
        }
        inFlight.forEach(request -> request.fail(error));
        pending.forEach(request -> request.fail(error));
        pending.clear();
        inFlight = List.of();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private record Pending(int direction, byte[] bytes, ChannelPromise promise) {
        void fail(Exception error) {
            if (promise != null) promise.tryFailure(error);
        }
    }

    static final class JavaReadPressure implements Predicate<Boolean> {
        private final Supplier<Channel> downstreamChannel;
        private ReadPause pause;

        JavaReadPressure(Supplier<Channel> downstreamChannel) {
            this.downstreamChannel = downstreamChannel;
        }

        @Override
        public boolean test(Boolean requested) {
            if (requested) {
                if (pause != null) return true;
                Channel channel = downstreamChannel.get();
                if (channel == null || !channel.isActive()) return false;
                var next = new ReadPause(channel);
                next.apply(true);
                pause = next;
            } else if (pause != null) {
                pause.apply(false);
                pause = null;
            }
            return true;
        }

        private static final class ReadPause {
            private final Channel channel;
            private boolean ownsPause;

            private ReadPause(Channel channel) {
                this.channel = channel;
            }

            private void apply(boolean requested) {
                // The Java channel's event loop controls reads. The Geyser session
                // executor may already have a backlog of translated packet tasks.
                var loop = channel.eventLoop();
                if (loop.inEventLoop()) {
                    set(requested);
                } else {
                    loop.execute(() -> set(requested));
                }
            }

            private void set(boolean requested) {
                if (!channel.isActive()) return;
                if (requested && channel.config().isAutoRead()) {
                    channel.config().setAutoRead(false);
                    ownsPause = true;
                } else if (!requested && ownsPause) {
                    channel.config().setAutoRead(true);
                    ownsPause = false;
                }
            }
        }
    }

    private static final class ReplyLimits {
        private int groupBytes;
        private int replyBytes;

        private ReplyLimits(int groupBytes) {
            this.groupBytes = groupBytes;
        }
    }

    private record Reply(byte[] forwarded, List<byte[]> clientbound, List<byte[]> backendbound) {
    }
}
