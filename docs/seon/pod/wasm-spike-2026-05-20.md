---
type: research
status: active
tags: [research, pod, wasm, architecture]
---

# WASM-Tauri pod — spike report (2026-05-20)

## TL;DR

**Recommend:** proceed with **wasm-rquickjs + wasmtime + Tauri**. The
runtime is mature enough (3046/6435 Node.js compat tests pass, datahike-cljs
dependencies all covered), the dev loop is workable but requires a build
orchestrator, and there's an archived prototype (Rust pod host with
wasmtime+bindgen!, build orchestrator script, MCP-bridge binary) that
can be mined for working patterns when implementing the seon-side
versions.

**Pre-alpha blockers identified:** one engineering question (does
`cljs.js` run cleanly under QuickJS) needs a live smoke test before
committing the full build-out. Two product UX choices on capability
prompts.

**Estimate to alpha:** 4–6 weeks of full focus once the cljs.js smoke
passes, assuming no QuickJS-incompat surprises.

---

## EdgeJS vs wasm-rquickjs — what's the lineage?

Both clones exist under `~/src/orchestro.ai/reference/`. Quick history:

- **EdgeJS** (`wasmerio/edgejs`, HEAD 2026-05-10) — Wasmer's CLI tool that
  runs any JS engine (V8, JavaScriptCore, QuickJS) with optional WASM
  sandboxing via `--safe` mode. Spec-05's **prior** direction (before
  2026-05-19 revision).
- **wasm-rquickjs** (`golemcloud/wasm-rquickjs`, HEAD 2026-05-13) — Golem
  Cloud's library/CLI that generates a Rust crate wrapping a JS module
  via QuickJS + WASM Component Model + WIT. Spec-05's **current** direction.

Spec-05 §0 explicitly: *"this spec supersedes its EdgeJS-via-Wasmer-CLI
direction with wasm-rquickjs-via-wasmtime-Rust-crate."* The switch
happened because:

1. wasm-rquickjs produces real **WASM Components** with **WIT-typed
   imports/exports** — the boundary is structurally enforced.
2. wasm-rquickjs runs under stock wasmtime (Apache-2.0 + LLVM exception),
   no Wasmer runtime dependency.
3. The Component Model means the host (our Tauri Rust process) decides
   exactly what `wasi:*` capabilities to grant. EdgeJS's `--safe` mode is
   coarser-grained.

**Use wasm-rquickjs. EdgeJS is the abandoned branch.**

---

## wasm-rquickjs maturity (data, not vibes)

### Project state

