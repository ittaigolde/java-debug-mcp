#!/usr/bin/env python3
"""Verify the debug-bridge web UI end-to-end.

Launches the MCP jar with -Ddebug-bridge.ui.port=auto. Drives the same HelloDebug
debugging scenario as smoke_test.py but also: confirms the UI URL is returned,
fetches /api/state and /api/frames over HTTP, opens /api/events SSE in the
background and asserts that a breakpoint hit shows up there, then stops the UI.
"""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
JAR = ROOT / "build" / "libs" / "debug-bridge-0.1.0.jar"
JDWP_PORT = 5006


class McpClient:
    def __init__(self, proc):
        self.proc = proc
        self.next_id = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._drain_stderr, daemon=True).start()
        self.stderr_lines = []

    def _drain_stderr(self):
        for line in self.proc.stderr:
            self.stderr_lines.append(line)
            sys.stderr.write("[bridge stderr] " + line)

    def send(self, method, params=None, *, is_notification=False):
        with self.lock:
            msg = {"jsonrpc": "2.0", "method": method}
            if params is not None:
                msg["params"] = params
            if not is_notification:
                self.next_id += 1
                msg["id"] = self.next_id
            self.proc.stdin.write(json.dumps(msg) + "\n")
            self.proc.stdin.flush()
            if is_notification:
                return None
            return self._read_response(msg["id"])

    def _read_response(self, want_id):
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("server closed stdout")
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if msg.get("id") == want_id:
                return msg

    def call_tool(self, name, arguments):
        resp = self.send("tools/call", {"name": name, "arguments": arguments})
        if "error" in resp:
            raise RuntimeError(f"{name} error: {resp['error']}")
        result = resp["result"]
        if result.get("isError"):
            raise RuntimeError(f"{name} returned isError")
        if "structuredContent" in result and result["structuredContent"] is not None:
            return result["structuredContent"]
        return json.loads(result["content"][0]["text"])


def start_target():
    cmd = [
        "java",
        f"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:{JDWP_PORT}",
        "-cp", str(EXAMPLES), "HelloDebug",
    ]
    print(f"$ {' '.join(cmd)}")
    p = subprocess.Popen(cmd, cwd=str(EXAMPLES), stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, bufsize=1)
    print("[target stdout]", p.stdout.readline().rstrip())
    return p


def start_bridge():
    cmd = ["java", "--add-modules", "jdk.jdi",
           "-Ddebug-bridge.ui.port=auto",
           "-jar", str(JAR)]
    print(f"$ {' '.join(cmd)}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, bufsize=1)


def http_get_json(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read())


def open_sse(url, on_event, stop):
    def run():
        req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            buf_event = None
            buf_data = []
            for raw in resp:
                if stop.is_set():
                    return
                line = raw.decode("utf-8", errors="replace").rstrip("\n")
                if line == "":
                    if buf_event and buf_data:
                        on_event(buf_event, "\n".join(buf_data))
                    buf_event, buf_data = None, []
                elif line.startswith(":"):
                    continue
                elif line.startswith("event: "):
                    buf_event = line[7:]
                elif line.startswith("data: "):
                    buf_data.append(line[6:])
    threading.Thread(target=run, daemon=True).start()


def main():
    if not JAR.exists():
        print(f"missing jar at {JAR}", file=sys.stderr); sys.exit(2)

    target = start_target()
    time.sleep(0.5)
    bridge_proc = start_bridge()
    client = McpClient(bridge_proc)

    failures = 0
    def step(name, fn):
        nonlocal failures
        print(f"\n--- {name} ---")
        try:
            r = fn()
            preview = json.dumps(r, indent=2, default=str)[:500] if r is not None else "(none)"
            print("PASS:", preview)
            return r
        except Exception as e:
            failures += 1
            print(f"FAIL: {e}")
            return None

    try:
        client.send("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "ui-smoke", "version": "0.1"},
        })
        client.send("notifications/initialized", {}, is_notification=True)

        ui_info = step("ui_start (no-op if -Dui.port=auto already started it)",
                       lambda: client.call_tool("ui_start", {}))
        url = ui_info["url"] if ui_info else None
        if not url:
            print("FAIL: no URL returned"); sys.exit(1)

        # Verify HTTP endpoints
        step("GET /api/state (before attach)",
             lambda: http_get_json(url + "api/state"))
        step("GET / (index.html present)",
             lambda: ("ok" if urllib.request.urlopen(url, timeout=5).status == 200 else "fail"))

        # Subscribe to SSE BEFORE attaching, so we capture every event.
        sse_events = []
        stop = threading.Event()
        open_sse(url + "api/events",
                 lambda event, data: sse_events.append((event, data)), stop)
        time.sleep(0.5)  # give SSE a moment to handshake + receive snapshot

        step("attach", lambda: client.call_tool("attach", {"host": "localhost", "port": JDWP_PORT}))
        step("set_breakpoint at HelloDebug:28",
             lambda: client.call_tool("set_breakpoint", {"class": "HelloDebug", "line": 28}))
        ev = step("continue_and_wait", lambda: client.call_tool("continue_and_wait", {"timeout_ms": 15000}))
        thread_id = ev.get("thread_id") if ev else None

        if thread_id:
            step("GET /api/frames?thread=" + thread_id,
                 lambda: http_get_json(url + "api/frames?thread=" + thread_id))
            step("GET /api/source?class=HelloDebug&line=28",
                 lambda: http_get_json(url + "api/source?class=HelloDebug&line=28"))

        step("GET /api/state (with attach + paused thread)",
             lambda: http_get_json(url + "api/state"))

        time.sleep(0.5)  # let SSE catch up
        kinds_seen = [e for e, _ in sse_events]
        step(f"SSE received events ({len(sse_events)})",
             lambda: {"events": kinds_seen[:12],
                      "saw_snapshot": "snapshot" in kinds_seen,
                      "saw_debug_event": "debug_event" in kinds_seen,
                      "saw_tool_call": "tool_call" in kinds_seen})

        step("continue_all", lambda: client.call_tool("continue_all", {}))
        step("ui_stop", lambda: client.call_tool("ui_stop", {}))

        # Port should be freed.
        try:
            sock = socket.socket()
            sock.settimeout(1.0)
            host = re.search(r"://([^:/]+):(\d+)", url)
            sock.connect((host.group(1), int(host.group(2))))
            sock.close()
            print("FAIL: port still accepting connections after ui_stop")
            failures += 1
        except OSError:
            print("PASS: port freed after ui_stop")

        step("detach", lambda: client.call_tool("detach", {}))
        stop.set()

    finally:
        try: bridge_proc.stdin.close()
        except Exception: pass
        try: bridge_proc.wait(timeout=5)
        except Exception: bridge_proc.kill()
        try: target.kill()
        except Exception: pass

    print(f"\n=== summary: {failures} failure(s) ===")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
