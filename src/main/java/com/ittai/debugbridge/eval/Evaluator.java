package com.ittai.debugbridge.eval;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

public final class Evaluator {

    public sealed interface Result {
        record V(Value value) implements Result {}
        record T(ReferenceType type) implements Result {}
        record Partial(String name) implements Result {}
    }

    private final VirtualMachine vm;
    private final ThreadReference thread;
    private final StackFrame frame;

    public Evaluator(VirtualMachine vm, ThreadReference thread, StackFrame frame) {
        this.vm = vm;
        this.thread = thread;
        this.frame = frame;
    }

    public Value evalToValue(Expr expr) throws Exception {
        Result r = eval(expr);
        if (r instanceof Result.V v) return v.value();
        if (r instanceof Result.T t) {
            throw new IllegalArgumentException("expression resolves to class " + t.type().name()
                + ", not a value — append a static field or method call");
        }
        throw new IllegalArgumentException(
            "expression resolves to partial name " + ((Result.Partial) r).name()
                + " — not a known local, field, or class");
    }

    public Result eval(Expr expr) throws Exception {
        return switch (expr) {
            case Expr.IntLit i -> new Result.V(vm.mirrorOf((int) i.value()));
            case Expr.LongLit l -> new Result.V(vm.mirrorOf(l.value()));
            case Expr.DoubleLit d -> new Result.V(vm.mirrorOf(d.value()));
            case Expr.FloatLit f -> new Result.V(vm.mirrorOf(f.value()));
            case Expr.StringLit s -> new Result.V(vm.mirrorOf(s.value()));
            case Expr.CharLit c -> new Result.V(vm.mirrorOf(c.value()));
            case Expr.BoolLit b -> new Result.V(vm.mirrorOf(b.value()));
            case Expr.NullLit ignored -> new Result.V(null);
            case Expr.ThisExpr ignored -> {
                ObjectReference thisObj = frame.thisObject();
                if (thisObj == null) {
                    throw new IllegalStateException("no 'this' in current frame (static method or constructor)");
                }
                yield new Result.V(thisObj);
            }
            case Expr.Ident id -> resolveIdent(id.name());
            case Expr.Member m -> resolveMember(eval(m.target()), m.name());
            case Expr.Call c -> resolveCall(c);
            case Expr.Index idx -> resolveIndex(idx);
            case Expr.BinOp bo -> evalBinOp(bo);
            case Expr.UnaryOp uo -> evalUnaryOp(uo);
        };
    }

    private Result resolveIdent(String name) {
        try {
            LocalVariable lv = frame.visibleVariableByName(name);
            if (lv != null) {
                return new Result.V(frame.getValue(lv));
            }
        } catch (Exception ignored) {}
        ObjectReference thisObj = frame.thisObject();
        if (thisObj != null) {
            Field f = thisObj.referenceType().fieldByName(name);
            if (f != null) {
                return new Result.V(thisObj.getValue(f));
            }
        }
        ReferenceType enclosing = frame.location().declaringType();
        Field staticField = enclosing.fieldByName(name);
        if (staticField != null && staticField.isStatic()) {
            return new Result.V(enclosing.getValue(staticField));
        }
        List<ReferenceType> classes = vm.classesByName(name);
        if (!classes.isEmpty()) {
            return new Result.T(classes.get(0));
        }
        return new Result.Partial(name);
    }

    private Result resolveMember(Result target, String name) {
        if (target instanceof Result.V vr) {
            Value v = vr.value();
            if (v == null) throw new NullPointerException("dereferencing null on field '" + name + "'");
            if (v instanceof ArrayReference ar && "length".equals(name)) {
                return new Result.V(vm.mirrorOf(ar.length()));
            }
            if (!(v instanceof ObjectReference obj)) {
                throw new IllegalArgumentException("cannot access field '" + name + "' on primitive");
            }
            Field f = obj.referenceType().fieldByName(name);
            if (f == null) {
                throw new IllegalArgumentException(
                    "no field '" + name + "' on " + obj.referenceType().name());
            }
            return new Result.V(obj.getValue(f));
        }
        if (target instanceof Result.T tr) {
            ReferenceType rt = tr.type();
            Field f = rt.fieldByName(name);
            if (f != null && f.isStatic()) {
                return new Result.V(rt.getValue(f));
            }
            String inner = rt.name() + "$" + name;
            List<ReferenceType> nested = vm.classesByName(inner);
            if (!nested.isEmpty()) return new Result.T(nested.get(0));
            throw new IllegalArgumentException(
                "no static field '" + name + "' on " + rt.name());
        }
        Result.Partial p = (Result.Partial) target;
        String candidate = p.name() + "." + name;
        List<ReferenceType> classes = vm.classesByName(candidate);
        if (!classes.isEmpty()) return new Result.T(classes.get(0));
        return new Result.Partial(candidate);
    }