- Workspace version `0.0.0` — pre-1.0, but actively developed.
- HEAD pinned to wasmtime v45 (PR #103, 2026-05-13).
- Apache-2.0 licensed.
- Has its own `AGENTS.md`, `README.md` (49 KB), 386-line library compat
  tracker, and a Node.js test-suite harness that vendors 5300+ upstream
  Node.js tests.

### Node.js test compat report (2026-03-20)

Run against the wasm-rquickjs runtime:

| Result | Count | % |
|---|---|---|
| ✅ PASS | 3046 | 47.3% |
| ⏭️ SKIP | 2062 | 32.0% |
| 🚫 IMPOSSIBLE | 1162 | 18.1% (no fork/sockets/native modules) |
| ❌ FAIL | 108 | 1.7% |
| 💥 ERROR | 57 | 0.9% |

Real pass rate ignoring "impossible-by-design" tests: **54.4%**.

### Modules that matter for seon (excerpted from `tests/node_compat/report.md`)

| Module | Pass | % | Relevance |
|---|---|---|---|
| `fs` | 364/407 | 89% | datahike-cljs via konserve file backend |
| `path` | 16/16 | 100% | `seon.fs` sandbox normalization |
| `crypto` | 199/218 | 91% | konserve + datahike hashing |
| `events` | 59/61 | 97% | core.async + general node patterns |
| `buffer` | 164/174 | 94% | konserve + http body handling |
| `stream` | 709/746 | 95% | http body, fs streams |
| `vm` | 25/121 | 21% | cljs.js compiler — **investigate** |
| `process` | 40/89 | 45% | env vars, cwd, exit — basics work |
| `fetch` | 1/1 | 100% | DeepSeek API calls |
| `http` (client) | included in fetch tests | works | DeepSeek + outbound |
| `worker_threads` | 18/145 | 12% | Phase-2 worker-thread sandbox blocked, but we don't need it in WASM (each component IS the sandbox) |

### Compat tracker — relevant libraries

- **`@prisma/client`, `drizzle-orm`, `sequelize`, `knex`, `pg`, `mysql2`, `mssql`** all ✅ — datahike-cljs's JS deps shouldn't surprise us
- **`better-sqlite3`** ❌ — `__filename is not defined`; uses native `.node` binding. Doesn't affect us (datahike uses konserve, not sqlite directly)
- **`axios`, `superagent`** ✅ — confirms HTTPS-client path works
- **`hono`** ✅ (server framework) — our HTTP server path via `wasi:sockets` should work for Tauri WebView wiring

### API surface coverage (from README)

The wasm-rquickjs runtime ships:

- **Console**: full (via `wasi:logging`)
- **fetch + Request + Response + Headers + FormData + Blob + File**: full (via `wasi:http`)
- **node:fs**: full sync + async + streams (via `wasi:filesystem`)
- **node:fs/promises**: full
- **node:http / node:https**: client (via `wasi:http`, TLS transparent), server (via `wasi:sockets`, HTTP/1.1 only)
- **node:path**: full
- **node:process**: argv, env, cwd, platform=`'wasi'`, arch=`'wasm32'`, exit, nextTick, hrtime, memoryUsage
- **node:vm**: `runInNewContext`, `runInContext`, `Script`, `SourceTextModule` (limited)
- **node:sqlite**: opt-in feature; embedded sqlite engine
- **CJS require**: `globalThis.require` works for builtins, npm packages, and local files
- **AbortController**: present (we use it in `seon.ai.deepseek`)

### Risk: cljs.js bootstrap

The one unknown that needs a live smoke test before committing weeks of
work: **does the cljs.js runtime compiler work under QuickJS?**

cljs.js uses:
- `goog.globalEval(s)` — boils down to `Function(s)()` or `(0, eval)(s)`; both are baseline JS, should work
- `shadow.cljs.bootstrap.node` — reads bootstrap `.transit.json` + `.js` files via `fs.readFileSync`; wasm-rquickjs has full sync fs
- `goog.require` / `goog.provide` — standard Closure module shapes; pure JS, should work
- `cljs.core$macros` registration on `globalThis` — relies on cjs.eval putting symbols into the global object; QuickJS does this normally

There's no reason to expect failure, but until we run the existing
`seon.client/datahike-smoke-test!` inside a wasm-rquickjs-generated
component, it's an assumption. **First milestone**: get the smoke test
green inside `wasmtime` CLI before doing any Tauri work.

---

## Dev loop — two paths

Both paths need a build orchestrator (the archived `build-pod` script is
a starting point). The chain:

```
shadow-cljs compile  →  out/client/main.js  (CJS bundle)
                        + ESM shim main.mjs (re-exports for WIT)
                              ↓
wasm-rquickjs generate-wrapper-crate  →  pod-build/  (Rust crate)
                              ↓
cargo build --target wasm32-wasip2 --release  →  pod.wasm
                              ↓
                       [wasmtime CLI OR Tauri-embedded wasmtime]
```

That's four build steps per change. Tolerable for releases, painful for
inner-loop dev.

### Path A — developer testing (live refresh)

**Goal:** edit a `.cljs` file, see the change in the running pod within
seconds.

Three architectural choices, from simplest to most magical:

#### A1 — "JS-side hot reload" (Recommended for inner loop)

Skip WASM entirely during inner-loop dev. Use the existing
`shadow-cljs watch client` + raw Node flow we already have. Only build
WASM for release/integration tests.

**Pros:** Sub-second reload (shadow-cljs already does it). Familiar dev
loop. Doesn't pay the wasm32-wasip2 build cost on every edit.

**Cons:** Dev != production. The agent has full Node access in dev mode,
so security-sensitive code paths (capability prompts, sandbox boundaries)
won't be exercised. Need integration tests that DO build WASM to catch
regressions.

#### A2 — "WASM rebuild + wasmtime reload"

A `bin/dev-pod-wasm` watcher script:

```bash
# Pseudo-code
fswatch src/ | while read; do
  clj -M:cljs compile client \
    && wasm-rquickjs generate-wrapper-crate ... \
    && cargo build --target wasm32-wasip2 \
    && wasmtime serve --reload pod.wasm
done
```

Rough cycle time per change: 8s (CLJS compile) + 2s (crate gen) + 15-30s
(cargo build, incremental) = **~25-40 seconds** per change. Tolerable for
integration testing; too slow for tight inner-loop iteration.

#### A3 — "wasm-rquickjs in-process eval"

wasm-rquickjs supports an eval-mode where you can push new JS into a
running component without rebuilding. The agent's `cljs.js` already does
this for forms. **If** we expose the same surface for `.cljs` file
reloads (e.g. an `eval-file` WIT export that takes the recompiled CLJS
output), we get sub-second reload inside WASM.

This is a real engineering project (~1 week to get working), but it's
the closest thing to "WASM dev that doesn't suck."

**Recommendation: ship A1 for inner-loop, A2 for CI/release, A3 as a
post-alpha polish.** A1 + A2 covers 95% of dev needs; A3 only matters if
the team grows.

### Path B — REPL access (how Claude / editors connect)

**Hard constraint:** Claude Code (the dev-side agent driving the seon
codebase) MUST have live eval access against the running pod — same
way it debugs the Node pod today via `mcp__seon_cljs__eval`. Same goes
for the user / developers from their editor. **Losing live REPL access
is not acceptable;** if the WASM dev loop can't preserve it, that's a
blocker.

**But the REPL must not be a security loophole.** The whole point of
the WASM containment is that the agent's `cljs.js` eval is bounded by
the WIT-typed import surface. If we expose an "eval-anything" hatch
that's reachable from inside the pod, an LLM-emitted form can call
through it and break out. So:

- The `eval` WIT interface is exposed as an **export** the host calls
  INTO. The pod cannot call out to it. (WIT export = "host can invoke";
  WIT import = "pod can invoke". Eval-into-the-pod is an export, not an
  import.)
- The MCP-bridge binary that fronts the eval-export is a HOST process;
  it talks to the pod via the Component Model. The pod's CLJS code has
  no path to that bridge except via the eval-export itself, which the
  host gates.
- Production builds can disable / gate the eval-export (e.g. require a
  signed dev token, or compile it out entirely). Dev builds expose it
  freely so Claude + editors can poke. Same binary, different feature
  flag.

Today's V0 Node pod runs an nREPL server on port 7889 (via
`shadow-cljs nrepl`). Editors + `mcp__seon_cljs__eval` connect to that.

