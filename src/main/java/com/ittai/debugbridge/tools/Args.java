package com.ittai.debugbridge.tools;

import java.util.List;
import java.util.Map;

public final class Args {

    public static String string(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) throw new IllegalArgumentException("missing required arg: " + name);
        return String.valueOf(v);
    }

    public static String stringOpt(Map<String, Object> args, String name, String dflt) {
        Object v = args.get(name);
        return v == null ? dflt : String.valueOf(v);
    }

    public static int integer(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) throw new IllegalArgumentException("missing required arg: " + name);
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    public static int integerOpt(Map<String, Object> args, String name, int dflt) {
        Object v = args.get(name);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    public static long longOpt(Map<String, Object> args, String name, long dflt) {
        Object v = args.get(name);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    public static boolean boolOpt(Map<String, Object> args, String name, boolean dflt) {
        Object v = args.get(name);
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOpt(Map<String, Object> args, String name) {
        Object v = args.get(name);
        if (v == null) return List.of();
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("arg " + name + " must be an array");
    }

    private Args() {}
}
