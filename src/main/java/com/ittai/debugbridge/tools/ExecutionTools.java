package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.EventBus;
import com.ittai.debugbridge.SuspendState;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class ExecutionTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            new ToolSpec(
                "continue_all",
                "Resume the entire VM. Requires at least one suspended thread.",
                object(props(), reqs()),
                args -> { bridge.vm().resume(); return Map.of("resumed", "all"); }
            ),
            new ToolSpec(
                "continue_thread",
                "Resume a specific suspended thread (others stay paused).",
                object(props("thread_id", str("Thread id")), reqs("thread_id")),
                args -> {
                    ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                    t.resume();
                    return Map.of("resumed", "thread", "thread_id", Args.string(args, "thread_id"));
                }
            ),
            new ToolSpec(
                "pause_all",
                "Suspend the entire VM immediately (no breakpoint required). Use sparingly — pauses GC and all threads.",
                object(props(), reqs()),
                args -> { bridge.vm().suspend(); return Map.of("paused", "all", "suspended_threads", SuspendState.suspendCount(bridge.vm())); }
            ),
            new ToolSpec(
                "step_over",
                "Step over the current line in the given thread. The thread must be suspended.",
                object(props("thread_id", str("Thread id")), reqs("thread_id")),
                args -> step(bridge, Args.string(args, "thread_id"), StepRequest.STEP_OVER)
            ),
            new ToolSpec(
                "step_into",
                "Step into the next call in the given thread.",
                object(props("thread_id", str("Thread id")), reqs("thread_id")),
                args -> step(bridge, Args.string(args, "thread_id"), StepRequest.STEP_INTO)
            ),
            new ToolSpec(
                "step_out",
                "Step out of the current method in the given thread.",
                object(props("thread_id", str("Thread id")), reqs("thread_id")),
                args -> step(bridge, Args.string(args, "thread_id"), StepRequest.STEP_OUT)
            ),
            new ToolSpec(
                "continue_and_wait",
                "Resume the VM and block until the next debug event arrives or the timeout elapses. "
                    + "Returns the event payload (location, thread, etc.) when one fires.",
                object(props("timeout_ms", integer("Timeout in milliseconds (default 10000)")), reqs()),
                args -> resumeAndWait(bridge, null, Args.longOpt(args, "timeout_ms", 10000))
            ),
            new ToolSpec(
                "step_over_and_wait",
                "Step over and block for the resulting step event.",
                object(props(
                    "thread_id", str("Thread id"),
                    "timeout_ms", integer("Timeout ms (default 10000)")
                ), reqs("thread_id")),
                args -> stepAndWait(bridge, Args.string(args, "thread_id"), StepRequest.STEP_OVER,
                    Args.longOpt(args, "timeout_ms", 10000))
            ),
            new ToolSpec(
                "step_into_and_wait",
                "Step into and block for the resulting step event.",
                object(props(
                    "thread_id", str("Thread id"),
                    "timeout_ms", integer("Timeout ms (default 10000)")
                ), reqs("thread_id")),
                args -> stepAndWait(bridge, Args.string(args, "thread_id"), StepRequest.STEP_INTO,
                    Args.longOpt(args, "timeout_ms", 10000))
            ),
            new ToolSpec(
                "step_out_and_wait",
                "Step out and block for the resulting step event.",
                object(props(
                    "thread_id", str("Thread id"),
                    "timeout_ms", integer("Timeout ms (default 10000)")
                ), reqs("thread_id")),
                args -> stepAndWait(bridge, Args.string(args, "thread_id"), StepRequest.STEP_OUT,
                    Args.longOpt(args, "timeout_ms", 10000))
            )
        );
    }

    private static Map<String, Object> step(Bridge bridge, String threadId, int depth) {
        ThreadReference t = bridge.ids().resolve(threadId, ThreadReference.class);
        SuspendState.requireSuspended(t);
        EventRequestManager erm = bridge.vm().eventRequestManager();
        for (StepRequest sr : erm.stepRequests()) {
            if (sr.thread().equals(t)) {
                sr.disable();
                erm.deleteEventRequest(sr);
            }
        }
        StepRequest req = erm.createStepRequest(t, StepRequest.STEP_LINE, depth);
        req.addCountFilter(1);
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        req.enable();
        t.resume();
        return Map.of("stepped", true, "thread_id", threadId, "depth", depth);
    }

    private static Map<String, Object> resumeAndWait(Bridge bridge, String threadId, long timeoutMs) throws InterruptedException {
        long afterSeq = bridge.events().currentSeq();
        if (threadId == null) {
            bridge.vm().resume();
        } else {
            ThreadReference t = bridge.ids().resolve(threadId, ThreadReference.class);
            t.resume();
        }
        EventBus.DebugEvent ev = bridge.events().waitAfter(afterSeq, timeoutMs);
        return wrapEvent(ev, timeoutMs);
    }

    private static Map<String, Object> stepAndWait(Bridge bridge, String threadId, int depth, long timeoutMs) throws InterruptedException {
        long afterSeq = bridge.events().currentSeq();
        step(bridge, threadId, depth);
        EventBus.DebugEvent ev = bridge.events().waitAfter(afterSeq, timeoutMs,
            e -> "step_completed".equals(e.kind()) && threadId.equals(e.threadId()));
        return wrapEvent(ev, timeoutMs);
    }

    static Map<String, Object> wrapEvent(EventBus.DebugEvent ev, long timeoutMs) {
        if (ev == null) {
            return Map.of("timeout", true, "waited_ms", timeoutMs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", ev.seq());
        out.put("ts_ms", ev.timestampMs());
        out.put("kind", ev.kind());
        out.put("thread_id", ev.threadId());
        out.putAll(ev.payload());
        return out;
    }

    private ExecutionTools() {}
}
