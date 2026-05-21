package com.ittai.debugbridge;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

public final class ValueCoercer {

    private final Ids ids;

    public ValueCoercer(Ids ids) {
        this.ids = ids;
    }

    public List<Value> coerce(VirtualMachine vm, List<String> paramTypes, List<Object> args) {
        if (args.size() != paramTypes.size()) {
            throw new IllegalArgumentException(
                "arg count mismatch: method expects " + paramTypes.size() + ", got " + args.size());
        }
        List<Value> out = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            out.add(coerceOne(vm, paramTypes.get(i), args.get(i)));
        }
        return out;
    }

    public Value coerceOne(VirtualMachine vm, String paramType, Object arg) {
        if (arg == null) return null;

        if (arg instanceof String s && ids.has(s)) {
            Object ref = ids.resolveAny(s);
            if (ref instanceof Value v) return v;
            throw new IllegalArgumentException("id " + s + " does not resolve to a JDI Value");
        }

        return switch (paramType) {
            case "boolean" -> vm.mirrorOf(toBool(arg));
            case "byte" -> vm.mirrorOf(toLong(arg).byteValue());
            case "short" -> vm.mirrorOf(toLong(arg).shortValue());
            case "char" -> {
                String s = String.valueOf(arg);
                if (s.isEmpty()) throw new IllegalArgumentException("empty char arg");
                yield vm.mirrorOf(s.charAt(0));
            }
            case "int" -> vm.mirrorOf(toLong(arg).intValue());
            case "long" -> vm.mirrorOf(toLong(arg));
            case "float" -> vm.mirrorOf(toDouble(arg).floatValue());
            case "double" -> vm.mirrorOf(toDouble(arg));
            case "java.lang.String" -> {
                ObjectReference s = vm.mirrorOf(String.valueOf(arg));
                yield s;
            }
            default -> {
                if (arg instanceof String s) {
                    if (ids.has(s)) {
                        Object ref = ids.resolveAny(s);
                        if (ref instanceof Value v) yield v;
                        throw new IllegalArgumentException("id " + s + " does not resolve to a JDI Value");
                    }
                    yield vm.mirrorOf(s);
                }
                throw new IllegalArgumentException(
                    "cannot coerce " + arg + " (" + arg.getClass().getSimpleName()
                        + ") to parameter type " + paramType
                        + " — pass an object_id for non-primitive args");
            }
        };
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) return Long.parseLong(s);
        if (o instanceof Boolean b) return b ? 1L : 0L;
        throw new IllegalArgumentException("cannot convert to long: " + o);
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) return Double.parseDouble(s);
        throw new IllegalArgumentException("cannot convert to double: " + o);
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        throw new IllegalArgumentException("cannot convert to boolean: " + o);
    }
}
