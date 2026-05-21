package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.Inspector;
import com.ittai.debugbridge.SuspendState;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class InspectionTools {

    public static List<ToolSpec> all(Bridge bridge) {
        Inspector inspector = new Inspector(bridge.ids());

        return List.of(
            new ToolSpec(
                "list_threads",
                "List all threads in the target VM with their status and suspend state.",
                object(props(), reqs()),
                args -> Map.of("threads", inspector.threads(bridge.vm()))
            ),

            new ToolSpec(
                "list_classes",
                "List loaded classes. Optional 'pattern' is a substring match on FQN. "
                    + "Heavy — use a pattern for narrowing.",
                object(
                    props("pattern", str("Substring to filter by; omit for all classes")),
                    reqs()
                ),
                args -> {
                    String pattern = Args.stringOpt(args, "pattern", null);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (ReferenceType rt : bridge.vm().allClasses()) {
                        String name = rt.name();
                        if (pattern != null && !name.contains(pattern)) continue;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", name);
                        out.add(row);
                    }
                    return Map.of("classes", out, "count", out.size());
                }
            ),

            new ToolSpec(
                "class_info",
                "Get fields, methods (with signatures), and source name for a class FQN.",
                object(
                    props("class", str("Fully-qualified class name (e.g. java.lang.String)")),
                    reqs("class")
                ),
                args -> {
                    String fqn = Args.string(args, "class");
                    List<ReferenceType> ts = bridge.vm().classesByName(fqn);
                    if (ts.isEmpty()) throw new IllegalArgumentException("class not loaded: " + fqn);
                    ReferenceType rt = ts.get(0);
                    List<Map<String, Object>> fields = new ArrayList<>();
                    for (Field f : rt.fields()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", f.name());
                        row.put("type", f.typeName());
                        row.put("static", f.isStatic());
                        fields.add(row);
                    }
                    List<Map<String, Object>> methods = new ArrayList<>();
                    for (Method m : rt.methods()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", m.name());
                        row.put("signature", m.signature());
                        row.put("static", m.isStatic());
                        row.put("return_type", m.returnTypeName());
                        methods.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("name", rt.name());
                    try { out.put("source_name", rt.sourceName()); } catch (Exception ignored) {}
                    out.put("fields", fields);
                    out.put("methods", methods);
                    return out;
                }
            ),

            new ToolSpec(
                "frames",
                "List stack frames of a suspended thread. Returns frame index, location, this_id.",
                object(props("thread_id", str("Thread id from list_threads")), reqs("thread_id")),
                args -> {
                    ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                    return Map.of("frames", inspector.frames(t));
                }
            ),

            new ToolSpec(
                "locals",
                "Read locals + 'this' of a specific stack frame. Thread must be suspended.",
                object(
                    props(
                        "thread_id", str("Thread id"),
                        "frame_index", integer("0-based frame index from frames()")
                    ),
                    reqs("thread_id", "frame_index")
                ),
                args -> {
                    ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                    return inspector.locals(t, Args.integer(args, "frame_index"));
                }
            ),

            new ToolSpec(
                "inspect_object",
                "Read object fields. Optional max_depth bounds recursion (default 1). "
                    + "Object references are returned as object_ids that can be passed to other tools.",
                object(
                    props(
                        "object_id", str("Object id (obj_N) from a previous tool"),
                        "max_depth", integer("Recursion depth (default 1)")
                    ),
                    reqs("object_id")
                ),
                args -> {
                    ObjectReference obj = bridge.ids().resolve(Args.string(args, "object_id"), ObjectReference.class);
                    return inspector.inspectObject(obj, Args.integerOpt(args, "max_depth", 1));
                }
            ),

            new ToolSpec(
                "read_array",
                "Read a slice of an array. start defaults to 0, count to 100.",
                object(
                    props(
                        "array_id", str("Array object id"),
                        "start", integer("Start index (default 0)"),
                        "count", integer("Element count (default 100)")
                    ),
                    reqs("array_id")
                ),
                args -> {
                    ArrayReference ar = bridge.ids().resolve(Args.string(args, "array_id"), ArrayReference.class);
                    return inspector.readArray(ar, Args.integerOpt(args, "start", 0), Args.integerOpt(args, "count", 100));
                }
            )
        );
    }

    private InspectionTools() {}
}