In WASM, the agent runs **inside the wasmtime component**. No port,
no listening socket the editor can hit. We need to expose REPL access
through a WIT-typed export AND a host-side MCP bridge that speaks the
same shape as the current Node-pod MCP.

#### B1 — "Eval-over-WIT" (the natural fit)

```wit
interface eval {
  variant eval-error {
    reader-error(string),
    runtime-error(string),
    interrupted,
  }

  record eval-result {
    eval-id:   string,
    ok:        bool,
    value-edn: option<string>,
    error:     option<eval-error>,
  }

  /// Evaluate a CLJS form in the agent's home namespace.
  eval-form: func(form: string) -> eval-result;

  /// Cancel a long-running eval (Phase 3 has real preemption — wasmtime
  /// can drop the running call frame).
  interrupt: func(eval-id: string) -> bool;
}
```

The Tauri host exposes this as a Tauri command:

```rust
#[tauri::command]
async fn seon_eval(state: State<'_, PodState>, form: String) -> Result<EvalResult> {
    state.pod.eval_form(&form).await
}
```

And the host exposes it to editors / MCP via a stdio JSON-RPC bridge.
Claude / editors connect to the MCP server, which forwards `eval-form`
to the pod via the WIT export. The archived prototype has a working
implementation of this bridge shape that we can mine for patterns.

