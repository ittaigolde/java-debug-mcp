package com.ittai.debugbridge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class UiServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StateView view;
    private final UiBroadcaster broadcaster;
    private final UserGate gate;
    private volatile HttpServer server;
    private volatile int boundPort;

    public UiServer(StateView view, UiBroadcaster broadcaster, UserGate gate) {
        this.view = view;
        this.broadcaster = broadcaster;
        this.gate = gate;
    }

    public synchronized Map<String, Object> start(int requestedPort) throws IOException {
        if (server != null) {
            return Map.of("url", url(), "port", boundPort, "already_running", true);
        }
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Math.max(0, requestedPort)), 0);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "debug-bridge-ui");
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/", this::serveIndex);
        s.createContext("/static/", this::serveStatic);
        s.createContext("/api/state", this::serveState);
        s.createContext("/api/frames", this::serveFrames);
        s.createContext("/api/source", this::serveSource);
        s.createContext("/api/events", this::serveEvents);
        s.createContext("/api/ready", this::serveReady);
        s.start();
        this.server = s;
        this.boundPort = s.getAddress().getPort();
        System.err.println("[debug-bridge] UI: " + url());
        return Map.of("url", url(), "port", boundPort, "already_running", false);
    }

    public synchronized Map<String, Object> stop() {
        if (server == null) return Map.of("stopped", false, "reason", "not running");
        broadcaster.closeAll();
        server.stop(0);
        server = null;
        boundPort = 0;
        return Map.of("stopped", true);
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String url() {
        return server == null ? null : "http://127.0.0.1:" + boundPort + "/";
    }

    // --- handlers --------------------------------------------------

    private void serveIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/") && !ex.getRequestURI().getPath().equals("/index.html")) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        byte[] body = readResource("/ui/index.html");
        if (body == null) {
            send(ex, 500, "text/plain", "missing /ui/index.html in jar");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String name = path.substring("/static/".length());
        if (name.contains("..") || name.contains("/") || name.isBlank()) {
            send(ex, 400, "text/plain", "bad path");
            return;
        }
        byte[] body = readResource("/ui/" + name);
        if (body == null) { send(ex, 404, "text/plain", "not found"); return; }
        String mime = name.endsWith(".js") ? "application/javascript"
            : name.endsWith(".css") ? "text/css"
            : name.endsWith(".html") ? "text/html; charset=utf-8"
            : "application/octet-stream";
        send(ex, 200, mime, body);
    }

    private void serveState(HttpExchange ex) throws IOException {
        try {
            sendJson(ex, 200, view.snapshot());
        } catch (Exception e) {
            sendJson(ex, 500, errorMap(e));
        }
    }

    private void serveFrames(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String threadId = q.get("thread");
        if (threadId == null) { sendJson(ex, 400, errorMap("missing thread")); return; }
        try {
            sendJson(ex, 200, view.framesAndTopLocals(threadId));
        } catch (Exception e) {
            sendJson(ex, 500, errorMap(e));
        }
    }

    private void serveSource(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String classFqn = q.get("class");
        String sourceName = q.getOrDefault("source", deriveSourceName(classFqn));
        String lineStr = q.getOrDefault("line", "1");
        if (classFqn == null) { sendJson(ex, 400, errorMap("missing class")); return; }
        int line;
        try { line = Integer.parseInt(lineStr); }
        catch (NumberFormatException nfe) { sendJson(ex, 400, errorMap("bad line")); return; }
        try {
            sendJson(ex, 200, view.source(classFqn, sourceName, line));
        } catch (Exception e) {
            sendJson(ex, 500, errorMap(e));
        }
    }

    private void serveReady(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        gate.signalReady();
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        UiBroadcaster.Subscriber sub = broadcaster.subscribe();
        try (OutputStream out = ex.getResponseBody()) {
            // Initial snapshot so the client can render before any event fires.
            String snap = JSON.writeValueAsString(view.snapshot());
            writeSse(out, "snapshot", snap);
            while (sub.isActive()) {
                UiBroadcaster.Message msg;
                try {
                    msg = sub.poll(15000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (msg == null) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                writeSse(out, msg.event(), msg.json());
            }
        } catch (IOException io) {
            // client disconnected
        } finally {
            broadcaster.unsubscribe(sub);
        }
    }

    // --- helpers ---------------------------------------------------

    private static void writeSse(OutputStream out, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append("\n");
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append("\n");
        }
        sb.append("\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream is = UiServer.class.getResourceAsStream(name)) {
            return is == null ? null : is.readAllBytes();
        }
    }

    private static String deriveSourceName(String classFqn) {
        if (classFqn == null) return null;
        int dot = classFqn.lastIndexOf('.');
        String simple = dot < 0 ? classFqn : classFqn.substring(dot + 1);
        int dollar = simple.indexOf('$');
        if (dollar >= 0) simple = simple.substring(0, dollar);
        return simple + ".java";
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return out;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) out.put(decode(kv), "");
            else out.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String mime, String body) throws IOException {
        send(ex, status, mime, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String mime, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        send(ex, status, "application/json; charset=utf-8", bytes);
    }

    private static Map<String, Object> errorMap(Object msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (msg instanceof Throwable t) m.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        else m.put("error", String.valueOf(msg));
        return m;
    }
}
