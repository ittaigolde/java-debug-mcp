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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
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
    private volatile String boundHost;
    private volatile String token;

    public UiServer(StateView view, UiBroadcaster broadcaster, UserGate gate) {
        this.view = view;
        this.broadcaster = broadcaster;
        this.gate = gate;
    }

    public synchronized Map<String, Object> start(int requestedPort) throws IOException {
        return start("127.0.0.1", requestedPort, null);
    }

    public synchronized Map<String, Object> start(String requestedHost, int requestedPort, String requestedToken) throws IOException {
        if (server != null) {
            return info(true, false);
        }
        String host = normalizeHost(requestedHost);
        String authToken = resolveToken(host, requestedToken);
        HttpServer s = HttpServer.create(new InetSocketAddress(host, Math.max(0, requestedPort)), 0);
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
        this.boundHost = host;
        this.token = authToken;
        Map<String, Object> out = info(false, "auto".equals(requestedToken));
        System.err.println("[debug-bridge] UI: " + out.get("url"));
        if (authToken != null) {
            System.err.println("[debug-bridge] UI token: " + authToken);
        }
        return out;
    }

    public synchronized Map<String, Object> stop() {
        if (server == null) return Map.of("stopped", false, "reason", "not running");
        broadcaster.closeAll();
        server.stop(0);
        server = null;
        boundPort = 0;
        boundHost = null;
        token = null;
        return Map.of("stopped", true);
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized String url() {
        if (server == null) return null;
        String host = "0.0.0.0".equals(boundHost) ? "127.0.0.1" : boundHost;
        return withToken("http://" + host + ":" + boundPort + "/");
    }

    // --- handlers --------------------------------------------------

    private void serveIndex(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
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
        if (!authorized(ex)) return;
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
        if (!authorized(ex)) return;
        try {
            sendJson(ex, 200, view.snapshot());
        } catch (Exception e) {
            sendJson(ex, 500, errorMap(e));
        }
    }

    private void serveFrames(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
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
        if (!authorized(ex)) return;
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
        if (!authorized(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        gate.signalReady();
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
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

    private Map<String, Object> info(boolean alreadyRunning, boolean generatedToken) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url());
        out.put("host", boundHost);
        out.put("port", boundPort);
        out.put("already_running", alreadyRunning);
        out.put("auth", token == null ? "none" : "token");
        if (token != null) {
            out.put("token", token);
            out.put("generated_token", generatedToken);
        }
        if (!isLoopbackHost(boundHost)) {
            out.put("remote_url_hint", withToken("http://<server-host>:" + boundPort + "/"));
            out.put("warning", "Remote UI is exposed; use only on a trusted network, VPN, SSH tunnel, or reverse proxy with TLS.");
        }
        return out;
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) return "127.0.0.1";
        return host.trim();
    }

    static String resolveTokenForTest(String host, String requestedToken) {
        return resolveToken(host, requestedToken);
    }

    private static String resolveToken(String host, String requestedToken) {
        String token = requestedToken;
        if (token == null || token.isBlank()) token = System.getProperty("debug-bridge.ui.token");
        if (token == null || token.isBlank()) token = System.getenv("DEBUG_BRIDGE_UI_TOKEN");
        if ("auto".equals(token)) token = generateToken();
        if (!isLoopbackHost(host) && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("ui token is required when binding remote host " + host
                + "; pass token='auto', -Ddebug-bridge.ui.token=..., or DEBUG_BRIDGE_UI_TOKEN");
        }
        return token == null || token.isBlank() ? null : token;
    }

    private static boolean isLoopbackHost(String host) {
        return host == null
            || "127.0.0.1".equals(host)
            || "localhost".equalsIgnoreCase(host)
            || "::1".equals(host)
            || "[::1]".equals(host);
    }

    private String withToken(String baseUrl) {
        if (token == null) return baseUrl;
        return baseUrl + "?token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private boolean authorized(HttpExchange ex) throws IOException {
        String expected = token;
        if (expected == null) return true;
        String got = null;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            got = auth.substring(7).trim();
        }
        if (got == null) {
            got = query(ex.getRequestURI()).get("token");
        }
        if (got != null && MessageDigest.isEqual(
            got.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        send(ex, 401, "text/plain", "unauthorized");
        return false;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