    private Result resolveCall(Expr.Call c) throws Exception {
        if (c.target() == null) {
            ObjectReference thisObj = frame.thisObject();
            ReferenceType type;
            ObjectReference invokeOn;
            if (thisObj != null) {
                type = thisObj.referenceType();
                invokeOn = thisObj;
            } else {
                type = frame.location().declaringType();
                invokeOn = null;
            }
            return invokeMethod(type, invokeOn, c.name(), c.args());
        }
        Result target = eval(c.target());
        if (target instanceof Result.V vr) {
            Value v = vr.value();
            if (v == null) throw new NullPointerException("method call on null: " + c.name());
            if (!(v instanceof ObjectReference obj)) {
                throw new IllegalArgumentException("cannot call method on primitive: " + c.name());
            }
            return invokeMethod(obj.referenceType(), obj, c.name(), c.args());
        }
        if (target instanceof Result.T tr) {
            return invokeMethod(tr.type(), null, c.name(), c.args());
        }
        throw new IllegalArgumentException(
            "cannot resolve call on partial name " + ((Result.Partial) target).name());
    }

    private Result invokeMethod(ReferenceType type, ObjectReference instance, String name, List<Expr> argExprs)
        throws Exception {
        List<Value> argVals = new ArrayList<>(argExprs.size());
        for (Expr ae : argExprs) {
            Result r = eval(ae);
            if (!(r instanceof Result.V vv)) {
                throw new IllegalArgumentException("argument is not a value: " + ae);
            }
            argVals.add(vv.value());
        }
        Method method = selectMethod(type, name, argVals);
        Value result;
        if (instance != null) {
            result = instance.invokeMethod(thread, method, argVals, ObjectReference.INVOKE_SINGLE_THREADED);
        } else {
            if (!(type instanceof ClassType ct)) {
                throw new IllegalArgumentException(
                    "static invocation requires class type, got " + type.getClass().getSimpleName());
            }
            result = ct.invokeMethod(thread, method, argVals, ObjectReference.INVOKE_SINGLE_THREADED);
        }
        return new Result.V(result);
    }

