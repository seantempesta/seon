---
type: issue
status: resolved
severity: blocker
tags: [issue, tooling, repl, architecture]
---

# Keep the REPL bridge independent of application source

## Problem

The stdio MCP server loaded Seon application namespaces before it could answer
`initialize`. Any application classpath or compilation defect therefore took
down `eval_clj`, even when a live JVM had already published a working io-prepl.

The REPL bridge is process tooling below the application. It must keep serving
JSON-RPC and discover advertised REPL endpoints regardless of the state of
`src/` and `src-old/`.

## Evidence

Before the fix, sending `initialize` to `bin/mcp-server-cljs` failed before any
JSON-RPC response. The load chain was:

```text
seon.dev.mcp
→ seon.dev.config
→ seon.config.resolve
→ seon.content-hash
→ seon.schema
→ datahike.api (unavailable to Babashka)
```

`src/seon/schema.cljc:20` raised `java.io.FileNotFoundException` for
`datahike/api`.

## Owner

`bin/mcp-server-cljs` and `script/seon/dev/mcp.clj` own the source-independent
stdio bridge. Cluster processes own `data/clusters/<name>/prepl.edn`.

## Acceptance

- The launcher exposes only `script/` to Babashka.
- The server namespace requires zero `seon.*` namespaces.
- `initialize` and `tools/list` work with no cluster or readable source tree.
- `eval_clj` re-reads advertisements per call, validates `(pid,
  start-instant)` liveness, and speaks io-prepl directly.
- Missing, unreadable, invalid, or stale advertisements return guiding error
  values without terminating the server.
- A cluster started or restored after MCP startup is discovered without an MCP
  restart.
- Existing tool names and input schemas remain available. CLJS-dependent tools
  return the owner-ruling error value while the CLJS build is off.

## Resolution

Resolved by the path-limited commit
`fix(tooling): decouple MCP REPL bridge from application source`.

The launcher now uses `bb --classpath script`; the bridge requires only
Cheshire plus Clojure EDN, I/O, and string libraries. It derives fresh
endpoints from advertisement files on every call, retains the cheap old-writer
port-file fallback, and opens io-prepl sockets directly.

Recurring proof:

- `bin/test seon.dev.mcp-bridge-test` ran 2 tests and 5 assertions with zero
  failures. It computes the namespace's require targets and launches Babashka
  with only `script/` on its classpath.

Live stdio proof from `bb --classpath script tmp/mcp-probe.clj`:

- `initialize` returned protocol `2024-11-05` and server `seon` version
  `0.3.0`; `tools/list` returned the seven retained tool names.
- `eval_clj` evaluated `(+ 1 2)` on `default` and returned `3`.
- `runtime_status` reported `default state=alive`, PID `78884`, prepl
  `127.0.0.1:52701`, and `http://127.0.0.1:7994`.
- With `data/clusters/default/prepl.edn` temporarily renamed, `eval_clj`
  returned `:repl-unavailable` and the `bin/repl default` remedy; the same
  server then answered `tools/list` with all seven tools.
- After restoring the advertisement, the same server rediscovered it and
  `eval_clj` returned `3` again.
