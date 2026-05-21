package com.ittai.debugbridge.tools;

import com.ittai.debugbridge.Session;
import com.ittai.debugbridge.ui.NoteLog;
import com.ittai.debugbridge.ui.UiBroadcaster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ittai.debugbridge.tools.Schemas.*;

public final class NoteTools {

    public static List<ToolSpec> all(NoteLog log, UiBroadcaster broadcaster, Session session) {
        return List.of(
            new ToolSpec(
                "note",
                "Record a thought, plan, observation, finding, or decision. Use this PROACTIVELY "
                    + "during debugging: before setting a breakpoint, say what you're looking for; "
                    + "while waiting, say what you're waiting on; when you find something, say what "
                    + "you found and what it means. Notes appear live in the UI's Notes pane and are "
                    + "captured in the session dump so future readers see reasoning, not just tool "
                    + "calls. Keep each note short (one or two sentences) and concrete. "
                    + "kind: 'plan' | 'waiting' | 'finding' | 'decision' | 'thought' (default).",
                object(
                    props(
                        "text", str("The thought to record (one or two sentences)"),
                        "kind", str("Category: plan, waiting, finding, decision, thought (default)")
                    ),
                    reqs("text")
                ),
                args -> {
                    String text = Args.string(args, "text");
                    String kind = Args.stringOpt(args, "kind", "thought");
                    NoteLog.Note n = log.add(kind, text);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("seq", n.seq());
                    payload.put("ts_ms", n.timestampMs());
                    payload.put("kind", n.kind());
                    payload.put("text", n.text());
                    if (broadcaster.hasSubscribers()) {
                        broadcaster.publish("note", payload);
                    }
                    session.record("note", Map.of("kind", n.kind(), "text", n.text()));
                    return payload;
                }
            )
        );
    }

    private NoteTools() {}
}
