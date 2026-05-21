package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class EventTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            new ToolSpec(
                "wait_for_event",
                "Block until the next debug event arrives (breakpoint hit, step, exception, watchpoint) "
                    + "or the timeout elapses. Returns the event payload or { timeout: true }.",
                object(props("timeout_ms", integer("Timeout in ms (default 10000)")), reqs()),
                args -> {
                    long timeout = Args.longOpt(args, "timeout_ms", 10000);
                    EventBus.DebugEvent ev = bridge.events().poll(timeout);
                    return ExecutionTools.wrapEvent(ev, timeout);
                }
            ),
            new ToolSpec(
                "list_recent_events",
                "Non-blocking snapshot of the most recent N buffered events.",
                object(props("limit", integer("Number of events (default 20)")), reqs()),
                args -> {
                    int limit = Args.integerOpt(args, "limit", 20);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (EventBus.DebugEvent ev : bridge.events().snapshot(limit)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("seq", ev.seq());
                        row.put("ts_ms", ev.timestampMs());
                        row.put("kind", ev.kind());
                        row.put("thread_id", ev.threadId());
                        row.putAll(ev.payload());
                        out.add(row);
                    }
                    return Map.of("events", out, "count", out.size(),
                        "backlog_size", bridge.events().size(),
                        "capacity", bridge.events().capacity());
                }
            )
        );
    }

    private EventTools() {}
}
