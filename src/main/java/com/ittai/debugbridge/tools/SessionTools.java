package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Bridge;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class SessionTools {

    public static List<ToolSpec> all(Bridge bridge) {
        return List.of(
            new ToolSpec(
                "dump_session",
                "Render the in-memory session log to a Markdown file at the given path. "
                    + "The append-only JSONL also remains on disk for later re-rendering.",
                object(props("path", str("Output file path (absolute or relative to CWD)")), reqs("path")),
                args -> {
                    Path out = Paths.get(Args.string(args, "path")).toAbsolutePath();
                    bridge.session().renderToMarkdown(out);
                    return Map.of(
                        "wrote", out.toString(),
                        "entries", bridge.session().entryCount(),
                        "jsonl_path", bridge.session().jsonlPath().toString()
                    );
                }
            ),
            new ToolSpec(
                "session_status",
                "Counts of tool calls and events recorded in this session.",
                object(props(), reqs()),
                args -> Map.of(
                    "entries", bridge.session().entryCount(),
                    "counters", bridge.session().counters(),
                    "jsonl_path", bridge.session().jsonlPath().toString()
                )
            )
        );
    }

    private SessionTools() {}
}
