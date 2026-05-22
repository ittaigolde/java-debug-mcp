package com.ittai.debugbridge;

import com.sun.jdi.ClassType;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Invoker {

    private final Ids ids;
    private final Inspector inspector;
    private final ExecutorService invokeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debug-bridge-invoker");
        t.setDaemon(true);
        return t;
    });
    private final ValueCoercer coercer;

    public Invoker(Ids ids, Inspector inspector) {
        this.ids = ids;
        this.inspector = inspector;
        this.coercer = new ValueCoercer(ids);
    }

    public Map<String, Object> invoke(
        VirtualMachine vm,
        String targetIdOrFqn,
        String methodName,
        String signature,
        List<Object> args,
        ThreadReference thread,
        long timeoutMs
    ) throws Exception {
        SuspendState.requireSuspended(thread);

        boolean staticCall;
        ObjectReference instance = null;
        ReferenceType type;
        if (ids.has(targetIdOrFqn)) {
            instance = ids.resolve(targetIdOrFqn, ObjectReference.class);
            type = instance.referenceType();
            staticCall = false;
        } else {
            List<ReferenceType> matches = vm.classesByName(targetIdOrFqn);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("target not found as object id or class FQN: " + targetIdOrFqn);
            }
            type = matches.get(0);
            staticCall = true;
        }

        Method method = resolveMethod(type, methodName, signature, args.size());
        List<Value> jdiArgs = coercer.coerce(vm, method.argumentTypeNames(), args);

        Callable<Value> call;
        if (staticCall) {
            if (!(type instanceof ClassType ct)) {
                throw new IllegalArgumentException("static invoke requires a class type, got " + type.getClass());
            }
            call = () -> ct.invokeMethod(thread, method, jdiArgs, ObjectReference.INVOKE_SINGLE_THREADED);
        } else {
            ObjectReference finalInstance = instance;
            call = () -> finalInstance.invokeMethod(thread, method, jdiArgs, ObjectReference.INVOKE_SINGLE_THREADED);
        }

        Future<Value> fut = invokeExecutor.submit(call);
        Value result;
        try {
            result = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new TimeoutException("invoke timed out after " + timeoutMs + "ms: "
                + targetIdOrFqn + "." + methodName
                + " — JDI method invocation is not safely cancellable; the target invocation may still be running");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof InvocationException ie) {
                ObjectReference ex = ie.exception();
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("invocation_exception", ex.referenceType().name());
                err.put("exception_id", ids.idFor("obj", String.valueOf(ex.uniqueID()), ex));
                err.put("toString", safeToString(ex, thread));
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("status", "exception");
                ret.put("error", err);
                return ret;
            }
            if (cause instanceof IncompatibleThreadStateException its) {
                throw new IllegalStateException(
                    "thread not in a state suitable for invocation (must be at breakpoint, exception, or step event): "
                        + its.getMessage());
            }
            throw cause instanceof Exception e ? e : new RuntimeException(cause);
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("status", "ok");
        ret.put("method", method.declaringType().name() + "#" + method.name() + method.signature());
        ret.put("return", inspector.valueToJson(result));
        return ret;
    }

    private String safeToString(ObjectReference ex, ThreadReference thread) {
        try {
            List<Method> ms = ex.referenceType().methodsByName("toString", "()Ljava/lang/String;");
            if (ms.isEmpty()) return ex.toString();
            Value v = ex.invokeMethod(thread, ms.get(0), List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
            return v == null ? "null" : v.toString();
        } catch (Exception e) {
            return ex.toString();
        }
    }

    private Method resolveMethod(ReferenceType type, String name, String signature, int argCount) {
        if (signature != null && !signature.isEmpty()) {
            List<Method> exact = type.methodsByName(name, signature);
            if (exact.isEmpty()) {
                throw new IllegalArgumentException(
                    "no method " + type.name() + "#" + name + signature);
            }
            return exact.get(0);
        }
        List<Method> byName = type.methodsByName(name);
        List<Method> byArity = new ArrayList<>();
        for (Method m : byName) {
            if (m.argumentTypeNames().size() == argCount) byArity.add(m);
        }
        if (byArity.isEmpty()) {
            throw new IllegalArgumentException(
                "no method " + type.name() + "#" + name + " with " + argCount + " args; "
                    + "candidates: " + byName.stream().map(Method::signature).toList());
        }
        if (byArity.size() > 1) {
            throw new IllegalArgumentException(
                "ambiguous: multiple " + type.name() + "#" + name + " with " + argCount
                    + " args — specify signature; candidates: "
                    + byArity.stream().map(Method::signature).toList());
        }
        return byArity.get(0);
    }
}
