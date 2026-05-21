package com.ittai.debugbridge.tools;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schemas {

    public static McpSchema.JsonSchema object(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, null, null, null);
    }

    public static Map<String, Object> str(String desc) {
        return type("string", desc);
    }

    public static Map<String, Object> integer(String desc) {
        return type("integer", desc);
    }

    public static Map<String, Object> number(String desc) {
        return type("number", desc);
    }

    public static Map<String, Object> bool(String desc) {
        return type("boolean", desc);
    }

    public static Map<String, Object> arr(Map<String, Object> items, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        if (desc != null) m.put("description", desc);
        return m;
    }

    public static Map<String, Object> any(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (desc != null) m.put("description", desc);
        return m;
    }

    private static Map<String, Object> type(String t, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t);
        if (desc != null) m.put("description", desc);
        return m;
    }

    public static List<String> reqs(String... names) {
        List<String> l = new ArrayList<>();
        for (String n : names) l.add(n);
        return l;
    }

    public static Map<String, Object> props(Object... kvs) {
        if (kvs.length % 2 != 0) throw new IllegalArgumentException("props needs key-value pairs");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put((String) kvs[i], kvs[i + 1]);
        }
        return m;
    }

    private Schemas() {}
}
