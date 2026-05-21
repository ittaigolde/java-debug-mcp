# Contributing to java-debug-mcp

Thanks for poking at this! A few notes:

## Build & test

Requires JDK 21+.

```
gradle shadowJar           # build the fat jar
gradle test                # run JUnit tests
python examples/smoke_test.py             # end-to-end stdio MCP
python examples/ui_smoke_test.py          # web UI + SSE
python examples/diagnostics_smoke_test.py # thread_dump / heap_dump / etc.
```

All three smoke tests should report `0 failure(s)` before you push.

## Branch & PR

- Target branch: `main`.
- Keep PRs focused — one feature or one fix per PR.
- The Java code style is "match what's there" — 4 spaces, no tabs.

## License

This project is MIT licensed (see `LICENSE`). By contributing you agree your
work is offered under the same license.

## Status

Alpha. APIs (tool names, schemas, SSE event shapes) may change between versions
until v1.0.0.
