package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.Inspector;
import com.ittai.debugbridge.Invoker;
import com.sun.jdi.ThreadReference;

import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class InvokeTools {

    public static List<ToolSpec> all(Bridge bridge) {
        Inspector inspector = new Inspector(bridge.ids());
        Invoker invoker = new Invoker(bridge.ids(), inspector);

        return List.of(
            new ToolSpec(
                "invoke_method",
                "Call a method on an object (instance) or class (static) in the paused target. "
                    + "Side effects in the target are real. The target thread must be suspended at a breakpoint, "
                    + "step, or exception event.",
                object(
                    props(
                        "target", str("object_id (instance invocation) OR class FQN (static invocation)"),
                        "method", str("Method name"),
                        "signature", str("JNI signature, e.g. (Ljava/lang/String;)I — omit only if name+arity is unambiguous"),
                        "args", arr(any(null), "Argument values: primitives inline, objects as object_ids"),
                        "thread_id", str("Thread id of a suspended thread (for execution context)"),
                        "timeout_ms", integer("Timeout in ms (default 5000)")
                    ),
                    reqs("target", "method", "thread_id")
                ),
                args -> {
                    ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                    return invoker.invoke(
                        bridge.vm(),
                        Args.string(args, "target"),
                        Args.string(args, "method"),
                        Args.stringOpt(args, "signature", null),
                        Args.listOpt(args, "args"),
                        t,
                        Args.longOpt(args, "timeout_ms", 5000)
                    );
                }
            )
        );
    }

    private InvokeTools() {}
}
