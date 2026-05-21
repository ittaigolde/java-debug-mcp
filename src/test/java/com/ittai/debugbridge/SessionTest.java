package com.ittai.debugbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test
    void roundTripWritesJsonlAndRendersMarkdown(@TempDir Path tmp) throws IOException {
        Path jsonl = tmp.resolve("session.jsonl");
        Session s = new Session(jsonl);
        s.setHeader("vm_name", "OpenJDK");
        s.record("tool_call", Map.of("name", "attach", "input", Map.of("host", "localhost", "port", 5005),
            "result", Map.of("ok", true)));
        s.record("event", Map.of("event_kind", "breakpoint_hit", "thread", "thr_1",
            "location", "Foo:42", "frames_summary", List.of("Foo.handle:42")));
        s.close();

        List<String> lines = Files.readAllLines(jsonl);
        assertTrue(lines.size() >= 3);
        assertTrue(lines.get(0).contains("session_start"));

        Path md = tmp.resolve("session.md");
        s.renderToMarkdown(md);
        String content = Files.readString(md);
        assertTrue(content.contains("# debug-bridge session"));
        assertTrue(content.contains("vm_name: OpenJDK"));
        assertTrue(content.contains("Tool call: attach"));
        assertTrue(content.contains("Event: breakpoint_hit"));
    }
}
