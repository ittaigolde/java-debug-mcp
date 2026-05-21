package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;

import java.util.List;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class AttachTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            new ToolSpec(
                "attach",
                "Attach to a running JVM via JDWP socket. The target must be started with "
                    + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:PORT",
                object(
                    props(
                        "host", str("Target host (e.g. localhost)"),
                        "port", integer("JDWP listening port")
                    ),
                    reqs("host", "port")
                ),
                args -> bridge.attach(Args.string(args, "host"), Args.integer(args, "port"))
            ),
            new ToolSpec(
                "detach",
                "Detach from the current JVM. The target keeps running.",
                object(props(), reqs()),
                args -> bridge.detach()
            ),
            new ToolSpec(
                "status",
                "Report attach state, VM info, suspended thread count, and event backlog size.",
                object(props(), reqs()),
                args -> bridge.status()
            )
        );
    }

    private AttachTools() {}
}
