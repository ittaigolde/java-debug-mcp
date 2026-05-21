package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.Inspector;
import com.ittai.debugbridge.SuspendState;
import com.ittai.debugbridge.eval.Evaluator;
import com.ittai.debugbridge.eval.Expr;
import com.ittai.debugbridge.eval.Parser;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class EvalTools {

    private static final String SUPPORTED =
        "Supported: identifiers (local → this.field → static), field chains (a.b.c), "
            + "method calls obj.foo(args), static refs Foo.bar / com.example.Foo.baz(), "
            + "literals (int/long/double/float/string/char/true/false/null), "
            + "arithmetic + - * / %, comparisons == != < > <= >=, logical && || !, "
            + "parens, array index arr[i], 'this'. "
            + "NOT supported: lambdas, streams, ternary, assignment, new, casts, generics — "
            + "use invoke_method + inspect_object for those.";

    public static List<ToolSpec> all(Bridge bridge) {
        Inspector inspector = new Inspector(bridge.ids());

        return List.of(
            new ToolSpec(
                "evaluate",
                "Evaluate a small Java expression in a suspended frame's context. " + SUPPORTED,
                object(
                    props(
                        "expr", str("Java expression to evaluate"),
                        "thread_id", str("Suspended thread id"),
                        "frame_index", integer("0-based frame index (default 0 = top frame)")
                    ),
                    reqs("expr", "thread_id")
                ),
                args -> {
                    ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                    SuspendState.requireSuspended(t);
                    int frameIndex = Args.integerOpt(args, "frame_index", 0);
                    StackFrame frame = t.frame(frameIndex);
                    Expr expr;
                    try {
                        expr = Parser.parse(Args.string(args, "expr"));
                    } catch (IllegalArgumentException pe) {
                        return Map.of(
                            "status", "unsupported_syntax",
                            "error", pe.getMessage(),
                            "supported", SUPPORTED
                        );
                    }
                    Evaluator eval = new Evaluator(bridge.vm(), t, frame);
                    Value v = eval.evalToValue(expr);
                    return Map.of(
                        "status", "ok",
                        "value", inspector.valueToJson(v)
                    );
                }
            )
        );
    }

    private EvalTools() {}
}