**Pros:** Real boundary. Editor can't reach anything beyond `eval-form`,
`query`, `interrupt`, and whatever else we export.

**Cons:** Loses the "drop into nREPL and poke at internals" debugging
flow. To inspect internal pod state, need explicit WIT exports for each
inspection point. Worth the tradeoff for security; we can expose generous
`inspect-*` fns at first and tighten later.

#### B2 — "Tunneled nREPL"

Run nREPL inside the WASM pod over a WASI socket; Tauri host opens a
loopback TCP listener and proxies bytes in/out. Keeps existing tooling
working unchanged.

**Pros:** Zero changes to editor side. nREPL just works.

**Cons:** Opens a loopback port (less clean than the WIT boundary).
Requires `wasi:sockets` server side, which is supported but new.

**Recommendation: ship B1.** The WIT-typed boundary is the whole point
of the WASM containment. nREPL's "everything is exposed" model fights
the security goal. Editors talk to the host via MCP/Tauri commands;
host forwards to the pod via WIT.

---

## What "the right WIT world" looks like

A drafted WIT world for the seon pod — `fs`, `http`, `mcp`,
`capability-prompt`, `eval` are all substrate-level capability
boundaries (nothing consumer-specific):

```wit
package seon:pod@0.1.0;

interface types {
  // Eval surface
  variant eval-error { reader-error(string), runtime-error(string), interrupted, timeout }
  record eval-result { eval-id: string, ok: bool, value-edn: option<string>, error: option<eval-error> }

  // DB / query surface
  variant db-error { invalid-query(string), runtime-error(string) }
  record query-result { rows-edn: string }

  // Agent surface
  enum agent-state { idle, running }
  record agent-snapshot { agent-id: string, turn-count: u32, state: agent-state, rendered-ctx: string }
}

// Host-mediated filesystem (default-deny; host prompts user for paths
// outside preopened allowlist).
interface fs {
  variant fs-error { not-found, permission-denied, io(string), out-of-scope }
  read-file:  func(path: string) -> result<list<u8>, fs-error>
  write-file: func(path: string, data: list<u8>) -> result<_, fs-error>
  list-dir:   func(path: string) -> result<list<string>, fs-error>
  exists:     func(path: string) -> bool
}

// Outbound HTTP with allowlist (host enforces; pod never sees a raw
// socket). DeepSeek / claude.ai / openai endpoints get pre-allowlisted
// per consumer config.
interface http {
  variant http-error { blocked-host(string), io(string), timeout }
  record http-request { method: string, url: string, headers: list<tuple<string, string>>, body: option<list<u8>> }
  record http-response { status: u16, headers: list<tuple<string, string>>, body: list<u8> }
  request: func(req: http-request) -> result<http-response, http-error>
}

// MCP-server spawning (host runs the child process; pod just sends/recvs
// JSON-RPC envelopes via a resource handle)
interface mcp {
  variant mcp-error { spawn-failed(string), closed, io(string), blocked }
  record mcp-spec { name: string, command: string, args: list<string>, env: list<tuple<string, string>> }
  resource handle {
    constructor(spec: mcp-spec)
    send:  func(request: string) -> result<string, mcp-error>
    close: func()
  }
}

// Capability prompts — host shows native dialog; pod waits async
interface capability-prompt {
  enum decision { allow, deny, allow-once, allow-forever }
  record prompt { title: string, body: string, capability: string }
  ask: func(p: prompt) -> decision
}

// What the agent's "let me try X" loop wants to consult
interface eval {
  use types.{eval-result, eval-error}
  eval-form:  func(form: string) -> eval-result
  interrupt:  func(eval-id: string) -> bool
}

world pod {
  // Imports (granted by host)
  import fs
  import http
  import mcp
  import capability-prompt

  // Wasi imports needed regardless
  import wasi:io/streams
  import wasi:clocks/wall-clock
  import wasi:clocks/monotonic-clock
  import wasi:random/random
  import wasi:logging/logging
  import wasi:filesystem/preopens  // for preopened dirs (e.g. ~/.seon/db)

  // Exports (host calls)
  export eval
  export get-ui-port: func() -> u16    // pod's loopback HTTP for Tauri WebView
  export shutdown: func()
}
```

