package com.ittai.debugbridge;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Session {

    public record Entry(long seq, long timestampMs, String kind, Map<String, Object> data) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path jsonlPath;
    private final List<Entry> entries = new ArrayList<>();
    private final long startedMs = System.currentTimeMillis();
    private long seq = 0;
    private Map<String, Object> header = new LinkedHashMap<>();
    private boolean closed = false;

    public Session(Path jsonlPath) throws IOException {
        this.jsonlPath = jsonlPath;
        if (jsonlPath.getParent() != null) {
            Files.createDirectories(jsonlPath.getParent());
        }
        if (!Files.exists(jsonlPath)) {
            Files.createFile(jsonlPath);
        }
        record("session_start", Map.of("started_at", Instant.ofEpochMilli(startedMs).toString()));
    }

    public synchronized void setHeader(String key, Object value) {
        header.put(key, value);
    }

    public synchronized Entry record(String kind, Map<String, Object> data) {
        Map<String, Object> safe = data == null ? Map.of() : new LinkedHashMap<>(data);
        Entry e = new Entry(++seq, System.currentTimeMillis(), kind, safe);
        entries.add(e);
        try (BufferedWriter w = Files.newBufferedWriter(jsonlPath, StandardOpenOption.APPEND)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", e.seq);
            row.put("ts_ms", e.timestampMs);
            row.put("kind", e.kind);
            row.put("data", e.data);
            w.write(MAPPER.writeValueAsString(row));
            w.newLine();
        } catch (IOException ex) {
            System.err.println("[session] write failed: " + ex.getMessage());
        }
        return e;
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        record("session_end", Map.of());
    }

    public synchronized Path jsonlPath() {
        return jsonlPath;
    }

    public synchronized int entryCount() {
        return entries.size();
    }

    public synchronized Map<String, Integer> counters() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Entry e : entries) {
            c.merge(e.kind, 1, Integer::sum);
        }
        return c;
    }

    public synchronized void renderToMarkdown(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# debug-bridge session — ")
            .append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(startedMs)))
            .append("\n\n");

        sb.append("## Target\n");
        if (header.isEmpty()) {
            sb.append("- (no attach recorded)\n");
        } else {
            for (Map.Entry<String, Object> h : header.entrySet()) {
                sb.append("- ").append(h.getKey()).append(": ").append(h.getValue()).append("\n");
            }
        }
        sb.append("\n## Timeline\n\n");

        for (Entry e : entries) {
            long offset = e.timestampMs - startedMs;
            String stamp = String.format("[+%02d:%02d.%03d]",
                offset / 60000, (offset / 1000) % 60, offset % 1000);
            switch (e.kind) {
                case "session_start", "session_end" -> {
                    sb.append("### ").append(stamp).append(" ").append(e.kind).append("\n\n");
                }
                case "tool_call" -> {
                    sb.append("### ").append(stamp).append(" Tool call: ")
                        .append(e.data.getOrDefault("name", "?")).append("\n");
                    sb.append("input:  `").append(toCompactJson(e.data.get("input"))).append("`\n");
                    sb.append("result: `").append(toCompactJson(e.data.get("result"))).append("`\n");
                    Object err = e.data.get("error");
                    if (err != null) {
                        sb.append("error:  ").append(err).append("\n");
                    }
                    sb.append("\n");
                }
                case "event" -> {
                    sb.append("### ").append(stamp).append(" Event: ")
                        .append(e.data.getOrDefault("event_kind", "?")).append("\n");
                    Object thread = e.data.get("thread");
                    if (thread != null) sb.append("thread: ").append(thread).append("\n");
                    Object loc = e.data.get("location");
                    if (loc != null) sb.append("location: ").append(loc).append("\n");
                    Object frames = e.data.get("frames_summary");
                    if (frames != null) {
                        sb.append("top frames:\n");
                        for (Object f : (List<?>) frames) {
                            sb.append("  - ").append(f).append("\n");
                        }
                    }
                    Object locals = e.data.get("locals");
                    if (locals != null) {
                        sb.append("locals: `").append(toCompactJson(locals)).append("`\n");
                    }
                    sb.append("\n");
                }
                default -> {
                    sb.append("### ").append(stamp).append(" ").append(e.kind).append("\n");
                    sb.append("`").append(toCompactJson(e.data)).append("`\n\n");
                }
            }
        }

        sb.append("## Final state\n");
        Map<String, Integer> counts = counters();
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            sb.append("- ").append(c.getKey()).append(": ").append(c.getValue()).append("\n");
        }

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, sb.toString());
    }

    private static String toCompactJson(Object value) {
        if (value == null) return "null";
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException ex) {
            return String.valueOf(value);
        }
    }
}
