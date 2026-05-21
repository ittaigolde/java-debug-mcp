package com.ittai.debugbridge;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;

public final class SuspendState {

    public static void requireSuspended(ThreadReference t) {
        if (t == null) {
            throw new IllegalStateException("thread is null");
        }
        if (!t.isSuspended()) {
            throw new IllegalStateException(
                "thread " + t.name() + " is not suspended; inspection/invocation requires a paused thread");
        }
    }

    public static boolean anySuspended(VirtualMachine vm) {
        for (ThreadReference t : vm.allThreads()) {
            if (t.isSuspended()) return true;
        }
        return false;
    }

    public static int suspendCount(VirtualMachine vm) {
        int c = 0;
        for (ThreadReference t : vm.allThreads()) {
            if (t.isSuspended()) c++;
        }
        return c;
    }

    private SuspendState() {}
}
