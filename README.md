# java-debug-mcp

An MCP server that exposes a Java debugger (JDI/JDWP) so command-line LLM clients
like Claude CLI can attach to, drive, and narrate debugging sessions on running
Java processes — and dump the entire session to Markdown for documentation and
post-mortem analysis.

**Status:** alpha. Not affiliated with Anthropic. License: MIT.

The MCP tool name is `debug-bridge` (because that's the original internal name
and the jar is still `debug-bridge-0.1.0.jar`). The repository is published as
`java-debug-mcp` to make the purpose obvious to people who haven't seen it.

## What it does

- Attach to a running JVM by host:port (JDWP socket).
- Set line breakpoints, exception breakpoints, field watchpoints.
- Step over/into/out, continue, pause/resume.
- Inspect threads, stack frames, locals, object fields, array slices.
- Invoke methods on live objects in the paused target.
- Evaluate small Java expressions (field chains, method calls, arithmetic,
  comparisons, logical ops; not lambdas/streams-with-lambdas/ternary).
- Receive breakpoint hits via blocking `wait_for_event` / fused
  `continue_and_wait` / `step_*_and_wait` tools.
- Append every tool call and event to a JSONL session log, and render it to
  Markdown with `dump_session`.
- **Optional read-only web UI** (`ui_start`): live view of threads, frames,
  source, locals, breakpoints, events, and Claude's narrated notes. Three-pane
  single page, served from the same process.
- **Diagnostics**: `thread_dump` (jstack-style), `heap_dump` (real `.hprof`
  the target writes via `HotSpotDiagnosticMXBean`), `list_instances`,
  `class_histogram` (single fast round-trip via `vm.instanceCounts`).
- **User-gated checkpoints**: `await_user_ready` blocks Claude until you click
  Start in the UI — useful for "wait until I've opened the browser tab" or
  "pause before a destructive action".

## Build

Requires JDK 21+ (Temurin recommended; any OpenJDK works — JDI ships in the
`jdk.jdi` module).

```
gradle shadowJar
```

Produces `build/libs/debug-bridge-0.1.0.jar` (single fat jar, ~6 MB).

## Configure Claude CLI

Add to `~/.claude.json` under `mcpServers`. Use an **absolute path** to the
`java` executable — Claude CLI doesn't always inherit your shell's PATH:

```json
"debug-bridge": {
  "type": "stdio",
  "command": "C:/path/to/your/jdk-21/bin/java.exe",
  "args": [
    "--add-modules", "jdk.jdi",
    "-jar",
    "/path/to/this/repo/build/libs/debug-bridge-0.1.0.jar"
  ],
  "env": {}
}
```

`--add-modules jdk.jdi` is required — JDI is not in the default module set.

To start the UI automatically at launch, add `-Ddebug-bridge.ui.port=auto`
before `-jar`. Without it, Claude can still call `ui_start` at runtime.

## Run the demo

In one terminal, launch the demo target with JDWP enabled:

```
cd examples
javac HelloDebug.java
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -cp . HelloDebug
```

In Claude CLI, ask the model to drive the debugger:

> Use the debug-bridge MCP to attach to localhost:5005, start the UI, set a
> breakpoint at HelloDebug.java line 28, continue and wait, then inspect the
> locals when it hits. After the hit, dump the session to `./session.md`.

There are three smoke tests in `examples/` you can run directly to verify the
build:

- `python examples/smoke_test.py` — basic attach + breakpoints + evaluate.
- `python examples/ui_smoke_test.py` — UI + SSE + source rendering.
- `python examples/diagnostics_smoke_test.py` — thread_dump, heap_dump,
  list_instances, class_histogram.

## Important: pausing live services

When a breakpoint hits with `suspend_policy="all"` (or you call `pause_all()`),
every thread in the target stops, including ones serving network requests,
heartbeats, and database connections. Wall-clock time keeps running:

- Network sockets time out.
- Database connections get killed.
- Kafka consumers drop out of their consumer group.
- Container liveness/readiness probes can fail.
- `System.currentTimeMillis()` jumps forward when you resume.

For long-running services, prefer `suspend_policy="event_thread"` (the default) —
only the hitting thread pauses, others keep flowing. Resume promptly. Don't use
this against production unless you understand the above and are sure.

## Expression evaluator scope

The `evaluate` tool runs a small recursive-descent evaluator against a suspended
frame. Supported:

- Identifiers (resolved: local → `this.field` → static field → class FQN)
- Field chains: `a.b.c`
- Method calls: `obj.foo(x, 42)`, `Foo.bar()`, `com.example.Foo.baz(1)`
- Literals: int/long/double/float, string, char, `true`/`false`/`null`
- Arithmetic: `+ - * / %` (with Java numeric promotion)
- Comparisons: `== != < > <= >=`
- Logical: `&& || !`
- Parens, array index `arr[i]`, `arr.length`, `this`

Not supported (use `invoke_method` + `inspect_object` instead):

- Lambdas, method references, anonymous classes
- Stream pipelines with lambdas (`.stream().filter(u -> ...)`)
- Ternary `? :`, assignment, `instanceof`, casts, `new`, generics syntax

When an unsupported construct is parsed, you get a clear `unsupported_syntax`
error explaining what to use instead.

## Session log

Every tool call, debug event, and Claude note is appended to
`~/.debug-bridge/sessions/session-YYYYMMDD-HHMMSS.jsonl` as one JSON object per
line. `dump_session(path)` renders that to a human-readable Markdown file with
a timeline of tool calls, events, and notes — useful for retraces, post-mortem
write-ups, and pasting into issue threads.

## Tool list (quick reference)

| Group | Tools |
|---|---|
| Lifecycle | `attach`, `detach`, `status` |
| Discovery | `list_threads`, `list_classes`, `class_info` |
| Breakpoints | `set_breakpoint`, `set_exception_breakpoint`, `set_watchpoint`, `list_breakpoints`, `remove_breakpoint` |
| Execution | `continue_all`, `continue_thread`, `pause_all`, `step_over`, `step_into`, `step_out`, `continue_and_wait`, `step_over_and_wait`, `step_into_and_wait`, `step_out_and_wait` |
| Inspection | `frames`, `locals`, `inspect_object`, `read_array` |
| Invocation | `invoke_method` |
| Expressions | `evaluate` |
| Events | `wait_for_event`, `list_recent_events` |
| Diagnostics | `thread_dump`, `heap_dump`, `list_instances`, `class_histogram` |
| UI | `ui_start`, `ui_stop`, `set_sourcepath`, `await_user_ready` |
| Notes | `note` (Claude narrates plan/finding/decision into the UI + session log) |
| Session | `session_status`, `dump_session` |

## License

MIT. See `LICENSE`.
