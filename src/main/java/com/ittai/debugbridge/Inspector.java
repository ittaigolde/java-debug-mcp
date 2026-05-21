package com.ittai.debugbridge;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Inspector {

    private static final int OBJECT_SUMMARY_STRING_MAX = 80;

    private final Ids ids;

    public Inspector(Ids ids) {
        this.ids = ids;
    }

    public List<Map<String, Object>> threads(VirtualMachine vm) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThreadReference t : vm.allThreads()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ids.idFor("thr", String.valueOf(t.uniqueID()), t));
            row.put("name", t.name());
            row.put("status", threadStatus(t.status()));
            row.put("suspended", t.isSuspended());
            row.put("suspend_count", t.suspendCount());
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> frames(ThreadReference t) throws Exception {
        SuspendState.requireSuspended(t);
        List<Map<String, Object>> out = new ArrayList<>();
        List<StackFrame> frames = t.frames();
        for (int i = 0; i < frames.size(); i++) {
            StackFrame f = frames.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            Location loc = f.location();
            row.put("class", loc.declaringType().name());
            Method m = loc.method();
            row.put("method", m.name());
            row.put("signature", m.signature());
            row.put("location", loc.declaringType().name() + "#" + m.name() + ":" + loc.lineNumber());
            row.put("line", loc.lineNumber());
            try {
                row.put("source_name", loc.sourceName());
            } catch (AbsentInformationException ignored) {}
            ObjectReference thisObj = f.thisObject();
            if (thisObj != null) {
                row.put("this_id", ids.idFor("obj", String.valueOf(thisObj.uniqueID()), thisObj));
                row.put("this_type", thisObj.referenceType().name());
            }
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> locals(ThreadReference t, int frameIndex) throws Exception {
        SuspendState.requireSuspended(t);
        StackFrame f = t.frame(frameIndex);
        Map<String, Object> out = new LinkedHashMap<>();
        ObjectReference thisObj = f.thisObject();
        if (thisObj != null) {
            out.put("this", valueToJson(thisObj));
        }
        Map<String, Object> locals = new LinkedHashMap<>();
        try {
            for (LocalVariable lv : f.visibleVariables()) {
                Value v = f.getValue(lv);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", lv.typeName());
                entry.put("value", valueToJson(v));
                locals.put(lv.name(), entry);
            }
        } catch (AbsentInformationException e) {
            out.put("warning", "no local variable info — compile with -g");
        }
        out.put("locals", locals);
        return out;
    }

    public Map<String, Object> inspectObject(ObjectReference obj, int maxDepth) {
        Set<Long> seen = new HashSet<>();
        return inspectInternal(obj, maxDepth, seen);
    }

    private Map<String, Object> inspectInternal(ObjectReference obj, int depth, Set<Long> seen) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (obj == null) {
            out.put("value", null);
            return out;
        }
        out.put("object_id", ids.idFor("obj", String.valueOf(obj.uniqueID()), obj));
        out.put("type", obj.referenceType().name());
        out.put("summary", summarize(obj));

        if (obj instanceof StringReference sr) {
            out.put("string_value", truncate(sr.value(), OBJECT_SUMMARY_STRING_MAX * 4));
            return out;
        }
        if (obj instanceof ArrayReference ar) {
            out.put("length", ar.length());
            return out;
        }

        if (depth <= 0) return out;
        if (!seen.add(obj.uniqueID())) {
            out.put("cycle", true);
            return out;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        ReferenceType rt = obj.referenceType();
        for (Field f : rt.fields()) {
            if (f.isStatic()) continue;
            try {
                Value v = obj.getValue(f);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", f.typeName());
                if (v instanceof ObjectReference oref && depth - 1 > 0) {
                    entry.put("value", inspectInternal(oref, depth - 1, seen));
                } else {
                    entry.put("value", valueToJson(v));
                }
                fields.put(f.name(), entry);
            } catch (Exception ex) {
                fields.put(f.name(), Map.of("error", String.valueOf(ex)));
            }
        }
        out.put("fields", fields);
        return out;
    }

    public Map<String, Object> readArray(ArrayReference ar, int start, int count) {
        Map<String, Object> out = new LinkedHashMap<>();
        int length = ar.length();
        out.put("array_id", ids.idFor("obj", String.valueOf(ar.uniqueID()), ar));
        out.put("type", ar.referenceType().name());
        out.put("length", length);
        int s = Math.max(0, Math.min(start, length));
        int c = Math.max(0, Math.min(count, length - s));
        out.put("start", s);
        out.put("count", c);
        List<Object> values = new ArrayList<>();
        for (int i = s; i < s + c; i++) {
            values.add(valueToJson(ar.getValue(i)));
        }
        out.put("values", values);
        return out;
    }

    public Object valueToJson(Value v) {
        if (v == null) return null;
        if (v instanceof BooleanValue b) return b.value();
        if (v instanceof ByteValue b) return (int) b.value();
        if (v instanceof CharValue c) return String.valueOf(c.value());
        if (v instanceof ShortValue s) return (int) s.value();
        if (v instanceof IntegerValue i) return i.value();
        if (v instanceof LongValue l) return l.value();
        if (v instanceof FloatValue f) return (double) f.value();
        if (v instanceof DoubleValue d) return d.value();
        if (v instanceof StringReference sr) {
            return truncate(sr.value(), OBJECT_SUMMARY_STRING_MAX * 4);
        }
        if (v instanceof ArrayReference ar) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("object_id", ids.idFor("obj", String.valueOf(ar.uniqueID()), ar));
            out.put("type", ar.referenceType().name());
            out.put("length", ar.length());
            out.put("kind", "array");
            return out;
        }
        if (v instanceof ObjectReference o) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("object_id", ids.idFor("obj", String.valueOf(o.uniqueID()), o));
            out.put("type", o.referenceType().name());
            out.put("summary", summarize(o));
            return out;
        }
        if (v instanceof PrimitiveValue pv) {
            return pv.toString();
        }
        return String.valueOf(v);
    }

    private String summarize(ObjectReference o) {
        if (o instanceof StringReference sr) {
            return "\"" + truncate(sr.value(), OBJECT_SUMMARY_STRING_MAX) + "\"";
        }
        if (o instanceof ArrayReference ar) {
            return ar.referenceType().name() + "[" + ar.length() + "]";
        }
        return o.referenceType().name() + "@" + Long.toHexString(o.uniqueID());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String threadStatus(int status) {
        return switch (status) {
            case ThreadReference.THREAD_STATUS_UNKNOWN -> "unknown";
            case ThreadReference.THREAD_STATUS_ZOMBIE -> "zombie";
            case ThreadReference.THREAD_STATUS_RUNNING -> "running";
            case ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping";
            case ThreadReference.THREAD_STATUS_MONITOR -> "monitor";
            case ThreadReference.THREAD_STATUS_WAIT -> "wait";
            case ThreadReference.THREAD_STATUS_NOT_STARTED -> "not_started";
            default -> "status_" + status;
        };
    }
}
