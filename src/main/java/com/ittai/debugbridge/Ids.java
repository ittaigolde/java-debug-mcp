package com.ittai.debugbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class Ids {

    private final Map<String, AtomicLong> counters = new HashMap<>();
    private final Map<String, String> keyToId = new HashMap<>();
    private final Map<String, Object> idToRef = new HashMap<>();

    public synchronized String idFor(String category, String key, Object ref) {
        String compositeKey = category + ":" + key;
        String existing = keyToId.get(compositeKey);
        if (existing != null) {
            return existing;
        }
        long n = counters.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
        String id = category + "_" + n;
        keyToId.put(compositeKey, id);
        idToRef.put(id, ref);
        return id;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T resolve(String id, Class<T> type) {
        Object ref = idToRef.get(id);
        if (ref == null) {
            throw new IllegalArgumentException("Unknown id: " + id);
        }
        if (!type.isInstance(ref)) {
            throw new IllegalArgumentException(
                "Id " + id + " resolves to " + ref.getClass().getName()
                    + ", not " + type.getName());
        }
        return (T) ref;
    }

    public synchronized Object resolveAny(String id) {
        Object ref = idToRef.get(id);
        if (ref == null) {
            throw new IllegalArgumentException("Unknown id: " + id);
        }
        return ref;
    }

    public synchronized boolean has(String id) {
        return idToRef.containsKey(id);
    }

    public synchronized void rebind(String id, Object newRef) {
        if (!idToRef.containsKey(id)) {
            throw new IllegalArgumentException("cannot rebind unknown id: " + id);
        }
        idToRef.put(id, newRef);
    }

    public synchronized void remove(String id) {
        Object removed = idToRef.remove(id);
        if (removed == null) return;
        keyToId.entrySet().removeIf(e -> e.getValue().equals(id));
    }

    public synchronized Map<String, Object> snapshot() {
        return new HashMap<>(idToRef);
    }

    public synchronized void clear() {
        counters.clear();
        keyToId.clear();
        idToRef.clear();
    }
}
