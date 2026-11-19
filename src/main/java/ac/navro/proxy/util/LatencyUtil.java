package ac.navro.proxy.util;

import ac.navro.proxy.check.api.holder.CheckHolder;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;


public final class LatencyUtil {
    private final CheckHolder checks;
    private boolean detectionEnabled;
    private final ThreadLocal<Long> dispatchingAck = new ThreadLocal<>();
    public Long currentAcknowledgmentId() { return dispatchingAck.get(); }
    private final Deque<Latency> sentQueue = new ConcurrentLinkedDeque<>();
    public volatile Latency prevAcceptedLatency;
    public volatile long prevAcceptedTime = System.currentTimeMillis();

    public LatencyUtil(CheckHolder checks) {
        this.checks = checks;
    }

    public Deque<Latency> sentQueue() { return sentQueue; }

    public synchronized void queue(long id, boolean ours) {
        queue(id, ours, null);
    }

    public synchronized void queue(long id, boolean ours, Runnable task) {
        Latency latency = new Latency(id, System.currentTimeMillis(), System.nanoTime(), ours,
                ours ? new ArrayList<>() : null);
        if (task != null) latency.addTask(task);
        sentQueue.add(latency);
    }

    public void queue(Runnable runnable) {
        synchronized (this) {
            Latency target = sentQueue.peekLast();
            if (target != null) {
                target.addTask(runnable);
                return;
            }
        }
        runnable.run();
    }

    public synchronized boolean hasPending(long id, boolean ours) {
        return sentQueue.stream().anyMatch(latency -> latency.id() == id && latency.ours() == ours);
    }

    public boolean onResponse(long id) {
        List<Latency> released = new ArrayList<>();
        Latency match;
        synchronized (this) {
            match = sentQueue.stream().filter(latency -> latency.id() == id).findFirst().orElse(null);
            if (match == null) return false;
            Latency head;
            do {
                head = sentQueue.removeFirst();
                released.add(head);
            } while (head != match);
        }
        for (Latency latency : released) accept(latency);
        return match.ours();
    }

    public boolean onOrderedResponse() {
        Latency latency;
        synchronized (this) {
            latency = sentQueue.pollFirst();
        }
        if (latency == null) return false;
        accept(latency);
        return latency.ours();
    }

    private void accept(Latency latency) {
        Long previous=dispatchingAck.get();dispatchingAck.set(latency.id());
        try { latency.run(); } finally { if(previous==null)dispatchingAck.remove();else dispatchingAck.set(previous); }
        try {
            onLatencyAccepted(latency);
        } catch (Throwable ignored) {
        }
        prevAcceptedTime = System.currentTimeMillis();
        prevAcceptedLatency = latency;
    }

    public void setDetectionEnabled(boolean enabled) { detectionEnabled = enabled; }

    public void onLatencyAccepted(Latency latency) {
        if (!detectionEnabled || checks == null) return;
        for (final ac.navro.proxy.check.api.Check check : checks.values()) {
            if (!checks.enabled(check.getClass()) || !(check instanceof ac.navro.proxy.check.api.impl.PingBasedCheck pingBasedCheck)) continue;
            pingBasedCheck.onLatencyAccepted(latency);
        }
    }

    public synchronized void close() { sentQueue.clear(); }

    public static final class Latency {
        private final long id, ms, ns;
        private final boolean ours;
        private List<Runnable> tasks;

        public Latency(long id, long ms, long ns, boolean ours, List<Runnable> tasks) {
            this.id = id;
            this.ms = ms;
            this.ns = ns;
            this.ours = ours;
            this.tasks = tasks;
        }

        public long id() { return id; }
        public long ms() { return ms; }
        public long ns() { return ns; }
        public boolean ours() { return ours; }

        public synchronized void addTask(Runnable task) {
            if (tasks == null) tasks = new ArrayList<>();
            tasks.add(task);
        }

        public void run() {
            List<Runnable> pending;
            synchronized (this) {
                pending = tasks;
                tasks = null;
            }
            if (pending == null) return;
            for (Runnable task : pending) {
                try {
                    task.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
