package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.ui.SourceResolver;
import com.ittai.debugbridge.ui.UiServer;
import com.ittai.debugbridge.ui.UserGate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class UiTools {

    public static List<ToolSpec> all(UiServer ui, SourceResolver sources, UserGate gate) {
        return List.of(
            new ToolSpec(
                "ui_start",
                "Start the embedded web UI (read-only observation surface). Returns a localhost URL "
                    + "to open in a browser. Idempotent — if already running, returns the existing URL. "
                    + "host defaults to 127.0.0.1. Remote hosts such as 0.0.0.0 require token='auto' "
                    + "or an explicit token.",
                object(
                    props(
                        "host", str("Bind host (default 127.0.0.1; use 0.0.0.0 only on trusted networks)"),
                        "port", integer("Port to bind (default 0 → free port chosen by OS)"),
                        "token", str("Optional UI auth token. Use 'auto' to generate one. Required for remote host.")
                    ),
                    reqs()
                ),
                args -> {
                    String host = Args.stringOpt(args, "host", "127.0.0.1");
                    int port = Args.integerOpt(args, "port", 0);
                    String token = Args.stringOpt(args, "token", null);
                    return ui.start(host, port, token);
                }
            ),
            new ToolSpec(
                "ui_stop",
                "Stop the embedded web UI and free the port.",
                object(props(), reqs()),
                args -> ui.stop()
            ),
            new ToolSpec(
                "await_user_ready",
                "Block until the user clicks Start in the browser UI banner, or until the timeout elapses. "
                    + "Use this AFTER ui_start and BEFORE the first debugging action, so the user has time to "
                    + "open the UI before things happen. Also useful as a checkpoint between phases or before "
                    + "a destructive action. Returns immediately if the UI is not running (headless). Clicks "
                    + "made before this is called are buffered and satisfy the next wait. Re-armable: each "
                    + "call requires a fresh click.",
                object(
                    props("timeout_ms", integer("Max wait in ms (default 300000 = 5 minutes)")),
                    reqs()
                ),
                args -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    if (!ui.isRunning()) {
                        out.put("ready", true);
                        out.put("headless", true);
                        return out;
                    }
                    long timeout = Args.longOpt(args, "timeout_ms", 300_000L);
                    boolean ready = gate.await(timeout);
                    out.put("ready", ready);
                    if (!ready) out.put("reason", "timeout after " + timeout + "ms");
                    return out;
                }
            ),
            new ToolSpec(
                "set_sourcepath",
                "Set the list of source roots used by the UI's source view. Replaces any existing "
                    + "sourcepath. Pass an empty list to fall back to CWD heuristics.",
                object(
                    props("paths", arr(str("Source root directory"),
                        "Source root directories (replaces any previous setting)")),
                    reqs("paths")
                ),
                args -> {
                    @SuppressWarnings("unchecked")
                    List<Object> raw = (List<Object>) args.getOrDefault("paths", List.of());
                    List<String> paths = new ArrayList<>();
                    for (Object o : raw) paths.add(String.valueOf(o));
                    List<String> applied = sources.setRoots(paths);
                    return Map.of("sourcepath", applied);
                }
            )
        );
    }

    private UiTools() {}
}
