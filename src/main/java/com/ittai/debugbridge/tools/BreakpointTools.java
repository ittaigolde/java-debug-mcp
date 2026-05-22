package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.sun.jdi.Field;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.AccessWatchpointRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class BreakpointTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            new ToolSpec(
                "set_breakpoint",
                "Set a line breakpoint. suspend_policy: 'event_thread' (default) pauses only the hitting "
                    + "thread; 'all' pauses the entire VM. If the class is not yet loaded, the breakpoint "
                    + "is deferred until ClassPrepare fires.",
                object(
                    props(
                        "class", str("Fully-qualified class name (e.g. com.example.Foo)"),
                        "line", integer("Line number in the source file"),
                        "suspend_policy", str("'event_thread' (default) or 'all'")
                    ),
                    reqs("class", "line")
                ),
                args -> setLineBreakpoint(bridge, Args.string(args, "class"),
                    Args.integer(args, "line"),
                    Args.stringOpt(args, "suspend_policy", "event_thread"))
            ),

            new ToolSpec(
                "set_exception_breakpoint",
                "Pause when a Java exception is thrown. Pass class FQN; "
                    + "set caught/uncaught to filter.",
                object(
                    props(
                        "class", str("Exception class FQN (e.g. java.lang.RuntimeException). "
                            + "Use 'java.lang.Throwable' to catch everything."),
                        "caught", bool("Pause on caught exceptions (default true)"),
                        "uncaught", bool("Pause on uncaught exceptions (default true)"),
                        "suspend_policy", str("'event_thread' (default) or 'all'")
                    ),
                    reqs("class")
                ),
                args -> setExceptionBreakpoint(bridge,
                    Args.string(args, "class"),
                    Args.boolOpt(args, "caught", true),
                    Args.boolOpt(args, "uncaught", true),
                    Args.stringOpt(args, "suspend_policy", "event_thread"))
            ),

            new ToolSpec(
                "set_watchpoint",
                "Watch a field for read and/or modification. The class must be loaded.",
                object(
                    props(
                        "class", str("Class FQN that owns the field"),
                        "field", str("Field name"),
                        "on_read", bool("Trigger on read access (default false)"),
                        "on_modify", bool("Trigger on modification (default true)"),
                        "suspend_policy", str("'event_thread' (default) or 'all'")
                    ),
                    reqs("class", "field")
                ),
                args -> setWatchpoint(bridge,
                    Args.string(args, "class"),
                    Args.string(args, "field"),
                    Args.boolOpt(args, "on_read", false),
                    Args.boolOpt(args, "on_modify", true),
                    Args.stringOpt(args, "suspend_policy", "event_thread"))
            ),

            new ToolSpec(
                "list_breakpoints",
                "List all active breakpoints, exception breakpoints, and watchpoints.",
                object(props(), reqs()),
                args -> listBreakpoints(bridge)
            ),

            new ToolSpec(
                "remove_breakpoint",
                "Remove a breakpoint, exception breakpoint, or watchpoint by its id.",
                object(props("id", str("Id from set_breakpoint/set_exception_breakpoint/set_watchpoint")), reqs("id")),
                args -> removeBreakpoint(bridge, Args.string(args, "id"))
            )
        );
    }

    private static Map<String, Object> setLineBreakpoint(
        Bridge bridge, String classFqn, int line, String suspendPolicy
    ) throws Exception {
        EventRequestManager erm = bridge.vm().eventRequestManager();
        List<ReferenceType> loaded = bridge.vm().classesByName(classFqn);
        if (!loaded.isEmpty()) {
            ReferenceType rt = loaded.get(0);
            List<Location> locs = rt.locationsOfLine(line);
            if (locs.isEmpty()) {
                throw new IllegalArgumentException("no executable location at " + classFqn + ":" + line);
            }
            BreakpointRequest req = erm.createBreakpointRequest(locs.get(0));
            req.setSuspendPolicy(policyConstant(suspendPolicy));
            String id = bridge.ids().idFor("bp", String.valueOf(System.identityHashCode(req)), req);
            req.putProperty("debug_bridge_id", id);
            req.enable();
            return Map.of(
                "id", id,
                "kind", "line",
                "class", classFqn,
                "line", line,
                "suspend_policy", suspendPolicy,
                "status", "active"
            );
        }
        // Deferred: install a class-prepare request that resolves the breakpoint when the class loads.
        // SUSPEND_EVENT_THREAD (not SUSPEND_NONE) is critical: it pauses the loading thread so the
        // event pump can install the BreakpointRequest before the first method call runs.
        // EventPump.resolveDeferredBreakpoints resumes the thread after install.
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter(classFqn);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        String pendingId = bridge.ids().idFor("bp", "pending_" + classFqn + ":" + line, cpr);
        cpr.putProperty("debug_bridge_pending", Map.of("class", classFqn, "line", line, "policy", suspendPolicy, "id", pendingId));
        cpr.enable();
        return Map.of(
            "id", pendingId,
            "kind", "line",
            "class", classFqn,
            "line", line,
            "suspend_policy", suspendPolicy,
            "status", "deferred — class not yet loaded; will install on class prepare"
        );
    }

    private static Map<String, Object> setExceptionBreakpoint(
        Bridge bridge, String classFqn, boolean caught, boolean uncaught, String suspendPolicy
    ) {
        EventRequestManager erm = bridge.vm().eventRequestManager();
        List<ReferenceType> loaded = bridge.vm().classesByName(classFqn);
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException(
                "exception class not yet loaded: " + classFqn
                    + " — load it in the target first, or use 'java.lang.Throwable'");
        }
        ExceptionRequest req = erm.createExceptionRequest(loaded.get(0), caught, uncaught);
        req.setSuspendPolicy(policyConstant(suspendPolicy));
        String id = bridge.ids().idFor("bp", "ex_" + classFqn + "_" + System.identityHashCode(req), req);
        req.putProperty("debug_bridge_id", id);
        req.enable();
        return Map.of(
            "id", id,
            "kind", "exception",
            "class", classFqn,
            "caught", caught,
            "uncaught", uncaught,
            "suspend_policy", suspendPolicy
        );
    }

    private static Map<String, Object> setWatchpoint(
        Bridge bridge, String classFqn, String fieldName, boolean onRead, boolean onModify, String suspendPolicy
    ) {
        EventRequestManager erm = bridge.vm().eventRequestManager();
        List<ReferenceType> loaded = bridge.vm().classesByName(classFqn);
        if (loaded.isEmpty()) throw new IllegalArgumentException("class not loaded: " + classFqn);
        ReferenceType rt = loaded.get(0);
        Field f = rt.fieldByName(fieldName);
        if (f == null) throw new IllegalArgumentException("no field " + fieldName + " on " + classFqn);

        List<String> watches = new ArrayList<>();
        List<EventRequest> reqs = new ArrayList<>();
        if (onModify) {
            ModificationWatchpointRequest mw = erm.createModificationWatchpointRequest(f);
            mw.setSuspendPolicy(policyConstant(suspendPolicy));
            mw.enable();
            reqs.add(mw);
            watches.add("modify");
        }
        if (onRead) {
            AccessWatchpointRequest aw = erm.createAccessWatchpointRequest(f);
            aw.setSuspendPolicy(policyConstant(suspendPolicy));
            aw.enable();
            reqs.add(aw);
            watches.add("read");
        }
        if (reqs.isEmpty()) throw new IllegalArgumentException("on_read and on_modify both false — nothing to do");
        String id = bridge.ids().idFor("bp", "wp_" + classFqn + "." + fieldName + "_" + System.identityHashCode(reqs.get(0)), reqs);
        for (EventRequest r : reqs) r.putProperty("debug_bridge_id", id);
        return Map.of(
            "id", id,
            "kind", "watchpoint",
            "class", classFqn,
            "field", fieldName,
            "watches", watches,
            "suspend_policy", suspendPolicy
        );
    }

    private static Map<String, Object> listBreakpoints(Bridge bridge) {
        EventRequestManager erm = bridge.vm().eventRequestManager();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BreakpointRequest req : erm.breakpointRequests()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", req.getProperty("debug_bridge_id"));
            row.put("kind", "line");
            Location loc = req.location();
            row.put("class", loc.declaringType().name());
            row.put("line", loc.lineNumber());
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
        for (ClassPrepareRequest req : erm.classPrepareRequests()) {
            Object pending = req.getProperty("debug_bridge_pending");
            if (!(pending instanceof Map<?, ?> info)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", info.get("id"));
            row.put("kind", "line");
            row.put("class", info.get("class"));
            row.put("line", info.get("line"));
            row.put("suspend_policy", info.get("policy"));
            row.put("enabled", req.isEnabled());
            row.put("status", "deferred");
            out.add(row);
        }
        return Map.of("breakpoints", out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> removeBreakpoint(Bridge bridge, String id) {
        Object ref = bridge.ids().resolveAny(id);
        EventRequestManager erm = bridge.vm().eventRequestManager();
        if (ref instanceof EventRequest req) {
            req.disable();
            erm.deleteEventRequest(req);
            bridge.ids().remove(id);
            return Map.of("removed", id, "kind", req.getClass().getSimpleName());
        }
        if (ref instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof EventRequest req) {
                    req.disable();
                    erm.deleteEventRequest(req);
                }
            }
            bridge.ids().remove(id);
            return Map.of("removed", id, "kind", "watchpoint_pair");
        }
        throw new IllegalArgumentException("id " + id + " does not refer to a breakpoint");
    }

    static int policyConstant(String name) {
        if (name == null) return EventRequest.SUSPEND_EVENT_THREAD;
        return switch (name) {
            case "none" -> EventRequest.SUSPEND_NONE;
            case "all" -> EventRequest.SUSPEND_ALL;
            case "event_thread" -> EventRequest.SUSPEND_EVENT_THREAD;
            default -> throw new IllegalArgumentException(
                "unknown suspend_policy: " + name + " (use 'event_thread' or 'all')");
        };
    }

    private BreakpointTools() {}
}
