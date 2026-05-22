package com.ittai.debugbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class EventBus {

    public record DebugEvent(
        long seq,
        long timestampMs,
        String kind,
        String threadId,
        java.util.Map<String, Object> payload
    ) {}

    private final LinkedBlockingDeque<DebugEvent> queue;
    private final int capacity;
    private long seq = 0;

    public EventBus(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingDeque<>(capacity);
    }

    public synchronized DebugEvent publish(String kind, String threadId, java.util.Map<String, Object> payload) {
        DebugEvent ev = new DebugEvent(++seq, System.currentTimeMillis(), kind, threadId, payload);
        while (!queue.offerLast(ev)) {
            queue.pollFirst();
        }
        notifyAll();
        return ev;
    }

    public DebugEvent poll(long timeoutMs) throws InterruptedException {
        return queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public synchronized long currentSeq() {
        return seq;
    }

    public synchronized DebugEvent waitAfter(long afterSeq, long timeoutMs) throws InterruptedException {
        return waitAfter(afterSeq, timeoutMs, ev -> true);
    }

    public synchronized DebugEvent waitAfter(long afterSeq, long timeoutMs, Predicate<DebugEvent> filter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            for (DebugEvent ev : queue) {
                if (ev.seq() > afterSeq && filter.test(ev)) {
                    queue.remove(ev);
                    return ev;
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return null;
            wait(remaining);
        }
    }

    public List<DebugEvent> snapshot(int limit) {
        List<DebugEvent> all = new ArrayList<>(queue);
        if (all.size() <= limit) return all;
        return all.subList(all.size() - limit, all.size());
    }

    public int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
        seq = 0;
    }

    public int capacity() {
        return capacity;
    }
}
