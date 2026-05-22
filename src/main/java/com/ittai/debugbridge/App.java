package com.ittai.debugbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ittai.debugbridge.tools.AttachTools;
import com.ittai.debugbridge.tools.BreakpointTools;
import com.ittai.debugbridge.tools.DiagnosticsTools;
import com.ittai.debugbridge.tools.EvalTools;
import com.ittai.debugbridge.tools.EventTools;
import com.ittai.debugbridge.tools.ExecutionTools;
import com.ittai.debugbridge.tools.InspectionTools;
import com.ittai.debugbridge.tools.InvokeTools;
import com.ittai.debugbridge.tools.SessionTools;
import com.ittai.debugbridge.tools.ToolSpec;
import com.ittai.debugbridge.tools.NoteTools;
import com.ittai.debugbridge.tools.UiTools;
import com.ittai.debugbridge.ui.NoteLog;
import com.ittai.debugbridge.ui.SourceResolver;
import com.ittai.debugbridge.ui.StateView;
import com.ittai.debugbridge.ui.UiBroadcaster;
import com.ittai.debugbridge.ui.UiServer;
import com.ittai.debugbridge.ui.UserGate;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

public final class App {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".debug-bridge", "sessions");
        Files.createDirectories(sessionDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        Path jsonl = sessionDir.resolve("session-" + stamp + ".jsonl");

        Ids ids = new Ids();
        EventBus events = new EventBus(1024);
        Session session = new Session(jsonl);
        UiBroadcaster broadcaster = new UiBroadcaster();
        Bridge bridge = new Bridge(ids, events, session, broadcaster);

        SourceResolver sources = new SourceResolver();
        NoteLog notes = new NoteLog();
        UserGate gate = new UserGate(broadcaster);
        StateView stateView = new StateView(bridge, sources, notes, gate);
        UiServer ui = new UiServer(stateView, broadcaster, gate);

        String uiPortProp = System.getProperty("debug-bridge.ui.port");
        if (uiPortProp != null && !uiPortProp.isBlank()) {
            String host = System.getProperty("debug-bridge.ui.host", "127.0.0.1");
            String token = System.getProperty("debug-bridge.ui.token");
            int port;
            if ("auto".equalsIgnoreCase(uiPortProp.trim())) port = 0;
            else port = Integer.parseInt(uiPortProp.trim());
            try {
                ui.start(host, port, token);
            } catch (Exception e) {
                System.err.println("[debug-bridge] UI startup failed: " + e);
            }
        }

        List<ToolSpec> tools = new ArrayList<>();
        tools.addAll(AttachTools.all(bridge));
        tools.addAll(InspectionTools.all(bridge));
        tools.addAll(BreakpointTools.all(bridge));
        tools.addAll(ExecutionTools.all(bridge));
        tools.addAll(InvokeTools.all(bridge));
        tools.addAll(EvalTools.all(bridge));
        tools.addAll(EventTools.all(bridge));
        tools.addAll(SessionTools.all(bridge));
        tools.addAll(DiagnosticsTools.all(bridge));
        tools.addAll(UiTools.all(ui, sources, gate));
        tools.addAll(NoteTools.all(notes, broadcaster, session));

        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean cleanupStarted = new AtomicBoolean(false);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
            new JacksonMcpJsonMapper(JSON),
            new ShutdownOnEofInputStream(System.in, shutdown),
            System.out
        );

        var spec = McpServer.sync(transport)
            .serverInfo("debug-bridge", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
            .instructions(
                "Drives a Java debugger (JDI/JDWP). Workflow: attach(host, port) → "
                    + "set_breakpoint → continue_and_wait → frames/locals/inspect_object → "
                    + "evaluate or invoke_method → dump_session('path.md'). "
                    + "Default suspend_policy is 'event_thread' to avoid freezing live services; "
                    + "use 'all' only when needed. "
                    + "The Markdown dump captures the full session for documentation/research. "
                    + "Optional read-only browser UI: ui_start(port?, host?, token?) returns a URL."
            );

        for (ToolSpec t : tools) {
            spec = spec.tools(buildSpec(t, session, broadcaster));
        }

        McpSyncServer server = spec.build();

        Runnable cleanup = () -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            try { ui.stop(); } catch (Exception ignored) {}
            try { bridge.detach(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            try { server.closeGracefully(); } catch (Exception ignored) {}
            shutdown.countDown();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "debug-bridge-shutdown"));

        shutdown.await();
        cleanup.run();
    }

    private static McpServerFeatures.SyncToolSpecification buildSpec(ToolSpec t, Session session, UiBroadcaster broadcaster) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(t.name())
            .description(t.description())
            .inputSchema(t.inputSchema())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                long start = System.currentTimeMillis();
                Map<String, Object> input = request.arguments() == null ? Map.of() : request.arguments();
                try {
                    Map<String, Object> result = t.handler().handle(input);
                    long elapsed = System.currentTimeMillis() - start;
                    session.record("tool_call", Map.of(
                        "name", t.name(),
                        "input", input,
                        "result", result,
                        "duration_ms", elapsed
                    ));
                    broadcastToolCall(broadcaster, t.name(), input, result, null, elapsed);
                    String json;
                    try {
                        json = JSON.writeValueAsString(result);
                    } catch (Exception je) {
                        json = "{\"error\":\"failed to serialize result: " + je.getMessage() + "\"}";
                    }
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(json)
                        .structuredContent(result)
                        .build();
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Map<String, Object> errMap = Map.of("error", err);
                    session.record("tool_call", Map.of(
                        "name", t.name(),
                        "input", input,
                        "error", err,
                        "duration_ms", elapsed
                    ));
                    broadcastToolCall(broadcaster, t.name(), input, null, err, elapsed);
                    String json;
                    try {
                        json = JSON.writeValueAsString(errMap);
                    } catch (Exception je) {
                        json = "{\"error\":\"" + e.getMessage() + "\"}";
                    }
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(json)
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    private static void broadcastToolCall(
        UiBroadcaster broadcaster,
        String name, Map<String, Object> input, Map<String, Object> result, String error, long durationMs
    ) {
        if (broadcaster == null || !broadcaster.hasSubscribers()) return;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("name", name);
        ev.put("input", input);
        if (result != null) ev.put("result", result);
        if (error != null) ev.put("error", error);
        ev.put("duration_ms", durationMs);
        ev.put("ts_ms", System.currentTimeMillis());
        broadcaster.publish("tool_call", ev);
    }

    private static final class ShutdownOnEofInputStream extends FilterInputStream {
        private final CountDownLatch shutdown;

        ShutdownOnEofInputStream(InputStream in, CountDownLatch shutdown) {
            super(in);
            this.shutdown = shutdown;
        }

        @Override
        public int read() throws IOException {
            int n = super.read();
            if (n < 0) shutdown.countDown();
            return n;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) shutdown.countDown();
            return n;
        }
    }
}