    private Method selectMethod(ReferenceType type, String name, List<Value> argVals) {
        List<Method> byName = type.methodsByName(name);
        List<Method> candidates = new ArrayList<>();
        for (Method m : byName) {
            if (m.argumentTypeNames().size() == argVals.size()) candidates.add(m);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "no method '" + name + "' on " + type.name() + " with " + argVals.size() + " args");
        }
        if (candidates.size() == 1) return candidates.get(0);
        for (Method m : candidates) {
            if (allArgsAssignable(m.argumentTypeNames(), argVals)) return m;
        }
        throw new IllegalArgumentException(
            "ambiguous overload for '" + name + "' on " + type.name() + " with " + argVals.size()
                + " args — pass exact signature via invoke_method");
    }

    private boolean allArgsAssignable(List<String> paramTypes, List<Value> args) {
        for (int i = 0; i < args.size(); i++) {
            String pt = paramTypes.get(i);
            Value v = args.get(i);
            if (v == null) {
                if (isPrimitiveType(pt)) return false;
                continue;
            }
            if (isPrimitiveType(pt)) {
                if (!(v instanceof PrimitiveValue)) return false;
                continue;
            }
            if (!(v instanceof ObjectReference o)) return false;
            if (!typeNameMatches(o.referenceType().name(), pt)) return false;
        }
        return true;
    }

    private boolean isPrimitiveType(String t) {
        return switch (t) {
            case "boolean", "byte", "short", "char", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    private boolean typeNameMatches(String actualFqn, String paramFqn) {
        if (actualFqn.equals(paramFqn)) return true;
        if ("java.lang.Object".equals(paramFqn)) return true;
        return false;
    }

    private Result resolveIndex(Expr.Index idx) throws Exception {
        Result tr = eval(idx.target());
        if (!(tr instanceof Result.V vr) || !(vr.value() instanceof ArrayReference ar)) {
            throw new IllegalArgumentException("index target is not an array");
        }
        Result ir = eval(idx.index());
        if (!(ir instanceof Result.V iv) || !(iv.value() instanceof PrimitiveValue pv)) {
            throw new IllegalArgumentException("index must be an integer");
        }
        int i = ((Number) primitiveAsJava(pv)).intValue();
        return new Result.V(ar.getValue(i));
    }

    private Result evalBinOp(Expr.BinOp bo) throws Exception {
        Value lv = ((Result.V) eval(bo.left())).value();
        if ("&&".equals(bo.op())) {
            if (!truthy(lv)) return new Result.V(vm.mirrorOf(false));
            Value rv = ((Result.V) eval(bo.right())).value();
            return new Result.V(vm.mirrorOf(truthy(rv)));
        }
        if ("||".equals(bo.op())) {
            if (truthy(lv)) return new Result.V(vm.mirrorOf(true));
            Value rv = ((Result.V) eval(bo.right())).value();
            return new Result.V(vm.mirrorOf(truthy(rv)));
        }
        Value rv = ((Result.V) eval(bo.right())).value();

        if ("+".equals(bo.op()) && (lv instanceof StringReference || rv instanceof StringReference)) {
            return new Result.V(vm.mirrorOf(stringify(lv) + stringify(rv)));
        }
        if ("==".equals(bo.op())) return new Result.V(vm.mirrorOf(refEquals(lv, rv)));
        if ("!=".equals(bo.op())) return new Result.V(vm.mirrorOf(!refEquals(lv, rv)));

        Object ln = primitiveAsJava((PrimitiveValue) lv);
        Object rn = primitiveAsJava((PrimitiveValue) rv);
        return new Result.V(applyNumericOp(bo.op(), ln, rn));
    }

    private Result evalUnaryOp(Expr.UnaryOp uo) throws Exception {
        Value v = ((Result.V) eval(uo.operand())).value();
        return switch (uo.op()) {
            case "+" -> new Result.V(v);
            case "-" -> {
                Object n = primitiveAsJava((PrimitiveValue) v);
                yield new Result.V(applyNumericOp("-", 0, n));
            }
            case "!" -> new Result.V(vm.mirrorOf(!truthy(v)));
            default -> throw new IllegalArgumentException("unsupported unary: " + uo.op());
        };
    }

    private Value applyNumericOp(String op, Object lObj, Object rObj) {
        boolean doublePath = lObj instanceof Double || lObj instanceof Float
            || rObj instanceof Double || rObj instanceof Float;
        if (doublePath) {
            double l = ((Number) lObj).doubleValue();
            double r = ((Number) rObj).doubleValue();
            return switch (op) {
                case "+" -> vm.mirrorOf(l + r);
                case "-" -> vm.mirrorOf(l - r);
                case "*" -> vm.mirrorOf(l * r);
                case "/" -> vm.mirrorOf(l / r);
                case "%" -> vm.mirrorOf(l % r);
                case "<" -> vm.mirrorOf(l < r);
                case ">" -> vm.mirrorOf(l > r);
                case "<=" -> vm.mirrorOf(l <= r);
                case ">=" -> vm.mirrorOf(l >= r);
                default -> throw new IllegalArgumentException("bad numeric op: " + op);
            };
        }
        long l = ((Number) lObj).longValue();
        long r = ((Number) rObj).longValue();
        return switch (op) {
            case "+" -> vm.mirrorOf(l + r);
            case "-" -> vm.mirrorOf(l - r);
            case "*" -> vm.mirrorOf(l * r);
            case "/" -> vm.mirrorOf(l / r);
            case "%" -> vm.mirrorOf(l % r);
            case "<" -> vm.mirrorOf(l < r);
            case ">" -> vm.mirrorOf(l > r);
            case "<=" -> vm.mirrorOf(l <= r);
            case ">=" -> vm.mirrorOf(l >= r);
            default -> throw new IllegalArgumentException("bad numeric op: " + op);
        };
    }

    private static boolean truthy(Value v) {
        if (v == null) return false;
        if (v instanceof BooleanValue b) return b.value();
        if (v instanceof PrimitiveValue pv) {
            Object o = primitiveAsJava(pv);
            if (o instanceof Number n) return n.doubleValue() != 0;
        }
        return true;
    }

    private static boolean refEquals(Value a, Value b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof PrimitiveValue ap && b instanceof PrimitiveValue bp) {
            return primitiveAsJava(ap).equals(primitiveAsJava(bp));
        }
        if (a instanceof ObjectReference ao && b instanceof ObjectReference bo) {
            return ao.uniqueID() == bo.uniqueID();
        }
        return false;
    }

    private static Object primitiveAsJava(PrimitiveValue pv) {
        if (pv instanceof BooleanValue b) return b.value();
        return switch (pv.type().name()) {
            case "byte" -> pv.byteValue();
            case "short" -> pv.shortValue();
            case "char" -> pv.charValue();
            case "int" -> pv.intValue();
            case "long" -> pv.longValue();
            case "float" -> pv.floatValue();
            case "double" -> pv.doubleValue();
            default -> pv.toString();
        };
    }

    private static String stringify(Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference sr) return sr.value();
        if (v instanceof PrimitiveValue pv) return String.valueOf(primitiveAsJava(pv));
        if (v instanceof ObjectReference o) return o.referenceType().name() + "@" + Long.toHexString(o.uniqueID());
        return String.valueOf(v);
    }
}
