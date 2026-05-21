package com.ittai.debugbridge;

import com.ittai.debugbridge.ui.UiBroadcaster;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

public final class Bridge {

    private final Ids ids;
    private final EventBus events;
    private final Session session;
    private final UiBroadcaster broadcaster;

    private volatile VirtualMachine vm;
    private volatile EventPump pump;
    private volatile Instant attachedAt;
    private volatile String attachedHost;
    private volatile int attachedPort;

    public Bridge(Ids ids, EventBus events, Session session, UiBroadcaster broadcaster) {
        this.ids = ids;
        this.events = events;
        this.session = session;
        this.broadcaster = broadcaster;
    }

    public synchronized Map<String, Object> attach(String host, int port) throws IOException {
        if (vm != null) {
            throw new IllegalStateException("already attached to " + attachedHost + ":" + attachedPort);
        }
        AttachingConnector conn = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> "com.sun.jdi.SocketAttach".equals(c.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("SocketAttach connector not found"));
        Map<String, Connector.Argument> args = conn.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));
        try {
            this.vm = conn.attach(args);
        } catch (com.sun.jdi.connect.IllegalConnectorArgumentsException e) {
            throw new IOException("attach failed: " + e.getMessage(), e);
        }
        this.attachedAt = Instant.now();
        this.attachedHost = host;
        this.attachedPort = port;

        this.pump = new EventPump(vm, ids, events, session, broadcaster);
        this.pump.start();

        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("vm_name", vm.name());
        info.put("jvm_version", vm.version());
        info.put("description", vm.description());
        info.put("attached_at", attachedAt.toString());
        info.put("host", host);
        info.put("port", port);

        session.setHeader("vm", vm.name() + " — " + vm.version());
        session.setHeader("attached", host + ":" + port + " at " + attachedAt);
        return info;
    }

    public synchronized Map<String, Object> detach() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (vm == null) {
            result.put("attached", false);
            return result;
        }
        try {
            if (pump != null) {
                pump.stopPump();
            }
            vm.dispose();
        } catch (Exception ignored) {
            // disposing a dead VM throws; safe to ignore
        }
        vm = null;
        pump = null;
        ids.clear();
        events.clear();
        result.put("attached", false);
        result.put("detached_at", Instant.now().toString());
        session.setHeader("detached", Instant.now().toString());
        return result;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("attached", vm != null);
        if (vm != null) {
            s.put("vm_name", vm.name());
            s.put("jvm_version", vm.version());
            s.put("host", attachedHost);
            s.put("port", attachedPort);
            s.put("attached_at", attachedAt.toString());
            s.put("suspended_thread_count", SuspendState.suspendCount(vm));
            s.put("event_backlog", events.size());
        }
        return s;
    }

    public VirtualMachine vm() {
        VirtualMachine v = this.vm;
        if (v == null) {
            throw new IllegalStateException("not attached — call attach(host, port) first");
        }
        return v;
    }

    public Ids ids() { return ids; }
    public EventBus events() { return events; }
    public Session session() { return session; }
    public UiBroadcaster broadcaster() { return broadcaster; }
}
