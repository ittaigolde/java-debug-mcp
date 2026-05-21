package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;
import com.ittai.debugbridge.Inspector;
import com.ittai.debugbridge.SuspendState;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class DiagnosticsTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            threadDumpTool(bridge),
            heapDumpTool(bridge),
            listInstancesTool(bridge),
            classHistogramTool(bridge)
        );
    }

    private static ToolSpec threadDumpTool(Bridge bridge) {
        return new ToolSpec(
            "thread_dump",
            "Capture all thread stack traces (jstack-style). If suspend_if_needed=true (default), "
                + "briefly vm.suspend() to make every thread's frames readable, then restore prior state. "
                + "Format 'json' (default) returns structured frames; 'jstack' returns a text dump.",
            object(
                props(
                    "format", str("'json' (default) or 'jstack'"),
                    "suspend_if_needed", bool("Briefly suspend the VM if nothing is paused (default true)"),
                    "include_system", bool("Include JVM internal threads like Finalizer (default true)")
                ),
                reqs()
            ),
            args -> {
                String format = Args.stringOpt(args, "format", "json");
                boolean suspendIfNeeded = Args.boolOpt(args, "suspend_if_needed", true);
                boolean includeSystem = Args.boolOpt(args, "include_system", true);
                VirtualMachine vm = bridge.vm();
                boolean weSuspended = false;
                if (suspendIfNeeded && !SuspendState.anySuspended(vm)) {
                    vm.suspend();
                    weSuspended = true;
                }
                try {
                    Inspector inspector = new Inspector(bridge.ids());
                    List<Map<String, Object>> dump = new ArrayList<>();
                    for (ThreadReference t : vm.allThreads()) {
                        if (!includeSystem && isSystemThread(t)) continue;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", bridge.ids().idFor("thr", String.valueOf(t.uniqueID()), t));
                        row.put("name", t.name());
                        row.put("status", threadStatusName(t.status()));
                        row.put("suspended", t.isSuspended());
                        row.put("suspend_count", t.suspendCount());
                        List<Map<String, Object>> frames = new ArrayList<>();
                        if (t.isSuspended()) {
                            try {
                                for (int i = 0; i < t.frameCount(); i++) {
                                    StackFrame f = t.frame(i);
                                    Map<String, Object> fm = new LinkedHashMap<>();
                                    fm.put("index", i);
                                    Location loc = f.location();
                                    fm.put("class", loc.declaringType().name());
                                    fm.put("method", loc.method().name());
                                    fm.put("signature", loc.method().signature());
                                    fm.put("line", loc.lineNumber());
                                    try { fm.put("source_name", loc.sourceName()); }
                                    catch (AbsentInformationException ignored) {}
                                    frames.add(fm);
                                }
                            } catch (Exception ex) {
                                row.put("frames_error", String.valueOf(ex));
                            }
                        }
                        row.put("frames", frames);
                        dump.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("thread_count", dump.size());
                    out.put("suspended_temporarily", weSuspended);
                    if ("jstack".equalsIgnoreCase(format)) {
                        out.put("text", renderJstack(dump));
                    } else {
                        out.put("threads", dump);
                    }
                    return out;
                } finally {
                    if (weSuspended) {
                        vm.resume();
                    }
                }
            }
        );
    }

    private static ToolSpec heapDumpTool(Bridge bridge) {
        return new ToolSpec(
            "heap_dump",
            "Write a .hprof heap dump to a path on the TARGET'S filesystem. Requires a thread_id "
                + "currently suspended by a breakpoint/step/exception event (JDI method invocation "
                + "constraint). The dump can be opened in Eclipse MAT, VisualVM, YourKit, etc. "
                + "live=true (default) walks only reachable objects after a full GC; false dumps all.",
            object(
                props(
                    "path", str("Output .hprof path on the target host (relative paths land in target's CWD)"),
                    "thread_id", str("A thread suspended at a breakpoint/step/exception event"),
                    "live", bool("Live objects only (default true) — runs full GC first")
                ),
                reqs("path", "thread_id")
            ),
            args -> {
                String path = Args.string(args, "path");
                boolean live = Args.boolOpt(args, "live", true);
                ThreadReference t = bridge.ids().resolve(Args.string(args, "thread_id"), ThreadReference.class);
                SuspendState.requireSuspended(t);
                VirtualMachine vm = bridge.vm();
                return invokeHeapDump(vm, t, path, live);
            }
        );
    }

    private static ToolSpec listInstancesTool(Bridge bridge) {
        return new ToolSpec(
            "list_instances",
            "Enumerate live instances of a class. Returns object_ids you can inspect_object on. "
                + "Max defaults to 100; pass 0 for unlimited (can be slow / large).",
            object(
                props(
                    "class", str("Fully-qualified class name"),
                    "max", integer("Max instances to return (default 100; 0 = unlimited)")
                ),
                reqs("class")
            ),
            args -> {
                String fqn = Args.string(args, "class");
                int max = Args.integerOpt(args, "max", 100);
                VirtualMachine vm = bridge.vm();
                List<ReferenceType> classes = vm.classesByName(fqn);
                if (classes.isEmpty()) {
                    throw new IllegalArgumentException("class not loaded: " + fqn);
                }
                Inspector inspector = new Inspector(bridge.ids());
                ReferenceType rt = classes.get(0);
                List<ObjectReference> instances = rt.instances(max);
                List<Map<String, Object>> rows = new ArrayList<>(instances.size());
                for (ObjectReference o : instances) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("object_id", bridge.ids().idFor("obj", String.valueOf(o.uniqueID()), o));
                    row.put("type", o.referenceType().name());
                    row.put("summary", summarize(o));
                    rows.add(row);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("class", rt.name());
                out.put("count", rows.size());
                out.put("capped", max > 0 && rows.size() == max);
                out.put("instances", rows);
                return out;
            }
        );
    }

    private static ToolSpec classHistogramTool(Bridge bridge) {
        return new ToolSpec(
            "class_histogram",
            "Class-by-instance-count histogram (jmap -histo style). Fast: single JDWP round-trip via "
                + "vm.instanceCounts. Filter by substring on FQN; sort by count desc; top N.",
            object(
                props(
                    "pattern", str("Optional substring filter on class FQN"),
                    "top_n", integer("Return at most N rows (default 30)"),
                    "min_instances", integer("Skip classes with fewer instances than this (default 1)")
                ),
                reqs()
            ),
            args -> {
                String pattern = Args.stringOpt(args, "pattern", null);
                int topN = Args.integerOpt(args, "top_n", 30);
                int minInstances = Args.integerOpt(args, "min_instances", 1);
                VirtualMachine vm = bridge.vm();
                List<ReferenceType> allClasses = vm.allClasses();
                List<ReferenceType> filtered = new ArrayList<>();
                for (ReferenceType rt : allClasses) {
                    if (pattern == null || rt.name().contains(pattern)) filtered.add(rt);
                }
                long[] counts = vm.instanceCounts(filtered);
                record Row(String name, long count) {}
                List<Row> rows = new ArrayList<>();
                for (int i = 0; i < filtered.size(); i++) {
                    if (counts[i] >= minInstances) {
                        rows.add(new Row(filtered.get(i).name(), counts[i]));
                    }
                }
                rows.sort(Comparator.comparingLong(Row::count).reversed());
                int n = Math.min(topN, rows.size());
                List<Map<String, Object>> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Row r = rows.get(i);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rank", i + 1);
                    m.put("class", r.name());
                    m.put("instances", r.count());
                    out.add(m);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("classes_scanned", filtered.size());
                result.put("classes_with_min_instances", rows.size());
                result.put("histogram", out);
                return result;
            }
        );
    }

    // --- internals ---------------------------------------------------

    private static Map<String, Object> invokeHeapDump(VirtualMachine vm, ThreadReference t,
                                                       String path, boolean live) throws Exception {
        ReferenceType mgmtFactoryRT = ensureClassLoaded(vm, t, "java.lang.management.ManagementFactory");
        ReferenceType mxBeanIfaceRT = ensureClassLoaded(vm, t, "com.sun.management.HotSpotDiagnosticMXBean");
        if (!(mgmtFactoryRT instanceof ClassType mgmtFactory)) {
            throw new IllegalStateException("ManagementFactory is not a ClassType");
        }
        Method getBean = findMethod(mgmtFactory, "getPlatformMXBean",
            "(Ljava/lang/Class;)Ljava/lang/management/PlatformManagedObject;");
        Value beanVal = mgmtFactory.invokeMethod(t, getBean,
            List.of(mxBeanIfaceRT.classObject()), ObjectReference.INVOKE_SINGLE_THREADED);
        if (!(beanVal instanceof ObjectReference bean)) {
            throw new IllegalStateException("getPlatformMXBean returned null/unexpected value");
        }
        Method dumpHeap = findMethod(bean.referenceType(), "dumpHeap",
            "(Ljava/lang/String;Z)V");
        bean.invokeMethod(t, dumpHeap,
            List.of(vm.mirrorOf(path), vm.mirrorOf(live)),
            ObjectReference.INVOKE_SINGLE_THREADED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("path", path);
        out.put("note", "Dump written on TARGET filesystem. Copy with scp/cp if target is remote.");
        return out;
    }

    private static ReferenceType ensureClassLoaded(VirtualMachine vm, ThreadReference t, String fqn)
        throws Exception {
        List<ReferenceType> loaded = vm.classesByName(fqn);
        if (!loaded.isEmpty()) return loaded.get(0);
        // Force-load by calling Class.forName(fqn) in the target.
        List<ReferenceType> classClasses = vm.classesByName("java.lang.Class");
        if (classClasses.isEmpty() || !(classClasses.get(0) instanceof ClassType classClass)) {
            throw new IllegalStateException("java.lang.Class not loaded — JVM in a strange state");
        }
        Method forName = findMethod(classClass, "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
        classClass.invokeMethod(t, forName, List.of(vm.mirrorOf(fqn)),
            ObjectReference.INVOKE_SINGLE_THREADED);
        loaded = vm.classesByName(fqn);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("class still not loaded after Class.forName: " + fqn);
        }
        return loaded.get(0);
    }

    private static Method findMethod(ReferenceType rt, String name, String signature) {
        List<Method> ms = rt.methodsByName(name, signature);
        if (ms.isEmpty()) throw new IllegalStateException("method not found: " + rt.name() + "#" + name + signature);
        return ms.get(0);
    }

    private static boolean isSystemThread(ThreadReference t) {
        String n = t.name();
        if (n == null) return false;
        return n.equals("Reference Handler") || n.equals("Finalizer") || n.equals("Signal Dispatcher")
            || n.equals("Attach Listener") || n.equals("Notification Thread") || n.equals("Common-Cleaner")
            || n.startsWith("GC ") || n.startsWith("Compiler") || n.equals("Service Thread");
    }

    private static String renderJstack(List<Map<String, Object>> threads) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> th : threads) {
            sb.append("\"").append(th.get("name")).append("\" #").append(th.get("id"));
            sb.append(" status=").append(th.get("status"));
            if (Boolean.TRUE.equals(th.get("suspended"))) sb.append(" SUSPENDED");
            sb.append("\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> frames = (List<Map<String, Object>>) th.get("frames");
            if (frames != null && !frames.isEmpty()) {
                for (Map<String, Object> f : frames) {
                    sb.append("    at ").append(f.get("class")).append(".").append(f.get("method"));
                    Object src = f.get("source_name");
                    if (src != null) sb.append("(").append(src).append(":").append(f.get("line")).append(")");
                    else sb.append("(line ").append(f.get("line")).append(")");
                    sb.append("\n");
                }
            } else if (th.get("frames_error") != null) {
                sb.append("    [frames error: ").append(th.get("frames_error")).append("]\n");
            } else {
                sb.append("    [no frames — thread not suspended]\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String summarize(ObjectReference o) {
        return o.referenceType().name() + "@" + Long.toHexString(o.uniqueID());
    }

    private static String threadStatusName(int s) {
        return switch (s) {
            case ThreadReference.THREAD_STATUS_UNKNOWN -> "unknown";
            case ThreadReference.THREAD_STATUS_ZOMBIE -> "zombie";
            case ThreadReference.THREAD_STATUS_RUNNING -> "running";
            case ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping";
            case ThreadReference.THREAD_STATUS_MONITOR -> "monitor";
            case ThreadReference.THREAD_STATUS_WAIT -> "wait";
            case ThreadReference.THREAD_STATUS_NOT_STARTED -> "not_started";
            default -> "status_" + s;
        };
    }

    private DiagnosticsTools() {}
}
