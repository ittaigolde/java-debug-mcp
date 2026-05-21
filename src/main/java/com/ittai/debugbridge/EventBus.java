package com.ittai.debugbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

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
        return ev;
    }

    public DebugEvent poll(long timeoutMs) throws InterruptedException {
        return queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS);
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
