package com.ittai.debugbridge.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class UserGate {

    private final UiBroadcaster broadcaster;
    private boolean buffered = false;
    private CompletableFuture<Boolean> pending = null;

    public UserGate(UiBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public boolean await(long timeoutMs) {
        CompletableFuture<Boolean> f;
        synchronized (this) {
            if (buffered) {
                buffered = false;
                broadcastState();
                return true;
            }
            pending = new CompletableFuture<>();
            f = pending;
        }
        broadcastState();
        boolean ready;
        try {
            ready = f.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            ready = false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ready = false;
        } catch (ExecutionException ee) {
            ready = false;
        }
        synchronized (this) {
            if (pending == f) pending = null;
        }
        broadcastState();
        return ready;
    }

    public void signalReady() {
        CompletableFuture<Boolean> toComplete = null;
        synchronized (this) {
            if (pending != null) {
                toComplete = pending;
            } else {
                buffered = true;
            }
        }
        if (toComplete != null) {
            toComplete.complete(true);
        } else {
            broadcastState();
        }
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("armed", pending != null);
        m.put("buffered", buffered);
        return m;
    }

    private void broadcastState() {
        if (broadcaster.hasSubscribers()) {
            broadcaster.publish("gate_state", state());
        }
    }
}