Consumer overlays (product-specific UI, integration with a specific
LLM-vendor's APIs, custom render slots) live in their own repos as
either a higher-level WIT or as CLJS modules the pod imports — the
substrate's WIT contract is **fully owned by seon**.

---

## Smallest-demonstrable-milestone path

Sequence, each step is a real green/red boundary:

1. **`cljs.js` smoke under wasm-rquickjs.** Take the existing
   `seon.client/datahike-smoke-test!`, wrap it in an ESM shim, generate
   a wasm-rquickjs crate (minimal WIT: just `export smoke: func() -> string`),
   compile to `wasm32-wasip2`, run under `wasmtime` CLI, expect
   "PASS 6 datoms". **Go/no-go for the whole approach.** Estimated time:
   1 day (if it works) — N days (if it doesn't).

2. **Agent loop under wasm-rquickjs.** Wrap `seon.client/start-agent!` +
   the stub LLM. Expose `eval-form` + `inject-message` via WIT. Drive a
   couple of turns from `wasmtime serve` or a smoke harness. ~3 days.

3. **WIT-bounded capability surface (fs + http).** Replace the inside-the-pod
   `(js/require "node:fs")` paths in `seon.fs` and `seon.ai.deepseek`
   with calls to the WIT-imported `fs` / `http` interfaces. Default-deny
   allowlist enforced at the **Rust host** layer, not in CLJS. ~1 week.

4. **Tauri shell with embedded wasmtime.** Revive
   `archive/lane-b-wasm-skeleton/src-tauri/src/pod.rs` (generalized into
   seon). Tauri opens a WebView pointing at the pod's loopback HTTP
   (from step 2). Capability-prompt UX shows a native macOS dialog. ~1 week.

5. **MCP-bridge binary** (`mcp-server-seon`). Editors + Claude connect
   via stdio JSON-RPC; forwards to the running Tauri host via a local
   IPC. Pattern exists in the archived prototype — start from there. ~3 days.

6. **Alpha-blocker polish.** Capability-prompt UX (remember decisions,
   review pane), failure-mode UX (timeout → user sees what happened),
   signed/notarized `.app` for distribution. ~1 week.

Total optimistic: **4 weeks**. Realistic with one or two QuickJS-incompat
surprises: **6–8 weeks**.

---

## Things this report deliberately did NOT decide

- **Where the pod build lives in the seon tree.** A new `pod/` top-level
  directory? Inside `src/seon/pod/`? A separate repo? Punt to first commit.
- **Whether the Tauri shell ships in seon or in a separate "seon-desktop"
  distribution.** Seon is the substrate library; the desktop wrapper might
  be a different artifact. Worth discussing.
- **Persistence story in WASM.** `wasi:filesystem/preopens` means the pod
  sees `~/.seon/db/` as a mounted directory. datahike-cljs's file backend
  writes there directly. But the pod's view of "the user's work folder"
  is more interesting — needs an explicit grant via `capability-prompt`.
- **Hot-swap of the WASM component itself.** When a new pod build comes
  in, do we restart the Tauri WebView pointing at it, or hot-swap
  in-place? Probably restart for V1; hot-swap is V1.5+.

---

## Recommended first commit

Once user okays this report, start with:

- `bin/build-pod` script (revived + generalized from archive)
- `src-wit/seon-pod.wit` with the WIT world above
- `placeholder.mjs` to validate the chain without a CLJS bundle
- Smoke test target: `bin/build-pod --placeholder && wasmtime run pod.wasm`
- A single doc note in `docs/seon/pod/` explaining the new directory

Then the milestone-1 work (cljs.js smoke). One commit per milestone after.
