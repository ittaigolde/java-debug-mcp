package com.ittai.debugbridge.ui;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.EventBus;
import com.ittai.debugbridge.Inspector;
import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.AccessWatchpointRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StateView {

    private final Bridge bridge;
    private final Inspector inspector;
    private final SourceResolver sourceResolver;
    private final NoteLog noteLog;
    private final UserGate gate;

    public StateView(Bridge bridge, SourceResolver sourceResolver, NoteLog noteLog, UserGate gate) {
        this.bridge = bridge;
        this.sourceResolver = sourceResolver;
        this.noteLog = noteLog;
        this.gate = gate;
        this.inspector = new Inspector(bridge.ids());
    }

    public SourceResolver sourceResolver() {
        return sourceResolver;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", bridge.status());
        if (!isAttached()) {
            out.put("threads", List.of());
            out.put("breakpoints", List.of());
            out.put("recent_events", recentEvents());
            out.put("notes", recentNotes());
            out.put("sourcepath", sourceResolver.rootsAsStrings());
            return out;
        }
        out.put("threads", inspector.threads(bridge.vm()));
        out.put("breakpoints", breakpoints(bridge.vm()));
        out.put("recent_events", recentEvents());
        out.put("notes", recentNotes());
        out.put("sourcepath", sourceResolver.rootsAsStrings());
        out.put("gate", gate.state());
        return out;
    }

    private List<Map<String, Object>> recentNotes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (NoteLog.Note n : noteLog.snapshot(100)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", n.seq());
            row.put("ts_ms", n.timestampMs());
            row.put("kind", n.kind());
            row.put("text", n.text());
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> framesAndTopLocals(String threadId) throws Exception {
        if (!isAttached()) throw new IllegalStateException("not attached");
        ThreadReference t = bridge.ids().resolve(threadId, ThreadReference.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thread_id", threadId);
        out.put("thread_name", t.name());
        out.put("suspended", t.isSuspended());
        if (!t.isSuspended()) {
            out.put("frames", List.of());
            out.put("locals", null);
            out.put("note", "thread not suspended; inspection requires a paused thread");
            return out;
        }
        List<Map<String, Object>> frames = inspector.frames(t);
        out.put("frames", frames);
        if (!frames.isEmpty()) {
            out.put("locals", inspector.locals(t, 0));
        }
        return out;
    }

    public Map<String, Object> source(String classFqn, String sourceName, int line) {
        SourceResolver.Result r = sourceResolver.resolve(classFqn, sourceName, line);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", r.available());
        out.put("path", r.path());
        out.put("hit_line", r.hitLine());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SourceResolver.Line ln : r.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", ln.number());
            row.put("text", ln.text());
            lines.add(row);
        }
        out.put("lines", lines);
        if (r.message() != null) out.put("message", r.message());
        return out;
    }

    private boolean isAttached() {
        try {
            bridge.vm();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private List<Map<String, Object>> recentEvents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EventBus.DebugEvent ev : bridge.events().snapshot(50)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ev.seq());
            row.put("ts_ms", ev.timestampMs());
            row.put("kind", ev.kind());
            row.put("thread_id", ev.threadId());
            row.putAll(ev.payload());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> breakpoints(VirtualMachine vm) {
        EventRequestManager erm = vm.eventRequestManager();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BreakpointRequest req : erm.breakpointRequests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", req.getProperty("debug_bridge_id"));
            row.put("kind", "line");
            Location loc = req.location();
            row.put("class", loc.declaringType().name());
            row.put("line", loc.lineNumber());
            try { row.put("source_name", loc.sourceName()); } catch (Exception ignored) {}
            row.put("enabled", req.isEnabled());
            out.add(row);
        }
        for (ExceptionRequest req : erm.exceptionRequests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", req.getProperty("debug_bridge_id"));
            row.put("kind", "exception");
            row.put("enabled", req.isEnabled());
            out.add(row);
        }
        for (ModificationWatchpointRequest req : erm.modificationWatchpointRequests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", req.getProperty("debug_bridge_id"));
            row.put("kind", "watchpoint_modify");
            row.put("field", req.field().declaringType().name() + "." + req.field().name());
            row.put("enabled", req.isEnabled());
            out.add(row);
        }
        for (AccessWatchpointRequest req : erm.accessWatchpointRequests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", req.getProperty("debug_bridge_id"));
            row.put("kind", "watchpoint_access");
            row.put("field", req.field().declaringType().name() + "." + req.field().name());
            row.put("enabled", req.isEnabled());
            out.add(row);
        }
        return out;
    }
}
