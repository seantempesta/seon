---
type: reference
status: active
tags: [reference, cljs, shadow-cljs, mcp, runbook]
---

# CLJS dev loop — first 5 minutes

How to bring the V0 CLJS pod runtime up and verify it via MCP eval. Aimed at a fresh agent or returning Sean after a context reset.

Authoritative architecture: [`consumer-spec/spec-01-webassembly-agents.md`](../../specifications/spec-01-webassembly-agents.md) — start at §6.10 for current state, §7 for the V0-B-2..V0-B-9 queue.

## TL;DR — three commands

```bash
# Terminal 1 (this seon repo): shadow-cljs watcher
cd /Users/you/src/seon
clj -M:cljs watch client

# Terminal 2 (same seon repo): Node host loads the bundle
node out/client/main.js

# Editor / Claude MCP: eval against the running runtime
mcp__seon_cljs__eval { code: "@seon.client/!state" }
```

After `clj -M:cljs watch client` reports `[:client] Build completed`, the watcher writes `.shadow-cljs/nrepl.port` (pinned to `:7889`). `node out/client/main.js` should print `[client] datahike-cljs smoke test PASS — 6 datoms` on boot. Then `mcp__seon_cljs__eval` reaches the runtime via shadow-cljs's nREPL piggyback.

## Verify in detail

### 1. The watcher is up

```clojure
;; Via mcp__seon_cljs__runtime_status — no args:
shadow nREPL port: 7889
builds: [{:build :client, :runtimes 1}]
mcp sessions: 0
```

If `runtime_status` returns `<no watcher>`, the watcher isn't running. Restart Terminal 1.

### 2. The datahike-cljs smoke is alive

```clojure
;; Pass session_id from a fresh mcp__seon_cljs__create_session {build: ":client"}
mcp__seon_cljs__eval {
  code: "(require '[cljs.core.async :as a])
         (a/go (println (a/<! (seon.client/datahike-smoke-test!))))"
  session_id: "<your sid>"
}

;; Expected stdout:
;; [client] datahike-cljs smoke test ...
;; {:rows #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]}, :status :pass, :datoms 6}
```

### 3. The patches are loaded

```clojure
mcp__seon_cljs__eval { code: "@seon.client/!state" session_id: "<sid>" }
;; Expected:
;; => {:boot-at "...", :reload-count <N>, :heartbeat-id #object [Timeout ...]}
```

The presence of a non-nil `:heartbeat-id` confirms `start-heartbeat!` ran. The patches' state lives in `seon.client/patches-applied?` (private `defonce`); checking `(boolean ...)` from inside the ns confirms.

## Hot-reload

Edit any `.cljs` file in `src/seon/`, save. The watcher recompiles within ~1s; the running runtime gets a websocket message; `^:dev/before-load` fires (stops the heartbeat in `seon.client`), namespaces re-load, `^:dev/after-load` fires (increments `:reload-count`, restarts the heartbeat). `defonce` state survives.

```clojure
;; Verify reload landed:
mcp__seon_cljs__eval { code: "(:reload-count @seon.client/!state)" session_id: "<sid>" }
;; => 1, 2, 3, ... after each save
```

## Stopping

```bash
# Find and kill the processes (each survives Claude Code session boundaries)
pkill -f "clj.*shadow.cljs.devtools.cli watch"
pkill -f "node out/client/main.js"

# Or, if you started them via Claude background tasks: TaskStop them.
```

## Common failure modes

**`no shadow-cljs watcher running (no .shadow-cljs/nrepl.port)`** — watcher isn't up. Start Terminal 1.

**`Use of undeclared Var seon.client/foo`** — function doesn't exist (or was renamed). Check `src/seon/client.cljs` for the actual name.

**`InternalError: stack overflow`** — would only show up under Wasmer-EdgeJS (V0.5+). Doesn't apply in raw Node V0.

**The eval works but returns `=> nil` for go-block calls** — that's expected. `go` returns a channel immediately; the println side-effect is what carries the actual value. Look in stdout for the result, not the eval's `=>`.

**MCP session got stale after restarting the watcher** — call `mcp__seon_cljs__create_session {build: ":client"}` again to get a fresh sid. Old sids referenced a runtime that's gone.

## Where next

After the smoke test is green, the V0 work queue per spec-01 §7 is:

- **V0-B-2** — write `src/seon/db.cljs` (real `seon.db` API for CLJS).
- **V0-B-3** — wire konserve `:tiered :memory + :sqlite-file`.
- **V0-B-4** — `src/seon/ai.cljs` + `src/seon/ai/deepseek.cljs` delegating to clj-llm.
- **V0-B-5..V0-B-9** — trigger dispatch, session lifecycle, end-to-end LLM loop, define-fn, snapshot/restore.

Lane coordination for the `.cljs`-alongside-`.clj` migration: [`consumer-docs/2026-05-16-cljc-migration-plan.md`](../../docs/2026-05-16-cljc-migration-plan.md).
