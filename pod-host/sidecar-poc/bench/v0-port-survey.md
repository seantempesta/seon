---
type: research
status: active
tags: [research, agent, database]
---

# V0 → sidecar port survey

Date: 2026-05-25
Agent: seon-agent (autonomous porting session)
Branch: feature/agent-runtime
Scope: package V0 substrate (`src/seon/*.cljs` + `*.cljc`) into the sidecar-poc wasm guest WITHOUT modifying V0 source.

## TL;DR

- **The entire V0 substrate compiles cleanly into the sidecar guest build.** 11 namespaces required transitively (seon.{agent, eval, render, render.default, log, error.instrument, code, parse, platform, schema, analyzer-info}) and shadow-cljs reports 0 errors, 0 warnings from V0 code itself (11 warnings are pre-existing — 7 from konserve, 1 from datahike-cljs, 3 from `wit.cljs` reflection).
- **Approach: source-path overlay (Option α).** Two new files at `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/{db,repl}.cljs` shadow the V0 namespaces. Activated via a new `:cljs-sidecar` deps alias that prepends `src-overlay/` to the shadow-cljs source-paths. **Zero modifications to `src/seon/*`.**
- **Bundle size:** 3.6 MB compiled (`out/v0-probe/main.js`, `:simple` optimizations, 8400 LOC). Down from 8.2 MB when datahike-cljs leaked in through `seon.repl`. The leak was plugged by also overlaying `seon.repl`.
- **What's not yet exercised:** the bundle compiles but hasn't been packaged into a wasm component or invoked. Runtime will hit blockers: `node:async_hooks` (V0's AsyncLocalStorage — overlay replaces with dynamic Var binding), `node:fs` (seon.eval's bootstrap cache loader), `js/fetch` (seon.ai.deepseek — works in wasm-rquickjs with node-http feature), `node:http` (seon.web.* — not required by the probe).
- **Recommended next step:** wire the v0-probe build into a wasm component (lift the build-sidecar-agent script's `:sidecar-agent` chain) and observe what the first `seon.agent/create!` call hits.

## Approach (α — overlay)

**Option α (source-path overlay)** was chosen and **worked first try.** Shadow-cljs honors source-paths order; placing `pod-host/sidecar-poc/guest-cljs/src-overlay/` AHEAD of `src/` in the global `:source-paths` makes the overlay copies of `seon.db` and `seon.repl` win the namespace resolution. Verified by inspecting compiled output: `out/v0-probe/main.js` contains the overlay's vars (`*tx-context*`, `*agent-id*`, the stub `assert-preconditions!`) and lacks V0's `form-head`/`resolve-malli-form`/etc.

**The overlay is alias-gated.** Empirically (verified by rebuilding `:client` without `:cljs-sidecar` and inspecting compiled output for `node:async_hooks` references): the overlay only wins when `:cljs-sidecar` is on the command line. Without it, shadow-cljs picks the V0 `seon.db.cljs` from `src/seon/`. The mechanism appears to be classpath order: deps.edn `:extra-paths` from active aliases are prepended to the JVM classpath, and shadow's namespace resolver follows classpath order for ambiguous namespaces. The shadow-cljs.edn `:source-paths` entry for `src-overlay` ensures shadow SCANS that directory; the alias's `:extra-paths` is what makes its resources take precedence at resolution time.

Verification:
- `clj -M:cljs release client` → `out/client/main.js` contains 2 `AsyncLocalStorage` references (V0's seon.db) and 32 `datahike.api` references — V0 SEON.DB WINS.
- `clj -M:cljs:cljs-sidecar release v0-probe` → `out/v0-probe/main.js` contains 0 `datahike.api` references and includes the overlay's `current_tx_context` / `*tx-context*` — OVERLAY WINS.

Both builds compile cleanly. The V0 pod is not at risk from the overlay's presence as long as `:cljs-sidecar` is NOT activated for `:client` rebuilds.

Option β (copy V0 files into guest-cljs/) was not necessary.

## Namespace classification table

| Namespace | LOC | Category | Status | Notes |
|---|---|---|---|---|
| `seon.schema` (.cljc) | 234 | pure Malli | ✅ compiled | zero deps; portable as-is |
| `seon.error` (.cljs) | 69 | pure CLJS | ✅ compiled | walks ex-info chain; no Node APIs |
| `seon.parse` (.cljc) | 191 | pure CLJC | ✅ compiled | rewrite-clj-based reader |
| `seon.code` (.cljc) | 318 | pure CLJC | ✅ compiled | form analysis helpers |
| `seon.platform` (.cljs) | 39 | host-detect | ✅ compiled | sniffs `process.versions.node` |
| `seon.log` (.cljs) | 158 | wraps seon.db | ✅ compiled | console + db.transact!; works through overlay |
| `seon.instrument` (.cljc) | 128 | malli instrument | ✅ compiled | transitively required |
| `seon.error.instrument` (.cljc) | 308 | malli instrument | ✅ compiled | transitively required |
| `seon.analyzer-info` (.cljs) | 170 | reads compile-state | ✅ compiled | pure CLJS over the analyzer atom |
| `seon.ui.components` (.cljc) | 239 | UI primitives | ✅ compiled | pure data; required by seon.render.default |
| `seon.ui.markdown` (.cljs) | 192 | markdown→hiccup | ✅ compiled | pure CLJS |
| `seon.db` (.cljs) | 1279 → **267** | overlay | 🟢 OVERLAY | overlay at `src-overlay/seon/db.cljs` (267 LOC) replaces V0's 1279 LOC; surface is V0-compatible, internals route through `sidecar-poc.datahike` |
| `seon.repl` (.cljs) | 131 → **47** | overlay | 🟢 OVERLAY | overlay at `src-overlay/seon/repl.cljs`; drops the `datahike.api` require + `ensure-conn!` (V0's `dev-init!` opens an in-memory datahike conn; under sidecar, the conn is JVM-owned) |
| `seon.eval` (.cljs) | 924 → **overlay 924** | overlay | 🟢 OVERLAY | overlay at `src-overlay/seon/eval.cljs` — identical to V0 except `warnings-als` (AsyncLocalStorage) swapped for a `^:dynamic *warnings-bucket*` Var. Bundle no longer references `node:async_hooks`. `js/require("fs")` for bootstrap caches still present — needs WASI preopen at runtime |
| `seon.render` (.cljs) | 110 | resolver | ✅ compiled | pulls seon.eval transitively |
| `seon.render.default` (.cljs) | 485 | section fns | ✅ compiled | renders agent ctx; reads via seon.db (overlay) |
| `seon.agent` (.cljs) | 1247 | the big one | ✅ compiled | all schemas, run-turn!, run-agentic-loop!, etc. — all compiled |
| `seon.ai.deepseek` (.cljs) | 211 | LLM HTTP | 🟡 not in probe | uses `js/fetch` — works in wasm-rquickjs with node-http feature, NOT YET ADDED |
| `seon.fs` (.cljs) | 450 | local FS | 🔴 blocked | requires `node:fs`; wasm32-wasip2 has wasi:filesystem but no node:fs shim |
| `seon.client` (.cljs) | 678 | http server + boot | 🔴 blocked | requires `node:http` + `datahike.api` + cljs-http |
| `seon.web.serve` (.cljs) | 422 | http server | 🔴 blocked | requires `node:http`, `node:fs` |
| `seon.web.broadcast` (.cljs) | 194 | sse fanout | 🟡 not tried | requires seon.web.serve transitively |
| `seon.web.sse` (.cljs) | 59 | sse format | 🟡 not tried | requires seon.web.serve transitively |
| `seon.test.runner` (.cljs) | 383 | test capture | 🟡 not in probe | requires seon.db — should work through overlay |

Total V0 substrate-track LOC included in the probe build: ~5,900 (excluding overlays).

## Async story (confirmed 2026-05-25)

wasm-rquickjs supports **native ES2017 `async`/`await` over JS Promises end-to-end** — empirically verified in the Phase C agent guest, which uses `(js/Promise.…)` + an `^:async` listener loop driven by setTimeout in `sidecar-poc.datahike` / `sidecar-poc.agent`. WIT-bound host imports compile to async-await JS call sites, and Promise chains traverse cleanly between wasm fibers and the wstd runtime.

What wasm-rquickjs does **NOT** ship is `node:async_hooks`. That is a Node-specific runtime API for tracking "current execution context" across the libuv event loop — including across promise/await boundaries — without explicit threading. Implementing it requires deep hooks into the libuv async resource tree, which has no equivalent in wasi:p2 or wasm-rquickjs's wstd-based event loop. The `node-async` and `node-http` cargo features of wasm-rquickjs enable Promise-based async I/O; they do **not** synthesize an `AsyncLocalStorage` shim.

Architecturally, this is fine for the sidecar guest: each `wasmtime::Store<GuestStore>` is a single QuickJS fiber by design (Phase 4 architectural notes), so there is exactly one "current context" at any time. CLJS `^:dynamic` Vars + `binding` work as the per-fiber substitute. V0 originally moved off `binding` precisely because its multi-agent JVM Node pod ran many concurrent agents in one event loop; under one wasm Store there is no concurrency for a Var to clobber across.

Conclusion: keep cargo features as-is. Replace each `AsyncLocalStorage`-based context store in V0 with a `^:dynamic` Var overlay. The seon.db overlay validates the pattern; seon.eval is the second site.

## Overlays written

**`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` (267 LOC)** — sidecar-flavored `seon.db`. Public surface:
- `*conn*` dynamic Var (matches V0)
- `*tx-context*` + `*agent-id*` dynamic Vars (replaces V0's AsyncLocalStorage)
- `with-tx-context` / `with-agent` use `binding` (single-fiber QuickJS-friendly)
- `transact!` validates attrs + values (Malli gate kept from V0), routes to `sidecar-poc.datahike/transact!`
- `query` / `pull` / `entity` route to `sidecar-poc.datahike` equivalents
- `listen!` wraps handler with V0-shape input map (`::db`, `::db-before`, `::datoms`, `::attr-index`); `::db-before` is nil under sidecar
- `new-id!` / `id->time-str` — bit-for-bit V0 (no Node deps)
- `malli->datahike-attr`, `malli->datahike-schema`, `assert-preconditions!` — STUBS (schema installation is JVM-side under sidecar)

**`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/repl.cljs` (47 LOC)** — sidecar-flavored `seon.repl`. Drops the `datahike.api` require + `ensure-conn!`; keeps `!compile-state`, `!init-version`, `!conn`, `ensure-bootstrap!`, `parse-forms` re-export.

## Build glue

- **`deps.edn`** — new `:cljs-sidecar` alias adds `pod-host/sidecar-poc/guest-cljs/src-overlay` to `:extra-paths`.
- **`shadow-cljs.edn`** — prepended `pod-host/sidecar-poc/guest-cljs/src-overlay` to top-level `:source-paths` so the overlay wins namespace resolution. Caveat documented inline.
- **`shadow-cljs.edn`** — new `:v0-probe` build emits to `out/v0-probe/main.js`, main = `sidecar-poc.seon-bootstrap/probe-info`.
- **`pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/seon_bootstrap.cljs`** — probe namespace that requires the full V0 substrate layer-by-layer.

Build command: `clj -M:cljs:cljs-sidecar release v0-probe`.

## Build log (chronological)

1. **First probe** (layers 1-7: schema/error/parse/code/db-overlay/log/platform) — built clean. 109 files, 2 compiled, 11 warnings, 5.77s. 2.2 MB bundle.
2. **Added layers 8-10** (seon.render + seon.eval + seon.agent) — built clean. 304 files, 164 compiled, 11 warnings, 25.77s. 8.2 MB bundle.
3. **Discovered datahike.api leak.** Compiled output contained 136 `datahike.api` references. Traced to `seon.agent` → `seon.repl` → `datahike.api`. The V0 `seon.repl` requires datahike for its `dev-init!` path.
4. **Wrote `seon.repl` overlay.** Dropped the `datahike.api` require + `ensure-conn!`. Kept the compile-state surface.
5. **Rebuilt** — 157 files, 5 compiled, 11 warnings, 7.77s. 3.6 MB bundle. Zero `datahike.api` references.

## Blockers identified (runtime, not compile-time)

Compilation is GREEN across the substrate. The blockers are runtime, all in code the probe imports but doesn't yet execute:

1. ~~**`seon.eval` calls `js/require("node:async_hooks")` at namespace load**~~ — **RESOLVED 2026-05-25** via `src-overlay/seon/eval.cljs`. Overlay swaps `defonce warnings-als` for `def ^:dynamic *warnings-bucket*`, the dispatcher's `.getStore` for a var deref, and `raw-eval`'s `.run` for `binding`. v0-probe build verified: 287 files / 11 pre-existing warnings, 0 `AsyncLocalStorage`/`node:async_hooks` references in the bundle.

2. **`seon.eval` reads bootstrap analysis caches from disk** (eval.cljs:125-164). `(js/require "fs")` + reads `out/bootstrap/*.transit.json`. Under wasi the files would need to be mounted as a WASI preopen — `pod-host/wasm-tauri/eval-smoke-build` has the working pattern (`--dir out/bootstrap::bootstrap`). **Fix**: copy the preopen wiring from eval-smoke.

3. **`seon.db` overlay's `validate-values!` is too loose for ref-typed attrs.** I skipped V0's bridge-based ref-arity logic to keep the overlay small. Errors a JVM writer would catch (e.g. ref-attr value that's neither map nor lookup-ref) will pass through and surface as JVM-side commit failures. **Fix**: port the `ref-attr-arity` helper (~30 LOC of V0's bridge).

4. **`seon.agent/create!` etc. call into `seon.eval/init-bootstrap!`** which needs the on-disk caches. Blocker #2 first.

5. **`seon.ai.deepseek`** (not in probe but obvious next step) — uses `js/fetch`. wasm-rquickjs has `node-http` feature; should work but the wstd async runtime + setTimeout polling pattern from the existing sidecar-poc agent applies.

6. **`seon.fs`** uses `node:fs` directly. Either stub it (read-only fallback that always returns `:seon.fs/ok? false`) or write a wasi-flavored backend (the file already documents a `:wasi` backend slot — never implemented).

7. **`seon.client` + `seon.web.*`** require `node:http`. Not portable. The agent loop needs to be driven from outside (Rust host invokes `seon.agent/run-turn!` directly through a new WIT export), not by an in-guest HTTP server.

## Stubs written

NONE for compile-success. Compile passed without any stubs.

For runtime (NOT YET WRITTEN — listed as recommended follow-up):

- `sidecar-poc.stubs.async_hooks` — minimal `AsyncLocalStorage` shim (`.run(value, f)` = call f directly; `.getStore()` returns last-set value via a closure).
- `sidecar-poc.stubs.fs` — `existsSync`/`readFileSync` that delegates to wasi:filesystem via wasm-rquickjs's `node-fs` feature (already enabled).
- `sidecar-poc.stubs.deepseek` — canned `chat-completion` that returns a fixed response, so an agent loop iterates without real LLM cost.

## Excluded namespaces

Deliberately not added to the probe:

- `seon.fs` — `node:fs` import is at namespace load. Either stub or wait for WASI backend.
- `seon.client` — requires `node:http` + `datahike.api` + `cljs-http`. Not relevant to the sidecar topology (Rust host drives the agent loop, not an in-guest HTTP server).
- `seon.web.{serve,sse,broadcast}` — same as above; agent surfaces flow through Rust→WIT, not pod-internal HTTP.
- `seon.wasm_smoke` / `seon.wasm_eval_smoke` — these are themselves smoke-test entry points, not substrate.

## Smoke test results

The bundle compiles but **HAS NOT YET BEEN INVOKED** under wasmtime. The package step (wasm-rquickjs generate-wrapper-crate + cargo build) would follow the existing `build-sidecar-agent` script's recipe; deferred because:

- Hitting Blocker #1 (`node:async_hooks` at load time) is certain — the first JS evaluation of the bundle would throw before `globalThis.sidecarBootstrapProbe` is set.
- The fix is small (overlay seon.eval too, with binding-based ALS shim), but pushed to a follow-up to keep this report a clean compile-time checkpoint.

## What's needed to get further

In order, smallest fix → biggest:

1. **`seon.eval` overlay** — drop `node:async_hooks`, replace with `^:dynamic` Var binding (same trick the seon.db overlay uses). ~50 LOC. Unblocks namespace load.
2. **`out/bootstrap/` WASI preopen** — copy the wiring from `pod-host/wasm-tauri/eval-smoke-build`. Reuses the existing bootstrap analysis caches (no rebuild needed). Unblocks `init-bootstrap!`.
3. **Package + smoke** — generate the wasm-rquickjs wrapper crate against the v0-probe bundle, instantiate in the existing Rust host, invoke `globalThis.sidecarBootstrapProbe()`. Observe what the first runtime call into seon.agent hits.
4. **Stub LLM** — give `seon.agent/run-turn!` a canned `llm-fn` so the agent loop iterates without an HTTP call.
5. **Wire `seon.agent/create!` through a new WIT export** — the Rust host invokes `runAgent(agent-id, role, duration-ms)` today; add `createSeonAgent(id)` that calls the V0 `create!` fn end-to-end. First real V0 turn lands as a sidecar transaction.

## Confidence

**HIGH that V0 substrate compiles into the sidecar.** Proven this session: 3.6MB bundle, all of seon.agent/seon.eval/seon.render compiled, zero V0-source modifications.

**MEDIUM that V0 substrate runs in the sidecar with the documented next-step overlays.** The seon.eval AsyncLocalStorage shim is the only architectural unknown; the `binding`-based approach the seon.db overlay uses is verified working (the V0 commit message at db.cljs:421-440 documents why V0 moved off `binding` to ALS — concurrent agents in one Node process. Under one wasm Store there's exactly one fiber; the V0 hazard doesn't apply.)

**MEDIUM-LOW that we can drive a full V0 agentic loop end-to-end this iteration without stubbing deepseek and the bootstrap cache layout.** Both are well-understood (eval-smoke proved bootstrap caches load under wasmtime; the existing sidecar-poc agent shows non-HTTP llm-fn patterns). But each is its own ~1-3h.

## Files touched

- **NEW:** `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` (267 LOC)
- **NEW:** `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/repl.cljs` (47 LOC)
- **NEW:** `pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/seon_bootstrap.cljs` (47 LOC)
- **EDIT:** `deps.edn` — added `:cljs-sidecar` alias (5 LOC)
- **EDIT:** `shadow-cljs.edn` — prepended overlay to `:source-paths` + new `:v0-probe` build (28 LOC added)
- **NEW:** `pod-host/sidecar-poc/bench/v0-port-survey.md` (this file)

Zero modifications to `src/seon/*`. Zero modifications to `pod-host/wasm-tauri/` or `pod-host/libdatahike-cljs/`.

## Regression check — V0 pod still builds

Existing builds verified post-overlay-introduction:

| Build | Command | Result | Notes |
|---|---|---|---|
| `:client` (V0 pod) | `clj -M:cljs release client` | ✅ 321 files, 239 compiled, 0 warnings, 32s | V0 seon.db with datahike.api — overlay NOT activated |
| `:sidecar-agent` (Phase D) | `clj -M:cljs release sidecar-agent` | ✅ 58 files, 2 compiled, 11 warnings, 4s | uses sidecar-poc.datahike directly; no seon.db |
| `:sidecar-agent` (with overlay alias) | `clj -M:cljs:cljs-sidecar release sidecar-agent` | ✅ 58 files, 2 compiled, 11 warnings, 4s | overlay path present but unused |
| `:v0-probe` (NEW) | `clj -M:cljs:cljs-sidecar release v0-probe` | ✅ 157 files, 5 compiled, 11 warnings, 8s | THIS REPORT'S DELIVERABLE |

No regressions.

## Reproducible commands

```bash
# Build the probe (clean, with the overlay active)
cd /Users/sean/src/seon
clj -M:cljs:cljs-sidecar release v0-probe
# → out/v0-probe/main.js (3.6 MB, 8400 LOC :simple-compiled CLJS)

# Verify the overlay won namespace resolution (no datahike.api leak)
grep -c "datahike\.api" out/v0-probe/main.js   # → 0

# Verify the overlay's vars are present
grep -o "seon\.db\.[a-zA-Z_-]*" out/v0-probe/main.js | sort -u | head -20

# (Next step — not yet exercised this session)
# Package as a wasm component, lifting build-sidecar-agent's recipe:
cd pod-host/sidecar-poc
# ./build-sidecar-agent --build v0-probe   (would need a tweak to point at out/v0-probe)
```
