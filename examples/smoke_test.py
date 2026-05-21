#!/usr/bin/env python3
"""Smoke test the debug-bridge MCP server end-to-end against HelloDebug.

Workflow:
1. Start HelloDebug with JDWP on a free port (5005).
2. Spawn the debug-bridge fat jar as an MCP server over stdio.
3. Send the standard MCP initialize handshake, then drive a debugging session.
4. Print PASS/FAIL per step.

Run: python smoke_test.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
JAR = ROOT / "build" / "libs" / "debug-bridge-0.1.0.jar"
JDWP_PORT = 5005


class McpClient:
    def __init__(self, proc: subprocess.Popen):
        self.proc = proc
        self.next_id = 0
        self.lock = threading.Lock()
        self._stderr_thread = threading.Thread(target=self._drain_stderr, daemon=True)
        self._stderr_thread.start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[debug-bridge stderr] " + line)

    def send(self, method: str, params=None, *, is_notification=False):
        with self.lock:
            msg = {"jsonrpc": "2.0", "method": method}
            if params is not None:
                msg["params"] = params
            if not is_notification:
                self.next_id += 1
                msg["id"] = self.next_id
            line = json.dumps(msg) + "\n"
            self.proc.stdin.write(line)
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
                sys.stderr.write("[non-json from server] " + line)
                continue
            if msg.get("id") == want_id:
                return msg

    def call_tool(self, name, arguments):
        resp = self.send("tools/call", {"name": name, "arguments": arguments})
        if "error" in resp:
            raise RuntimeError(f"{name} error: {resp['error']}")
        result = resp["result"]
        if result.get("isError"):
            text = result["content"][0]["text"] if result.get("content") else "(no content)"
            raise RuntimeError(f"{name} tool returned isError: {text}")
        if "structuredContent" in result and result["structuredContent"] is not None:
            return result["structuredContent"]
        if result.get("content"):
            return json.loads(result["content"][0]["text"])
        return {}


def start_target() -> subprocess.Popen:
    cmd = [
        "java",
        f"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:{JDWP_PORT}",
        "-cp", str(EXAMPLES), "HelloDebug",
    ]
    print(f"$ {' '.join(cmd)}")
    p = subprocess.Popen(
        cmd,
        cwd=str(EXAMPLES),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    first_line = p.stdout.readline()
    sys.stdout.write(f"[target stdout] {first_line}")
    return p


def start_bridge() -> subprocess.Popen:
    cmd = ["java", "--add-modules", "jdk.jdi", "-jar", str(JAR)]
    print(f"$ {' '.join(cmd)}")
    return subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )


def main():
    if not JAR.exists():
        print(f"missing fat jar: {JAR}; run `gradle shadowJar` first", file=sys.stderr)
        sys.exit(2)

    target = start_target()
    time.sleep(0.5)
    bridge_proc = start_bridge()
    client = McpClient(bridge_proc)

    failures = 0

    def step(name, fn):
        nonlocal failures
        print(f"\n--- {name} ---")
        try:
            result = fn()
            print("PASS:", json.dumps(result, indent=2, default=str)[:600])
            return result
        except Exception as e:
            failures += 1
            print(f"FAIL: {e}")
            return None

    try:
        # MCP handshake
        init = client.send("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "smoke-test", "version": "0.1"},
        })
        print("initialize:", json.dumps(init.get("result", {}).get("serverInfo", {})))
        client.send("notifications/initialized", {}, is_notification=True)

        attached = step("attach", lambda: client.call_tool("attach", {"host": "localhost", "port": JDWP_PORT}))

        step("status", lambda: client.call_tool("status", {}))

        step("list_threads", lambda: client.call_tool("list_threads", {}))

        bp = step("set_breakpoint at HelloDebug:24 (Thread.sleep)",
                  lambda: client.call_tool("set_breakpoint", {"class": "HelloDebug", "line": 28}))

        ev = step("continue_and_wait (expect breakpoint hit)",
                  lambda: client.call_tool("continue_and_wait", {"timeout_ms": 15000}))

        thread_id = ev.get("thread_id") if isinstance(ev, dict) else None
        if thread_id:
            frames = step("frames", lambda: client.call_tool("frames", {"thread_id": thread_id}))
            this_id = None
            if frames and frames.get("frames"):
                this_id = frames["frames"][0].get("this_id")

            step("locals", lambda: client.call_tool("locals", {"thread_id": thread_id, "frame_index": 0}))

            if this_id:
                step("inspect_object(this)", lambda: client.call_tool("inspect_object", {"object_id": this_id, "max_depth": 2}))

                step("invoke_method describe()",
                     lambda: client.call_tool("invoke_method", {
                         "target": this_id, "method": "describe",
                         "signature": "()Ljava/lang/String;",
                         "args": [], "thread_id": thread_id,
                     }))

                step("evaluate this.counter + 1",
                     lambda: client.call_tool("evaluate", {
                         "expr": "this.counter + 1",
                         "thread_id": thread_id,
                     }))

                step("evaluate this.describe()",
                     lambda: client.call_tool("evaluate", {
                         "expr": "this.describe()",
                         "thread_id": thread_id,
                     }))

                step("evaluate unsupported stream pipeline (expect unsupported_syntax)",
                     lambda: client.call_tool("evaluate", {
                         "expr": "users.stream().count()",
                         "thread_id": thread_id,
                     }))

            step("continue_all", lambda: client.call_tool("continue_all", {}))

        dump_path = str(ROOT / "session.md")
        step(f"dump_session to {dump_path}", lambda: client.call_tool("dump_session", {"path": dump_path}))

        step("detach", lambda: client.call_tool("detach", {}))

    finally:
        try:
            bridge_proc.stdin.close()
        except Exception:
            pass
        try:
            bridge_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            bridge_proc.kill()
        try:
            target.kill()
        except Exception:
            pass

    print(f"\n=== summary: {failures} failure(s) ===")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
