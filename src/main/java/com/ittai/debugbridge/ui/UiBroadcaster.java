package com.ittai.debugbridge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class UiBroadcaster {

    public record Message(long seq, long timestampMs, String event, String json) {}

    public static final class Subscriber {
        final String id;
        final LinkedBlockingDeque<Message> queue;
        volatile boolean active = true;

        Subscriber(String id, int capacity) {
            this.id = id;
            this.queue = new LinkedBlockingDeque<>(capacity);
        }

        public Message poll(long timeoutMs) throws InterruptedException {
            return queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public boolean isActive() { return active; }
        public void close() { active = false; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PER_SUBSCRIBER_CAPACITY = 256;

    private final Map<String, Subscriber> subs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong subSeq = new AtomicLong();

    public Subscriber subscribe() {
        String id = "sub_" + subSeq.incrementAndGet();
        Subscriber s = new Subscriber(id, PER_SUBSCRIBER_CAPACITY);
        subs.put(id, s);
        return s;
    }

    public void unsubscribe(Subscriber s) {
        s.close();
        subs.remove(s.id);
    }

    public boolean hasSubscribers() {
        return !subs.isEmpty();
    }

    public void publish(String event, Object payload) {
        if (subs.isEmpty()) return;
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "serialization failed: " + e.getMessage());
            try {
                json = JSON.writeValueAsString(err);
            } catch (Exception ee) {
                json = "{\"error\":\"serialization failed\"}";
            }
        }
        Message msg = new Message(seq.incrementAndGet(), System.currentTimeMillis(), event, json);
        for (Subscriber s : subs.values()) {
            if (!s.active) continue;
            while (!s.queue.offerLast(msg)) {
                s.queue.pollFirst();
            }
        }
    }

    public void closeAll() {
        for (Subscriber s : subs.values()) s.close();
        subs.clear();
    }
}
