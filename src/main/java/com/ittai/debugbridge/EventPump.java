package com.ittai.debugbridge;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.WatchpointEvent;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.ittai.debugbridge.ui.UiBroadcaster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EventPump extends Thread {

    private final VirtualMachine vm;
    private final Ids ids;
    private final EventBus bus;
    private final Session session;
    private final UiBroadcaster broadcaster;
    private volatile boolean running = true;

    public EventPump(VirtualMachine vm, Ids ids, EventBus bus, Session session, UiBroadcaster broadcaster) {
        super("debug-bridge-event-pump");
        setDaemon(true);
        this.vm = vm;
        this.ids = ids;
        this.bus = bus;
        this.session = session;
        this.broadcaster = broadcaster;
    }

    public void stopPump() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        try {
            while (running) {
                EventSet set;
                try {
                    set = vm.eventQueue().remove();
                } catch (InterruptedException ie) {
                    if (!running) return;
                    continue;
                } catch (VMDisconnectedException vd) {
                    return;
                }

                for (Event ev : set) {
                    handle(ev, set);
                    if (ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent) {
                        running = false;
                    }
                }
                // Critical: we do NOT auto-resume here. Claude drives resume via tool calls.
                // Exception: events whose request was created with SUSPEND_NONE don't actually
                // suspend, so resume() is a no-op for them. We still don't call it.
            }
        } catch (Throwable t) {
            session.record("pump_error", Map.of("error", String.valueOf(t)));
        }
    }

    private void handle(Event ev, EventSet set) {
        String kind;
        Map<String, Object> payload = new LinkedHashMap<>();
        ThreadReference t = (ev instanceof LocatableEvent le) ? le.thread() : null;
        String threadId = t == null ? null : ids.idFor("thr", String.valueOf(t.uniqueID()), t);

        switch (ev) {
            case BreakpointEvent be -> {
                kind = "breakpoint_hit";
                addLocation(payload, be.location());
                Object bpId = ev.request().getProperty("debug_bridge_id");
                if (bpId != null) payload.put("breakpoint_id", bpId);
            }
            case StepEvent se -> {
                kind = "step_completed";
                addLocation(payload, se.location());
                // Step requests are one-shot — disable so subsequent steps create fresh requests.
                se.request().disable();
            }
            case ExceptionEvent xe -> {
                kind = "exception";
                addLocation(payload, xe.location());
                payload.put("exception_type", xe.exception().referenceType().name());
                if (xe.catchLocation() != null) {
                    payload.put("caught_at", locStr(xe.catchLocation()));
                } else {
                    payload.put("caught_at", null);
                }
            }
            case ModificationWatchpointEvent mwe -> {
                kind = "watchpoint_modify";
                addLocation(payload, mwe.location());
                payload.put("field", mwe.field().declaringType().name() + "." + mwe.field().name());
                payload.put("new_value", String.valueOf(mwe.valueToBe()));
            }
            case WatchpointEvent we -> {
                kind = "watchpoint_access";
                addLocation(payload, we.location());
                payload.put("field", we.field().declaringType().name() + "." + we.field().name());
            }
            case ClassPrepareEvent cpe -> {
                resolveDeferredBreakpoints(cpe);
                session.record("event", Map.of(
                    "event_kind", "class_prepare",
                    "class", cpe.referenceType().name()
                ));
                // Class-prepare requests for deferred breakpoints use SUSPEND_EVENT_THREAD so we
                // can install the BreakpointRequest before the method runs. Now that we've installed
                // (or no-op'd if no pending), release the loading thread.
                try {
                    cpe.thread().resume();
                } catch (Exception ignored) {}
                return;
            }
            case ThreadStartEvent tse -> {
                session.record("event", Map.of(
                    "event_kind", "thread_start",
                    "thread", tse.thread().name()
                ));
                return;
            }
            case ThreadDeathEvent tde -> {
                session.record("event", Map.of(
                    "event_kind", "thread_death",
                    "thread", tde.thread().name()
                ));
                return;
            }
            case VMDeathEvent vde -> { kind = "vm_death"; }
            case VMDisconnectEvent vdc -> { kind = "vm_disconnect"; }
            default -> {
                kind = "other";
                payload.put("class", ev.getClass().getSimpleName());
            }
        }

        if (threadId != null) payload.put("thread_id", threadId);
        if (t != null) payload.put("thread_name", t.name());
        payload.put("suspend_policy", policyName(ev.request()));

        bus.publish(kind, threadId, payload);

        Map<String, Object> rec = new LinkedHashMap<>(payload);
        rec.put("event_kind", kind);
        session.record("event", rec);

        if (broadcaster != null) {
            Map<String, Object> uiPayload = new LinkedHashMap<>(payload);
            uiPayload.put("kind", kind);
            uiPayload.put("ts_ms", System.currentTimeMillis());
            broadcaster.publish("debug_event", uiPayload);
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveDeferredBreakpoints(ClassPrepareEvent cpe) {
        Object pending = cpe.request().getProperty("debug_bridge_pending");
        if (!(pending instanceof Map<?, ?> info)) return;
        ReferenceType rt = cpe.referenceType();
        String classFqn = (String) info.get("class");
        if (!rt.name().equals(classFqn)) return;
        int line = (int) info.get("line");
        String policy = (String) info.get("policy");
        String id = (String) info.get("id");
        try {
            List<Location> locs = rt.locationsOfLine(line);
            if (locs.isEmpty()) {
                session.record("event", Map.of(
                    "event_kind", "deferred_breakpoint_failed",
                    "id", id,
                    "class", classFqn,
                    "line", line,
                    "reason", "no executable location at line"
                ));
                return;
            }
            EventRequestManager erm = vm.eventRequestManager();
            BreakpointRequest req = erm.createBreakpointRequest(locs.get(0));
            req.setSuspendPolicy(switch (policy) {
                case "all" -> EventRequest.SUSPEND_ALL;
                case "none" -> EventRequest.SUSPEND_NONE;
                default -> EventRequest.SUSPEND_EVENT_THREAD;
            });
            req.putProperty("debug_bridge_id", id);
            req.enable();
            // Rebind the original id (which was pointing to the ClassPrepareRequest we're about to
            // delete) to the live BreakpointRequest, so list_breakpoints / remove_breakpoint work.
            ids.rebind(id, req);
            cpe.request().disable();
            erm.deleteEventRequest(cpe.request());
            session.record("event", Map.of(
                "event_kind", "deferred_breakpoint_installed",
                "id", id,
                "class", classFqn,
                "line", line
            ));
        } catch (Exception ex) {
            session.record("event", Map.of(
                "event_kind", "deferred_breakpoint_failed",
                "id", id,
                "class", classFqn,
                "line", line,
                "error", String.valueOf(ex)
            ));
        }
    }

    private void addLocation(Map<String, Object> payload, Location loc) {
        if (loc == null) return;
        payload.put("location", locStr(loc));
        payload.put("class", loc.declaringType().name());
        try {
            payload.put("method", methodSig(loc.method()));
        } catch (Exception ignored) {}
        payload.put("line", loc.lineNumber());
        try {
            payload.put("source_name", loc.sourceName());
        } catch (Exception ignored) {}
    }

    private static String locStr(Location loc) {
        try {
            return loc.declaringType().name() + "#" + loc.method().name() + ":" + loc.lineNumber();
        } catch (Exception e) {
            return String.valueOf(loc);
        }
    }

    private static String methodSig(Method m) {
        return m.declaringType().name() + "#" + m.name() + m.signature();
    }

    private static String policyName(EventRequest req) {
        if (req == null) return "unknown";
        return switch (req.suspendPolicy()) {
            case EventRequest.SUSPEND_NONE -> "none";
            case EventRequest.SUSPEND_EVENT_THREAD -> "event_thread";
            case EventRequest.SUSPEND_ALL -> "all";
            default -> "unknown";
        };
    }
}
