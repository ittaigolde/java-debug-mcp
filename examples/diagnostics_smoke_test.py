#!/usr/bin/env python3
"""Smoke test the four new diagnostics tools: thread_dump, heap_dump,
list_instances, class_histogram. Uses HelloDebug as target."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
JAR = ROOT / "build" / "libs" / "debug-bridge-0.1.0.jar"
JDWP_PORT = 5007


class McpClient:
    def __init__(self, proc):
        self.proc = proc
        self.next_id = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[bridge] " + line)

    def send(self, method, params=None, *, is_notification=False):
        with self.lock:
            msg = {"jsonrpc": "2.0", "method": method}
            if params is not None: msg["params"] = params
            if not is_notification:
                self.next_id += 1
                msg["id"] = self.next_id
            self.proc.stdin.write(json.dumps(msg) + "\n")
            self.proc.stdin.flush()
            if is_notification: return None
            return self._read(msg["id"])

    def _read(self, want_id):
        while True:
            line = self.proc.stdout.readline()
            if not line: raise RuntimeError("server closed")
            try: msg = json.loads(line)
            except json.JSONDecodeError: continue
            if msg.get("id") == want_id: return msg

    def call(self, name, arguments):
        r = self.send("tools/call", {"name": name, "arguments": arguments})
        if "error" in r: raise RuntimeError(f"{name}: {r['error']}")
        result = r["result"]
        if result.get("isError"):
            text = result["content"][0]["text"] if result.get("content") else "?"
            raise RuntimeError(f"{name} isError: {text}")
        if result.get("structuredContent") is not None: return result["structuredContent"]
        return json.loads(result["content"][0]["text"])


def main():
    if not JAR.exists():
        print(f"missing jar at {JAR}"); sys.exit(2)

    target = subprocess.Popen(
        ["java", f"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:{JDWP_PORT}",
         "-cp", str(EXAMPLES), "HelloDebug"],
        cwd=str(EXAMPLES), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1)
    print("[target]", target.stdout.readline().rstrip())
    time.sleep(0.5)

    bridge = subprocess.Popen(
        ["java", "--add-modules", "jdk.jdi", "-jar", str(JAR)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, bufsize=1)
    client = McpClient(bridge)
    failures = 0

    def step(name, fn):
        nonlocal failures
        print(f"\n--- {name} ---")
        try:
            r = fn()
            preview = json.dumps(r, indent=2, default=str)[:700]
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
            "clientInfo": {"name": "diag-smoke", "version": "0.1"},
        })
        client.send("notifications/initialized", {}, is_notification=True)

        step("attach", lambda: client.call("attach", {"host": "localhost", "port": JDWP_PORT}))

        # thread_dump without paused threads → should auto-suspend briefly.
        td = step("thread_dump (json, auto-suspend)",
                  lambda: client.call("thread_dump", {"format": "json"}))
        assert td and td.get("thread_count", 0) > 0, "thread_dump returned no threads"

        step("thread_dump (jstack text)",
             lambda: client.call("thread_dump", {"format": "jstack", "include_system": False}))

        # class_histogram with filter.
        step("class_histogram(pattern=HelloDebug)",
             lambda: client.call("class_histogram", {"pattern": "HelloDebug", "top_n": 10}))

        # Need a paused thread for heap_dump and list_instances (list_instances actually doesn't
        # require a paused thread — but heap_dump does).
        step("set_breakpoint at HelloDebug:28",
             lambda: client.call("set_breakpoint", {"class": "HelloDebug", "line": 28}))
        ev = step("continue_and_wait", lambda: client.call("continue_and_wait", {"timeout_ms": 15000}))
        thread_id = ev["thread_id"] if ev else None

        # list_instances of HelloDebug (1) and HelloDebug$User (3).
        step("list_instances(HelloDebug)",
             lambda: client.call("list_instances", {"class": "HelloDebug", "max": 10}))
        step("list_instances(HelloDebug$User)",
             lambda: client.call("list_instances", {"class": "HelloDebug$User", "max": 10}))

        # heap_dump
        tmp_dump = Path(tempfile.gettempdir()) / f"debug-bridge-test-{int(time.time())}.hprof"
        try: tmp_dump.unlink()
        except FileNotFoundError: pass
        if thread_id:
            step(f"heap_dump -> {tmp_dump}",
                 lambda: client.call("heap_dump",
                                     {"path": str(tmp_dump), "thread_id": thread_id, "live": True}))
            exists = tmp_dump.exists()
            size = tmp_dump.stat().st_size if exists else 0
            print(f"  hprof file: exists={exists} size={size} bytes")
            if not exists or size < 1000:
                failures += 1
                print("FAIL: heap_dump file missing or too small")
            else:
                print("PASS: heap_dump file written and non-trivial")
                try: tmp_dump.unlink()
                except OSError: pass

        step("continue_all", lambda: client.call("continue_all", {}))
        step("detach", lambda: client.call("detach", {}))

    finally:
        try: bridge.stdin.close()
        except Exception: pass
        try: bridge.wait(timeout=5)
        except Exception: bridge.kill()
        try: target.kill()
        except Exception: pass

    print(f"\n=== summary: {failures} failure(s) ===")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
