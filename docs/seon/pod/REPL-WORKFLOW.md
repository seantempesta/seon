---
type: reference
status: active
tags: [reference, pod, cljs, mcp]
---

# CLJS pod — REPL workflow (V0 / pre-WASM)

How to drive the running CLJS pod from an editor or MCP client. Currently
this means **shadow-cljs nREPL piggyback** to the long-running Node
process. When Phase 3 lands (WASM-Tauri), this changes — see
[[wasm-spike-2026-05-20]] §"REPL access".

## V0 — Node + shadow-cljs

### Boot

```sh
cd /Users/sean/src/seon

# Terminal 1 — watcher (compiles + writes .shadow-cljs/nrepl.port)
clj -M:cljs watch client

# Terminal 2 — Node host loads the compiled bundle
node out/client/main.js
```

The watcher pins shadow nREPL to **`:7889`**. The Node host writes its
bound HTTP port to `tmp/seon-port` (default `7890`; override via
`SEON_PORT`).

### MCP eval against the running pod

The active CLJS MCP server is registered as `seon_cljs`:

```clojure
;; Smoke — proves the runtime is alive
mcp__seon_cljs__eval {
  code: "(require '[cljs.core.async :as a])
         (a/go (println (a/<! (seon.client/datahike-smoke-test!))))"
}
;; => {:rows #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]}, :status :pass, :datoms 6}

;; Inspect process-lifetime state
mcp__seon_cljs__eval { code: "@seon.client/!state" }
;; => {:boot-at "...", :reload-count <N>, :heartbeat-id #object [Timeout ...]}

;; Configure the fs sandbox (default is deny-all)
mcp__seon_cljs__eval { code: "(seon.fs/configure!
                                  {:seon.fs/allowed-roots [\"/Users/me/work\"]
                                   :seon.fs/read-only? false})" }
```

### Hot reload

Edit any `.cljs` in `src/seon/`, save. shadow-cljs recompiles in ~1s;
the running runtime gets a websocket message; `^:dev/before-load`
cleanup runs (heartbeat, broadcast watcher, agent kick listener);
namespaces re-load; `^:dev/after-load` rewires.

`defonce` state survives:
`!agent-conn`, `!compile-state`, `seon.schema/*schemas`, `seon.fs/!config`,
`seon.eval/!timeout-ms`, `seon.eval/timeout-sentinel`,
`seon.web.serve/!server` + `!sse-connections`, etc.

### Stopping

```sh
pkill -f "clj.*shadow.cljs.devtools.cli watch"
pkill -f "node out/client/main.js"
```

### Common failure modes

- **`no shadow-cljs watcher running`** — watcher isn't up. Restart Terminal 1.
- **`Cannot read properties of null (reading 'findInternedVar')`** — bootstrap CLJS didn't load. Recompile `:bootstrap` (`clj -M:cljs compile bootstrap`).
- **`EADDRINUSE 127.0.0.1:7890`** — another pod still listening. `lsof -ti :7890 | xargs kill`, or set `SEON_PORT=0` for ephemeral.
- **Eval returns `nil` for `(go ...)`** — `go` returns a channel immediately; the value lands in stdout from the `println` side-effect. Look in the Node host's stderr/stdout, not the eval's `=>`.

## What changes when Phase 3 (WASM-Tauri) ships

The CLJS pod will run inside a `wasm32-wasip2` Component embedded in
wasmtime — there's no Node process to reach via nREPL. Editor/MCP access
goes through a **WIT-typed `eval` interface**, surfaced by an
`mcp-server-seon` binary that bridges stdio JSON-RPC to the running
Tauri host's IPC.

```text
[editor] ←stdio→ [mcp-server-seon] ←IPC→ [Tauri host] ←WIT→ [wasm pod]
                                                              │
                                                              └ eval-form
                                                                interrupt
                                                                query
                                                                inspect-agent
```

No raw nREPL — that contradicts the WASM containment story. The
WIT-typed `eval` surface is intentionally narrower than nREPL, and the
host owns the capability boundary. See `pod-host/wasm-tauri/` (Rust
workspace) for the implementation in progress.
