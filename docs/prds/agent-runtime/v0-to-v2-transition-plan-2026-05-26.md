---
type: prd
status: draft
tags: [prd, agent, database, platform, migration]
---

# V0 → V2 Transition Plan — Single Substrate, Phased

**Author:** planning agent (sole; user paused all others pending this plan)
**Date:** 2026-05-26
**Branch:** `feature/agent-runtime`
**Successor to:** `pod-host/sidecar-poc/CUTOVER.md` (which becomes a §6 verification artifact, not the plan)

---

## User intent (verbatim — load-bearing)

> "I want you to launch an agent to fully write the entire transition plan and to fucking think about everything so we aren't losing progress made by the other agents for your version. The agent should look at both implementations and determine the best final implementation not just blindly move shit around. Yes we can cleanup old v0 code as needed. The ALS replacement is the biggest thing I can think of, but I'm also nervous that you're going to break the JVM side of things. We may need to upgrade the system that is launching datahike for the current seon system and also what about the seon http server? It can now do the web hosting right? This is a big change we should do it in phases and check to make sure shit isn't breaking the way we don't expect along the way."

Three asks embedded:
1. **Don't lose MVP progress** — the V0 agent loop, eval, render, run-turn decomposition, retro-stamp, instrumentation, test-runner are all working code that must survive.
2. **Pick a best final shape** — don't just move directories.
3. **Phase it, with checkpoints** — each phase verifiable; each phase rollback-able.

Plus three concrete questions: (a) ALS replacement; (b) "JVM side breaks"; (c) "what about the seon http server — it can now do web hosting right?"

---

> **REVISED 2026-05-26 PM — read this first.**
>
> The first draft proposed full seon-JVM-as-V2-server integration in one shot (option c). Sean pushed back: "we aren't going to do it right" in one shot. **Revised scope: this is a PLATFORM UPDATE for the MVP, not a full JVM integration.** Parallel systems persist; only the database layer integrates now. Full merge happens later when both sides are stable. See §0b "Revised scope" below for the corrected design.
>
> **FURTHER REVISED 2026-05-26 EVE — two-path.** The flow-runtime-update spike (`research/flow-runtime-update-spike-2026-05-26.md`) verified dynamic flow registration works but with real cost (orphans in-flight requests, drops tx-bus subscribers, stales Integrant state). Decision: **MVP doesn't integrate session DBs into the flow at all.** Path A (existing seon.clj through `:seon.db/flow`) is left untouched; Path B (agent session DBs through a direct-conn atom registry) is new. seon.clj is undisturbed throughout MVP. Flow integration deferred to its own future PRD. See `integration-architecture-2026-05-26.md` §1.5.

## §0 Executive summary

- **TL;DR (three bullets, post-revision).**
  1. **MVP-scoped platform update — parallel systems preserved.** The ONLY thing integrating now is the datahike database layer per Platform V2's multi-database plan. The seon JVM keeps its own DB and can additionally host other agents' DBs (shared or separate). Everything else stays as CLJS recreations in the pod: HTTP server, render pipeline, inspector, web UI, agent loop. Full seon-JVM-as-V2-server merge is deferred until both sides are stable and ready.
  2. **ALS goes away entirely.** Per Sean: switching to separate agents per instance (one wasm Store = one agent process = one fiber). No thread/concurrency issues. Runtime state injected into the session lives in a SINGLE ATOM (`seon.agents/!self`, not the multi-agent `!instances` map from the original atom-state PRD draft). One atom is defined per-process so dev iteration in shadow-cljs single-instance works AND it transparently works inside the wasm container (because each Store IS a single instance). `node:async_hooks` is not shipped to wasm; `^:dynamic` Vars work fine on the single-fiber model.
  3. **Revised phasing in fewer phases.** Phase 0 cleanup → Phase 1 directory rename → Phase 2 database-layer integration (multi-DB datahike in JVM, CLJS pod becomes a client) → Phase 3 single-atom runtime state → Phase 4 CLJS file relocation (move not merge) → Phase 5 multi-agent smoke against the integrated DB → Phase 6 cutover → Phase 7 cleanup. Phase 8 (full JVM merge) and Phase 9 (Tauri) explicitly deferred. Phases 2, 5, 6 are the load-bearing risk points; the rest are mechanical.

## §0b Revised scope (2026-05-26 PM)

**What integrates now (Phase 2):**
- Datahike database layer. The JVM exposes a multi-DB datahike per Platform V2's plan. CLJS pod connects as a client (via wire protocol over UDS or HTTP). Multiple DBs supported: seon JVM keeps its own, and the same JVM can host N more for agents — either shared or separate per agent.

**What stays as parallel CLJS recreations (NOT merged):**
- HTTP server: CLJS pod owns its loopback HTTP + SSE inspector. The JVM has its own `seon.web.*` for the JVM's UI. They coexist on different ports.
- Render pipeline: CLJS pod owns its renderer + inspector + chat UI. Same.
- Agent loop: lives in the CLJS pod. Wake handlers, `run-agentic-loop!`, `eval-batch!`, detect-and-tee — all CLJS.
- Schema registry: stays in `seon.schema.cljc` (CLJC, shared by both sides via reader conditionals).
- Test runner: `seon.test.runner.cljs` stays in the pod for agent-authored tests; JVM keeps its own JVM-side test runner.

**Why parallel:** rushing the full JVM merge risks breaking the JVM's production substrate (web UI, Datastar SSE, dev hook, agent JVM pool, orchestrator sessions) while we're still iterating on agent-runtime design. Merge later when both sides have stopped moving.

**Eventual full merge (Phase 8+):** when V2 is stable and CLJS surface has settled, do the option-(c) merge — JVM IS the V2 server, single HTTP, single inspector. That work is its own PRD, not this one.

**Hard preserve (don't touch in MVP):** seon JVM concepts stay untouched. `:seon.db/flow` (core.async.flow process around the conn), Integrant lifecycle, agent JVM pool, dev hook, orchestrator sessions, web UI, inspector — none of these are touched by this plan. The only seon JVM change is: convert single-DB datahike to multi-DB, point all existing seon CLJ references at a single "master seon database" (one named DB), and add a wire-server component that lets CLJS clients connect to JVM-hosted DBs (master or per-agent).

**Real work in the database-layer integration:**
- (a) JVM multi-DB datahike — Platform already solved this. Install their system.
- (b) Multi-CLJS-agent shared-DB read/write — Platform already solved this. Install their system.
- (c) seon CLJ code converts to using `(db/transact! :seon …)` style with the named master DB. Most call sites likely already do this; some may need explicit qualification.

**Temporary CLJ breakage is OK.** Platform's install may break the existing seon CLJ side while routes/refs settle. We accept that, fix it after the install lands, do NOT slow Platform down with compatibility constraints during their work. The seon CLJ web UI / dev hook / agent JVM pool / orchestrator sessions can be temporarily non-functional; cutover criterion is that they come back to green AFTER the install, not that they never break.

- **Total estimated effort.** **62-92 hours** of focused work (one engineer-equivalent). Spread across calendar time the user prefers. See §6 per-phase breakdowns.

- **Top 3 risks (full list in §5).**
  - **R1 (HIGH × HIGH):** JVM-side Integrant lifecycle breaks when merging the wire-server. The JVM today owns one Datahike conn via `:seon.db/flow` (a core.async.flow process). The V2 writer opens its own Datahike conn at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:74-77`. Two paths to the same DB inside one JVM is the breakage shape.
  - **R2 (HIGH × MEDIUM):** Schema drift between V0 (`src/seon/agent.cljs:112+`) and the overlay (`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs`) during Phase 4. V0 ships new attrs faster than the overlay can absorb them. Pre-mitigated by Phase 2 reuse of `src/seon/schema.cljc` (one registry, both sides).
  - **R3 (MEDIUM × HIGH):** LLM HTTP capability through WASI. `seon.ai.deepseek` uses `js/fetch`. Under wasmtime + `node-http` cargo feature this probably works (sidecar-poc/AGENT.md says "wasm-rquickjs `node-http` feature"), but ALSO: the JVM is right there and can proxy. The "JVM-proxied HTTP for guests" path is more secure and easier to control; the "guest does own fetch" path is one less component. Decision deferred to §8 Q4 with a default of "JVM proxies".

---

## §1 Inventory — what exists today

### 1a. V0 CLJS pod (`src/seon/*.cljs` — 32 files)

Total LOC across the 32 `.cljs` files: ~12,000. The big ones:

| File | LOC | Role | Health |
|---|---:|---|---|
| `src/seon/db.cljs` | 1382 | datahike-cljs API + 2 ALS instances + `*conn*` dynvar + `with-agent`/`with-tx-context` | green, full surface |
| `src/seon/agent.cljs` | 1317 | run-turn!, run-agentic-loop!, section composer, schemas, kick handler | green; §5/§6 decomposition shipped 2026-05-23 |
| `src/seon/eval.cljs` | 936 | cljs.js bootstrap, eval-batch!, per-form per-fiber warnings via ALS, globalThis result-stash, detect-and-tee | green |
| `src/seon/client.cljs` | 726 | `start-agent!`, HTTP server boot, replay-program-graph!, datahike smoke | green |
| `src/seon/fs.cljs` | 450 | local FS gate (allowlist + path-traversal) | green; uses `node:fs` |
| `src/seon/render/default.cljs` | 485 | 6 default section fns | green |
| `src/seon/web/serve.cljs` | 422 | loopback HTTP+SSE | green; uses `node:http` |
| `src/seon/test/runner.cljs` | 383 | three-tier-storage canonical example; test capture as data | green; user-named gold standard |
| `src/seon/ai/deepseek.cljs` | 211 | LLM HTTP client, AbortController timeout | green; uses `js/fetch` |
| `src/seon/render.cljs` | ~110 | resolver dispatch | green |
| `src/seon/repl.cljs` | 131 | iteration surface; `!compile-state` defonce | green |
| `src/seon/handlers/*.cljs` (7 files) | ~600 | message/eval/fn/ns/schema/wake/retro-stamp render handlers | green |
| `src/seon/web/{broadcast,inspector,sse,serve}.cljs` (4 files) | ~700 | tx-broadcast, inspector page, SSE format | green |
| `src/seon/{platform,log,error,inspect,analyzer-info,handler}.cljs` | ~700 | misc substrate | green |
| `src/seon/{wasm_smoke,wasm_eval_smoke,agent_view}.cljs` | ~400 | spike/smoke entry points | green but partly obsoleted by V2 |
| `src/seon/dev/test_preload.cljs` | 5 | shadow-cljs preload that requires every test ns | green |
| `src/seon/ui/markdown.cljs` | 192 | pure CLJS markdown→hiccup | green; portable |

Dependencies that constrain WASI portability (verified via grep):
- `node:async_hooks`: `db.cljs`, `eval.cljs` — eliminated by the atom-state PRD + V2's one-fiber-per-Store reality.
- `node:fs`: `fs.cljs`, `eval.cljs` (bootstrap-cache loader at `eval.cljs:125-164`), `web/serve.cljs` — wasm-rquickjs has a `fs` polyfill that routes to WASI preopens (verified working in sidecar-poc/PROTOCOL.md fs-smoke at line 982).
- `node:http`: `web/serve.cljs` — DOES NOT MIGRATE. HTTP server moves to JVM (the user's question).
- `js/fetch`: `ai/deepseek.cljs` — see §8 Q4 (JVM-proxied vs guest-native).

### 1b. JVM seat (`src/seon/*.clj{,c}` — 95 files)

Total LOC across `.clj`+`.cljc`: ~25,000. Anchor namespaces:

| File | LOC | Role |
|---|---:|---|
| `src/seon/db.clj` | 397 | **AUTHORITATIVE Datahike API** — `db/transact!`, `db/query`, `db/pull-by-name`. Routes through `seon.db.datahike.flow/request!` (core.async.flow process). Validates via Malli at `db.clj:93-127`. |
| `src/seon/schema.cljc` | 309 | Schema registry. CLJC — shared with the CLJS pod. |
| `src/seon/system.clj` | 275 | Integrant `init-key`/`halt-key!` for every component. |
| `src/seon/runtime.clj` | 997 | Runtime registry — every namespace instance (Integrant component, agent JVM, external nREPL) appears here. |
| `src/seon/web/server.clj` | 200 | http-kit HTTP server. Uses `requiring-resolve` late-binding for hot-reload. SSE infrastructure at 100ms throttle. |
| `src/seon/web/{routes,handlers,components,sse,sse.clj,inspector,broadcast,namespace,brotli,caddy,tailwind,flows,html,logs}.clj` | ~3000 | Full HTTP/SSE/UI stack — Datastar, Tailwind, Caddy, brotli. |
| `src/seon/db/datahike/{flow,system,schema,conn_process,tx_bus}.clj` | ~1200 | Datahike-on-flow lifecycle. THIS is "the system launching datahike for the current seon system" the user mentions. |
| `src/seon/flow/{topology,harness,trace,pool,msg,status,agent_runner}.clj` | ~2500 | core.async.flow routing backbone. |
| `src/seon/dev/{hook,instrumentation,clojure_replace,lint,test,review,markdown,...}.clj` | ~3000 | Dev hook + tooling. |
| `src/seon/ai/{claude,gemini,agent}.clj` | ~1500 | JVM-side LLM clients (different from `seon.ai.deepseek.cljs`). |
| `src/seon/ns/{view,routes,introspect,lifecycle,example}.clj` | ~1500 | Namespace surfaces — view, routes, lifecycle. |
| `src/seon/graph/{analyzer,context,extract,ingest,query,scanner}.clj` | ~1500 | Shape graph + code analysis. |
| `src/seon/ui/{components,html,viewer}.cljc` | ~600 | CLJC UI — shared CLJ/CLJS. |
| `src/seon/parse.cljc`, `src/seon/code.cljc`, `src/seon/instrument.cljc`, `src/seon/error/instrument.cljc` | ~950 | CLJC code-analysis primitives used by both sides. |
| Remaining: `core.clj`, `config.clj`, `ai.clj`, `ctx.clj`, `health.clj`, `logging.clj`, `repl.clj`, `runner.clj`, `session.clj`, `orchestrator/session.clj` | ~3000 | Composition + supporting glue. |

The JVM system is the **production backbone** today. Web UI, HTTP, datastar SSE, agent JVM pool, orchestrator sessions all live here. The user is right to be nervous — touching the Integrant graph or `seon.db.datahike.*` breaks the running app.

### NOTE on §3 buckets below (revised 2026-05-26 PM)

The bucket assignments below were written under the original "JVM IS the server" plan. Under the revised parallel-systems plan:

- Bucket A (replaced by V2 runtime) — UNCHANGED. `db.cljs` / `eval.cljs` / `repl.cljs` still get the overlay treatment because they wrap the database access path.
- Bucket B (move verbatim) — UNCHANGED. CLJS substrate moves to `pod-host/guest/` but stays CLJS.
- Bucket C (small adjustments) — `web/inspector.cljs` does NOT delete. The CLJS pod still owns its inspector. Only the path to the database changes (now goes through the wire-server client instead of in-process datahike-cljs).
- Bucket D (deleted) — does NOT delete `client.cljs` wholesale. The HTTP/web-server bits of `client.cljs` stay; only the in-process datahike init goes away (replaced by wire-server client connect). `web/{serve,broadcast,sse,inspector}.cljs` stay as CLJS.

Re-read each bucket with that lens. A more thorough rewrite of §3 will land alongside Phase 1's directory rename when paths are stable.

### 1c. V2 sidecar (`pod-host/sidecar-poc/` — Phase D + PF green)

| Subdirectory | Role | LOC | Health |
|---|---|---:|---|
| `jvm-writer/src/seon/sidecar/` | Standalone JVM writer: `writer.clj` (512), `codec.clj` (CBOR+length-framed), `transit.clj` (Transit-JSON), `broadcast.clj` (pub fanout), `client.clj` (smoke). **Duplicates the JVM `seon.db` surface partially.** Has its own `(defonce ^:private state (atom nil))` at `writer.clj:27` and opens its own Datahike conn at `writer.clj:74-77`. | ~900 | green; ALL the PoC tests pass (25/105 + 6/16 facts). **DOES NOT use `src/seon/db.clj`.** This is the duplication that Phase 2 collapses. |
| `rust-host/src/main.rs` | Rust orchestrator: spawns JVM writer subprocesses (one per session), wraps UDS in tokio actor, snapshot cache, broadcast::Sender, REPL CLI, multi-agent/multi-session runners | 2084 | green |
| `rust-host/src/guest.rs` | wasmtime + wasmtime-wasi linker, WIT `bindgen!`, `db_iface::Host` impl forwarding to JVM writer, WASI preopens, env vars | 644 | green |
| `rust-host/wit/sidecar.wit` | The WIT contract: 13 import ops + 2 exports (`run-smoke`, `run-agent`) | ~150 | green; complete protocol surface |
| `guest-cljs/src/sidecar_poc/{wit,datahike,agent,fs,facts,transit,als}.cljs` | guest-side support: WIT boundary, datahike overlay, synthetic workload agent, fs helpers, facts KB, transit helpers, userland ALS | ~1200 | green |
| `guest-cljs/src-overlay/seon/{db,eval,repl}.cljs` | **V0-API-compatible overlay**. Shadows the V0 namespaces with same surface but routes through `sidecar-poc.datahike` instead of `datahike.api`. | ~700 | green compile; **HAS DRIFT** — see `bench/v0-port-survey.md:214-279`. Overlay uses `^:dynamic` for agent-id but V0 moved to ALS (commit 5a82742). |
| `PROTOCOL.md`, `SESSIONS.md`, `README.md`, `RECOMMENDATION.md`, `CUTOVER.md`, `AGENT.md` | Authoritative docs | ~1500 | green |
| `bench/*.md` | Measurement artifacts including the V0-port survey (the bucket-by-bucket compatibility audit) | ~1000 | green |
| `build-sidecar-agent` | One-shot script: shadow-cljs CLJS bundle → wasm-rquickjs wrapper → cargo build | 60 | green |

Observed health: Phase D N=3 multi-agent 300s smoke green; Phase PF multi-session (2×2 + 3×1) green; protocol coverage 9/9 V0 datahike APIs; cache hit rate validated sub-microsecond when hitting.

Observed gaps (from `CUTOVER.md` blocking list):
- No real V0 agent turn has been driven in a wasm guest yet (synthetic workload only).
- LLM HTTP capability through WIT is unfinished.
- ALS parallel-agent smoke (cross-await context isolation) not separately verified.
- EDN fallback in `read-T` still present.
- No blob WIT capability.
- No V1→V2 live session migration plan.

---

## §2 Target architecture (REVISED 2026-05-26 PM)

> **Original §2.1 below proposed option (c) — JVM IS the V2 server. That decision is REVERSED per Sean's 2026-05-26 PM steer.** The MVP-scoped target is a hybrid where ONLY the database integrates; HTTP, render, inspector, agent loop all stay as parallel CLJS. Original text retained below for historical context.

### 2.1-revised — hybrid: shared multi-DB datahike, separate everything else

- **Database layer (integrated):** seon JVM hosts multi-DB datahike per Platform V2's plan. Exposes a wire-server (UDS or HTTP) that the CLJS pod connects to as a client. JVM has its own DB; can additionally host any number of agent DBs (shared or separate).
- **CLJS pod (parallel, unchanged role):** owns its own loopback HTTP server, SSE inspector, chat UI, agent loop, render pipeline, test runner. Talks to the JVM only for database operations. Each pod = one agent instance (one wasm Store).
- **JVM (parallel, unchanged role):** keeps `seon.web.*` UI on its own port. Keeps Integrant lifecycle. Keeps dev hook. Adds the wire-server component for serving CLJS pod clients.
- **No HTTP merge.** Two HTTP servers (JVM's on 8080, pod's on its loopback port). User-facing UI happens in whichever is configured per use case.
- **No inspector merge.** CLJS pod has its CLJS inspector for "what the agent sees." JVM has its JVM inspector for "what the JVM substrate sees." They're different tools serving different audiences.
- **Full merge deferred** to Phase 8+ when both sides are stable.

### 2.1-original (HISTORICAL) — option (c): JVM IS the V2 server

The user asked: "the seon http server — it can now do the web hosting right?" Yes. The choice between (a) merge, (b) keep separate, (c) JVM-is-the-server is the load-bearing decision and the right answer is **(c)**, with reasoning that follows from the existing inventory:

1. **Two JVMs talking to one Datahike makes no sense.** Datahike is process-embedded LMDB. If the V2 "writer JVM" and the "seon main JVM" are both up, they'd be two DB owners. Either we run one JVM and it owns the DB (option c), or we delete the seon main JVM and the V2 writer JVM becomes everything (which is just option c with a different starting frame).
2. **The HTTP server is ALREADY in the JVM.** `seon.web.server` is a working http-kit + SSE + Datastar stack with hot-reload via `requiring-resolve`. Recreating it in Rust would be a parallel implementation; routing browser traffic through a Rust HTTP server to a JVM backend is a needless hop. The JVM serves the web UI directly.
3. **Rust host is for WASM, not HTTP.** The Rust host's job is `wasmtime::Store` lifecycle, WIT-bound host imports, snapshot cache, broadcast channel. None of that is HTTP-server work. The host can be a child process the JVM spawns *only when wasm guests are needed* — many sessions (REPL development, JVM-only agent JVMs) don't need wasm at all.
4. **The user's nervousness is misdirected.** The thing the user said they're nervous about ("breaking the JVM side of things") is exactly the thing this architecture *most preserves*. We're not replacing the JVM. We're keeping the JVM and adding *one new entry point* — the wire server that wasm guests connect to — implemented as one new Integrant component reading from the existing `seon.db`.

### 2.2 The diagram

```
                    ┌─────────────────────────────────────────────────┐
                    │  seon JVM (`bin/run` / `seon.runner/-main`)    │
                    │                                                 │
                    │  ┌────────────────────────────────────────────┐ │
                    │  │  Integrant system                          │ │
                    │  │   :seon.schema/registry                    │ │
                    │  │   :seon.db/flow      ◄── Datahike conn     │ │
                    │  │   :seon.web.server/http-server  port 8080  │ │
                    │  │   :seon.dev/nrepl    port 7888             │ │
                    │  │   :seon.flow/{infrastructure,pool}         │ │
                    │  │   :seon.dev/instrumentation                │ │
                    │  │   :seon.server/wire-server  ◄── NEW        │ │
                    │  │      UDS req/resp + pub per session        │ │
                    │  │      Reuses seon.db (NOT a parallel impl)  │ │
                    │  │   :seon.server/rust-host    ◄── NEW opt.   │ │
                    │  │      Child process; manages wasm guests    │ │
                    │  └────────────────────────────────────────────┘ │
                    │                                                 │
                    │  ┌─ Browser/Datastar SSE ◄── seon.web.* ──────┐ │
                    │  │     (existing, unchanged)                  │ │
                    │  └────────────────────────────────────────────┘ │
                    └──────────────┬──────────────────────────────────┘
                                   │ UDS (req/resp + pub)
                                   │ per session
                                   ▼
              ┌─────────────────────────────────────────────────┐
              │ rust-host (cargo bin, child of JVM)             │
              │   wasmtime::Store<GuestStore> × N               │
              │   snapshot cache + broadcast::Sender            │
              │   WIT-bound seon:server/db@0.1.0 imports        │
              │                                                 │
              │   Spawned by JVM via :seon.server/rust-host     │
              │   ig component (process supervision + restart)  │
              └──────────────┬──────────────────────────────────┘
                             │ component-model imports
                             ▼
                  ┌────────────────────────────────┐
                  │ wasm32-wasip2 guest             │
                  │   guest-cljs/src/seon/* (V0)    │
                  │   guest-cljs/src/seon/* overlay │
                  │   (was sidecar-poc.* — renamed) │
                  │   one Store = one fiber         │
                  │   ALS-free                      │
                  └────────────────────────────────┘
```

### 2.3 Resolved questions

| Question | Decision | Justification |
|---|---|---|
| **Where does the JVM DB server live?** | `src/seon/server/` (new subdir under the main seon JVM). Three new files: `src/seon/server/wire.clj`, `src/seon/server/codec.clj`, `src/seon/server/transit.clj`. Reuses `seon.db`. | One process, one Datahike, one registry. The wire is a new entry point that reads from `seon.db/transact!` etc. — not a parallel DB impl. |
| **Web UI hosting** | `seon.web.server` (existing) serves the browser-facing UI directly. Datastar SSE, Tailwind, Caddy, brotli — all unchanged. | Already works. No reason to put a Rust HTTP server in front. |
| **Rust host's role** | wasm execution only. Spawned as a child process by the JVM (`:seon.server/rust-host` Integrant component) when wasm sessions are needed. Otherwise not spawned. | Single responsibility. JVM-only sessions (REPL dev, agent JVMs without wasm) don't pay the Rust cost. |
| **Tauri packaging (Phase 8 only)** | Tauri wraps a launcher that runs the JVM. The Rust host is built into the same Tauri binary and invoked via the JVM Integrant component. UI is the JVM's web server in a webview. | Single user-visible process tree. Tauri's `tauri::async_runtime` spawns the JVM child; the JVM spawns the Rust host child as needed. |
| **Directory naming (final)** | `src/seon/server/` (JVM-side wire), `pod-host/host/` (Rust, renamed from `pod-host/sidecar-poc/rust-host/`), `pod-host/guest/` (CLJS guest sources, renamed from `pod-host/sidecar-poc/guest-cljs/`). The `pod-host/sidecar-poc/` directory itself is deleted at Phase 1 conclusion. | Match the architecture: the "server" is in `src/seon/` because it IS Seon; "host" + "guest" make sense for the wasm portion. |
| **CLJS files migrating?** | Moves from `src/seon/*.cljs` → `pod-host/guest/src/seon/*.cljs` (the user's existing `pod-host/sidecar-poc/guest-cljs/src-overlay/` shape, but no longer named "overlay"). One source-of-truth per ns; the JVM compiles its `.clj` siblings, the CLJS guest compiles the `.cljs` from `pod-host/guest/src/`. | Avoid two JVM/CLJS shadow ns trees. With option (c), there's no overlay anymore — there's just the V2 CLJS guest sources, and they live in `pod-host/guest/src/seon/`. The path makes the boundary obvious. The shadow-cljs source-paths simply switch. |
| **`src/seon/*.cljs`** | **Deleted at Phase 6 cutover.** Until Phase 6, the V0 build at `src/seon/*.cljs` continues to compile via shadow-cljs's `:client` build for the MVP track's iteration loop. After Phase 6, only `pod-host/guest/src/seon/*.cljs` remains. | No legacy code post-cutover (the user's stated preference, `MEMORY.md` "No legacy code"). |
| **ALS — keep, replace, or delete?** | **Delete entirely on the V2 platform.** The two V0 ALS instances (`agent-id-als`, tx-context `als-instance`) AND the V0 `warnings-als` were chosen because of *V0 multi-fiber-per-Node-process concurrency*. V2 is one-fiber-per-wasm-Store. The atom-state PRD's `seon.agents/!instances` atom carries per-agent state; `^:dynamic` Vars (V0 style, pre-5a82742) handle the fiber-local cases that aren't really needed under one-fiber-per-Store. ON THE JVM SIDE: ALS isn't applicable (Java threads). The JVM `seon.db` already uses `^:dynamic *datahike-flow*` etc. — no changes. | The atom PRD's reasoning + the V2 architecture being one-fiber-per-Store make this the cleanest answer. ALS was the *right* answer for V0; it's the *wrong* answer for V2. |

### 2.4 Why this beats the alternatives

- **Option (a) "merge into main JVM but keep wire-server logic separate"** — same code, this IS option (a) with a clearer label. We're calling it (c) because the framing matters: the JVM IS the server, not "the JVM has a sidecar bolted on". This avoids the trap of treating `src/seon/server/` as a third-party module.
- **Option (b) "keep V2 writer separate"** — rejected because two JVMs cannot share one Datahike, and one JVM (the main one) is already doing everything the user needs. Standalone V2 writer would either (i) need its own DB (defeats the point of cutover) or (ii) IPC to the main JVM (insane).

---

## §3 What survives, what migrates, what dies

### 3a. V0 CLJS files (`src/seon/*.cljs` — 32 files), bucketed

**Bucket A — runtime layer replaced (the existing overlay's targets):**
| File | V0 LOC | Replacement | Notes |
|---|---:|---|---|
| `src/seon/db.cljs` | 1382 | `pod-host/guest/src/seon/db.cljs` (overlay form, ~267 LOC) | Routes through `sidecar-poc.datahike` (which renames to `seon.guest.datahike` in Phase 1). NEW work in Phase 3: replace overlay's `^:dynamic *agent-id*` with `(seon.agents/get-key {::id (seon.agents/current)})` — see §4. |
| `src/seon/eval.cljs` | 936 | `pod-host/guest/src/seon/eval.cljs` (overlay form, ~924 LOC; the existing overlay shipped 2026-05-25) | `warnings-als` removed (atom-only, see §4). Bootstrap-cache loader (`eval.cljs:125-164`) reads from WASI preopen `/bootstrap` rather than `out/bootstrap/`. |
| `src/seon/repl.cljs` | 131 | `pod-host/guest/src/seon/repl.cljs` (overlay form, ~47 LOC) | Drops `datahike.api` require. Keeps `!compile-state`, `!init-version`, `parse-forms`. |

**Bucket B — migrates verbatim (most of the V0 substrate). ~25 files:**
| File | LOC | Notes |
|---|---:|---|
| `src/seon/agent.cljs` | 1317 | Move to `pod-host/guest/src/seon/agent.cljs`. Verbatim. No code changes. (The schemas reference `seon.schema/register!` which is CLJC — shared.) |
| `src/seon/render.cljs` | ~110 | Move verbatim. |
| `src/seon/render/default.cljs` | 485 | Move verbatim. |
| `src/seon/inspect.cljs` | ~250 | Move verbatim. |
| `src/seon/agent_view.cljs` | ~200 | Move verbatim. |
| `src/seon/handlers/{eval,fn,message,ns,retro_stamp,schema,wake}.cljs` (7 files) | ~600 | Move verbatim. |
| `src/seon/log.cljs` | 158 | Move verbatim; WASI preopen `/logs` handles the file destination per `sidecar-poc/log.cljs` future-mount comment. |
| `src/seon/handler.cljs` | ~150 | Move verbatim. |
| `src/seon/analyzer_info.cljs` | 170 | Move verbatim. |
| `src/seon/platform.cljs` | 39 | Move verbatim. The host-sniff still works under wasm-rquickjs (just returns `:wasm-rquickjs` instead of `:node`). |
| `src/seon/error.cljs` | 69 | Move verbatim. |
| `src/seon/test/runner.cljs` | 383 | Move verbatim. User-named gold standard for three-tier storage. |
| `src/seon/dev/test_preload.cljs` | 5 | Move verbatim (paths adjust). |
| `src/seon/ui/markdown.cljs` | 192 | Move verbatim. |
| `src/seon/render/default.cljs`, `src/seon/render.cljs` | (counted above) | |

**Bucket C — needs small adjustment (specific line ranges):** ~5 files.
| File | LOC | Adjustment | File:line of the change |
|---|---:|---|---|
| `src/seon/fs.cljs` | 450 | Replace `(js/require "fs")` with the wasm-rquickjs `fs` polyfill (already routes to WASI preopens — verified by sidecar-poc/PROTOCOL.md fs-smoke line 982). Allowlist roots become WASI preopen guest paths (e.g. `/seon-src`, `/scratch`). | `fs.cljs:1-50` (the require block + default `:allowed-roots`); `fs.cljs:120-180` (path normalization to handle WASI's "absolute paths only" — wasm preopens look like `/seon-src` not `~/src/seon`) |
| `src/seon/ai/deepseek.cljs` | 211 | Decision per §8 Q4 — either (a) keep `js/fetch` and rely on wasm-rquickjs `node-http` cargo feature (already enabled in sidecar build), or (b) replace with a `seon.guest.host/llm-call` WIT-bound import that proxies to the JVM. **Default plan: (a).** If (a) hits WASI sandbox issues during Phase 5, fallback to (b). | All of `deepseek.cljs` if path (b); minimal if path (a). |
| `src/seon/eval.cljs` (overlay, in Bucket A above) | (counted above) | The bootstrap-cache loader (`eval.cljs:125-164` `bootstrap-cache-files`, `load-all-analysis-caches!`) reads `out/bootstrap/`. Under wasm this becomes a WASI preopen at `/bootstrap`. The Rust host adds a `--bootstrap-dir <path>` flag wiring to `MountSpec { host: <path>, guest: "/bootstrap", ro: true }`. The `eval.cljs` code changes one constant: `"out/bootstrap"` → `"/bootstrap"`. | `eval.cljs:125-168` (the `bootstrap-path` parameter default), `eval.cljs:232+` (the `init-bootstrap!` body) |
| `src/seon/log.cljs` | 158 | The log file path default ("logs/pod-events.log") becomes "/logs/pod-events.log" matching the planned WASI preopen at `/logs` (per `f395122` commit "future WASI mount is /logs/"). | `log.cljs:178` (`*log-file*` default) |
| `src/seon/web/inspector.cljs` | ~150 | The inspector currently installs a JVM-side tx-listener and writes SSE morphs. Under V2, the wasm guest doesn't host HTTP — the JVM's `seon.web.inspector` (`.clj` sibling) listens to the wire-server's broadcast and writes morphs. So the **`.cljs` inspector becomes a no-op or is deleted (Bucket D)**. Decision: DELETE; the JVM-side `seon.web.inspector` already exists and works. | Whole file. |

**Bucket D — deleted (V0-only, no V2 equivalent):** ~4 files.
| File | LOC | Reason |
|---|---:|---|
| `src/seon/client.cljs` | 726 | This is the V0 boot/main entry. V2's boot path lives in the JVM (`seon.runner`) + the Rust host. The agent-lifecycle pieces inside (`start-agent!`, `replay-program-graph!`) migrate to `pod-host/guest/src/seon/agent_boot.cljs` (a smaller, ~250 LOC file extracted from the parts that ARE substrate-agnostic). The HTTP-server bootstrap, datahike smoke test, deepseek/stub-llm selection, process safety net — DELETED (JVM owns those). |
| `src/seon/web/{serve,broadcast,inspector,sse}.cljs` | ~700 | Whole `.cljs` web stack. V2 web UI is JVM-served. JVM siblings `seon.web.{server,broadcast,inspector,sse}.clj` already exist and work. |
| `src/seon/handlers/eval.cljs` … wait, those are in Bucket B (they're not HTTP code, they're render handlers). Correction: handlers stay. The deletes are just the HTTP/SSE code under `src/seon/web/*.cljs`. | | |
| `src/seon/wasm_smoke.cljs`, `src/seon/wasm_eval_smoke.cljs` | ~300 | Old wasm-tauri spike entry points. Obsoleted by the working sidecar-poc smoke chain. |

**Net file count after migration:**
- `src/seon/*.cljs` → 0 files (`.cljs` lane in `src/seon/` is empty; CLJC files like `parse.cljc`, `code.cljc`, `schema.cljc`, `instrument.cljc` continue to be the shared substrate).
- `pod-host/guest/src/seon/*.cljs` → ~24 files (Bucket A + B + adjusted C).

### 3b. JVM-side (`src/seon/*.clj{,c}` — 95 files)

**Stays put (vast majority — ~88 files):** the entire JVM substrate. `seon.db`, `seon.schema`, `seon.system`, `seon.runtime`, `seon.flow.*`, `seon.web.*`, `seon.db.datahike.*`, `seon.dev.*`, `seon.ns.*`, `seon.graph.*`, `seon.ai.{claude,gemini,agent}`, `seon.ui.*`, etc.

**Extended (small, surgical edits):**
| File | Edit | Effort |
|---|---|---:|
| `src/seon/system.clj` | Add `:seon.server/wire-server` Integrant component init/halt methods | 30 LOC |
| `src/seon/system.clj` | Add `:seon.server/rust-host` Integrant component (optional — spawns the Rust host child process only when configured) | 50 LOC |
| `resources/system.edn` | Wire the two new components (port/socket-base/wasm-bin paths) | 15 LOC |
| `src/seon/db.clj` | NONE if Phase 2 succeeds. The wire-server reads/writes through `seon.db/transact!` etc. as-is. | 0 LOC |

**New code (the V2 server merge — Phase 2):**
| File | Role | Est. LOC |
|---|---|---:|
| `src/seon/server/wire.clj` | UDS request loop + dispatch. Reuses `seon.db/transact!`, `seon.db/query`, `seon.db/pull-by-name`. **Replaces the 13-op dispatch table at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:200-380`.** | ~350 |
| `src/seon/server/codec.clj` | CBOR + length-framed I/O. Direct port of `jvm-writer/src/seon/sidecar/codec.clj`. | ~80 |
| `src/seon/server/transit.clj` | Transit-JSON read/write. Direct port of `jvm-writer/src/seon/sidecar/transit.clj`. | ~60 |
| `src/seon/server/broadcast.clj` | Pub-socket fanout. Direct port of `jvm-writer/src/seon/sidecar/broadcast.clj`. | ~100 |
| `src/seon/server/session.clj` | Per-session wire-server lifecycle. Maps to one Datahike DB-name per session (uses `:seon.db/flow`'s namespace-keyword pattern). | ~150 |
| **Total new code:** | | **~740 LOC** |

**Dies (after Phase 2 cutover):**
- `pod-host/sidecar-poc/jvm-writer/src/` — entire subdirectory. Replaced by `src/seon/server/`. Tests migrate to `test/seon/server/`.

### 3c. V2 sidecar — what stays, what moves

| Existing path | Destination | Reason |
|---|---|---|
| `pod-host/sidecar-poc/rust-host/` | `pod-host/host/` | Rename only. Rust host stays as is. |
| `pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/{wit,transit,fs,facts,agent,als,datahike}.cljs` | `pod-host/guest/src/seon/guest/{wit,transit,fs,facts,agent_synthetic,datahike}.cljs` | Renamed to live under `seon.guest.*`. `als.cljs` deleted (atom replaces it). `agent.cljs` renamed to `agent_synthetic.cljs` to not collide with the real `seon.agent` (Bucket B above). |
| `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/{db,eval,repl}.cljs` | `pod-host/guest/src/seon/{db,eval,repl}.cljs` | The OVERLAY becomes the real CLJS code (no overlay-vs-V0 distinction anymore — they're the only CLJS code for `seon.{db,eval,repl}`). |
| `pod-host/sidecar-poc/jvm-writer/` | DELETED at Phase 2 cutover | Replaced by `src/seon/server/`. |
| `pod-host/sidecar-poc/{README,PROTOCOL,SESSIONS,RECOMMENDATION,CUTOVER,AGENT}.md` | `docs/seon/architecture/server-{protocol,sessions,wire}.md` (consolidated) + this PRD | Single source of doc truth post-cutover. |
| `pod-host/sidecar-poc/bench/` | `docs/prds/agent-runtime/bench/` | Archived measurements. |
| `pod-host/sidecar-poc/build-sidecar-agent` | `pod-host/build-guest` | Top-level build script. |
| `pod-host/sidecar-poc/data/` | `data/sessions/` | Was already gitignored. |
| `pod-host/sidecar-poc/` (the directory itself) | DELETED at Phase 1 conclusion | Renamed away. |

---

## §4 ALS → single atom transition (REVISED 2026-05-26 PM)

This is the load-bearing technical change. The user named it the biggest concern.

> **REVISED scope:** the original draft proposed `seon.agents/!instances` — a multi-agent MAP atom — for V0 with collapse to single-agent on V2. Sean's 2026-05-26 PM steer: **drop the multi-agent map entirely**. Each agent runs in its own process/Store; runtime state lives in ONE atom (`seon.agents/!self`). Defined once per process; works identically in dev (shadow-cljs single-instance watch) and in production (wasm Store, which IS a single instance). No ALS anywhere. `^:dynamic` Vars stay for tx-context + warnings-bucket — they're fine on single-fiber.

### 4.1 What changes

| Site | V0 today | V2 final |
|---|---|---|
| **Per-agent state (config overrides, lifecycle, volatile fn refs)** | Not consolidated — scattered across `:seon.agent/*` DB datoms + globalThis result-stash + various defonce atoms | `seon.agents/!instances` atom (the atom-state PRD's central design). Atom key = agent-id; value = `::instance` map. |
| **"Who's running this fiber" (agent-id propagation)** | `agent-id-als` (Node `AsyncLocalStorage`) at `db.cljs:516-518` | **Deleted on V2.** Each wasm Store is one fiber; agent-id is bound at Store instantiation via WASI env `SIDECAR_SESSION` + a top-level `(defonce !my-agent-id (atom nil))` set by the boot path. `(seon.agents/current)` returns `@!my-agent-id`. Or the WASI env directly. |
| **tx-context (causality bundle for transacts)** | `als-instance` at `db.cljs:481-483` | `^:dynamic *tx-context*` — works under V2 because each wasm Store is one fiber (no clobber hazard). Overlay already uses this. |
| **Per-eval analyzer warnings bucket** | `warnings-als` at `eval.cljs:205-207` | `^:dynamic *warnings-bucket*` — same reasoning. Overlay already uses this. |
| **DB connection** | `*conn*` dynvar at `db.cljs:439`, set once at boot via `(set! db/*conn* conn)` | Stays as `*conn*` dynvar — substrate-singleton, not per-agent. But under V2 the "connection" is really the WIT-bound `seon:server/db@0.1.0` host — the dynvar holds a synthetic conn-record (already done by overlay). |

### 4.2 The atom shape (from atom-state PRD §4, the existing design)

```clojure
(ns seon.agents
  (:require [seon.schema :as schema]
            [seon.db :as db]))

(defonce !instances (atom {}))
(defonce !my-agent-id (atom nil))   ; per-Store (one wasm Store = one agent)

(schema/register! ::id     :seon.db/id)
(schema/register! ::state  [:enum :booting :idle :running :paused :stopped])
(schema/register! ::booted-at :inst)
(schema/register! ::eval-timeout-ms     :int)
(schema/register! ::deepseek-timeout-ms :int)
(schema/register! ::fs-allowed-roots    [:vector :string])
(schema/register! ::compile-state-ref   :any)
(schema/register! ::llm-fn              :any)
(schema/register! ::home-ns             :symbol)

(schema/register! ::instance
  [:map [::id ::id] [::state ::state] [::booted-at ::booted-at]
   [::eval-timeout-ms      {:optional true} ::eval-timeout-ms]
   [::deepseek-timeout-ms  {:optional true} ::deepseek-timeout-ms]
   [::fs-allowed-roots     {:optional true} ::fs-allowed-roots]
   [::compile-state-ref    {:optional true} ::compile-state-ref]
   [::llm-fn               {:optional true} ::llm-fn]
   [::home-ns              {:optional true} ::home-ns]])

(schema/register! :seon.agents/instances [:map-of ::id ::instance])
```

### 4.3 Migration sequence per call site (file:line)

For each V0 ALS call site, the exact change:

#### `src/seon/db.cljs` → `pod-host/guest/src/seon/db.cljs` (overlay-derived final)

- `db.cljs:481-483` (`als-instance` defonce): **DELETE**. No replacement.
- `db.cljs:485-497` (`current-tx-context`): replace with `(when (some? *tx-context*) *tx-context*)`. Var becomes `^:dynamic` at top-level.
- `db.cljs:516-518` (`agent-id-als` defonce): **DELETE**.
- `db.cljs:520-528` (`current-agent-id`): replace with `(deref seon.agents/!my-agent-id)`.
- `db.cljs:530-544` (`with-agent`): becomes:
  ```clojure
  (defn with-agent [agent-id f]
    (reset! seon.agents/!my-agent-id agent-id)  ; per-Store, so this is safe
    (f))
  ```
  No try/finally needed — agent-id is the Store's identity for its whole lifetime.
- `db.cljs:546+` (`with-tx-context`): replace `.run` of als-instance with `(binding [*tx-context* (merge *tx-context* ctx-map)] (f))`.

#### `src/seon/eval.cljs` → `pod-host/guest/src/seon/eval.cljs`

- `eval.cljs:205-207` (`warnings-als` defonce): **DELETE**. Replace with `(def ^:dynamic *warnings-bucket* nil)` at top-level.
- `eval.cljs:226` (warning dispatcher reading `.getStore warnings-als`): replace with `(when *warnings-bucket* (swap! *warnings-bucket* conj …))`.
- `eval.cljs:412` (raw-eval wrapping `.run warnings-als <atom>`): replace with `(binding [*warnings-bucket* (atom [])] (cljs.js/eval-str …))`.

#### `src/seon/agent.cljs` → `pod-host/guest/src/seon/agent.cljs`

Verbatim move. All ALS calls go through `seon.db/with-agent` etc. which now use the new mechanism. **No direct edits to `agent.cljs`.**

#### `src/seon/client.cljs` → `pod-host/guest/src/seon/agent_boot.cljs` (extracted)

- `client.cljs:579-639` (`db/with-agent` wrapping `boot-with-agent!`): becomes a straight call sequence. `with-agent` still works (just sets the atom), but the rest of the boot is unchanged.

### 4.4 Persistence story

Reconstructed from DB facts at boot (per atom-state PRD §7):

```clojure
(defn rebuild-instances! [conn]
  (let [agents (db/query
                 {::db/db @conn
                  ::db/query '[:find ?id ?state ?booted-at
                               :where [?a :seon.agent/id ?id]
                                      [?a :seon.agent/state ?state]
                                      [?a :seon.agent/booted-at ?booted-at]]})]
    (reset! seon.agents/!instances
            (into {} (for [[id state booted-at] agents]
                       [id {::id id ::state state ::booted-at booted-at}])))))
```

Called from `agent_boot.cljs` after `replay-program-graph!`. Volatile keys (compile-state, llm-fn) re-seeded by per-agent init.

### 4.5 Does ALS stay anywhere on V2?

**No, in the wasm guest.** Each Store is one fiber; `^:dynamic` Vars are clobber-safe.

**No, on the JVM.** The JVM never had ALS — it has thread-locals via `^:dynamic`, which already works correctly because the JVM threading model is preemptive but bound by `binding` scopes.

**This is the cleanest answer to the user's biggest concern.**

### 4.6 Risk mitigation for the ALS removal

R4-1: **What if a JVM agent ever needs cross-await context?** It won't — the JVM doesn't run wasm fibers; agent code on the JVM runs on real threads with stack-local `binding` scopes. The pattern that broke V0 (one Node process, many `await`-suspended fibers, dynvar set! clobber) **does not exist in V2's architecture.**

R4-2: **Drift between overlay and V0 (the bench/v0-port-survey.md drift)**: Phase 4 collapses the overlay-vs-V0 split. There IS no overlay after Phase 4 — just `pod-host/guest/src/seon/*.cljs`. Drift impossible.

---

## §5 Risk register

Ranked by probability × impact.

| # | Risk | P | I | Mitigation |
|---|---|---|---|---|
| **R1** | **JVM Integrant lifecycle breaks during Phase 2 wire-server merge.** `:seon.db/flow` owns the conn via a flow process; the V2 writer at `jvm-writer/writer.clj:74-77` opens its own conn. Two paths to one Datahike = lock contention OR two separate dbs. | HIGH | HIGH | Phase 2 ships the wire-server as a NEW Integrant component that calls `seon.db/transact!` etc. — same singleton conn. Wire-server has NO direct datahike calls. Smoke before Phase 2 conclusion: run `(user/reset)` 10 times in a row; HTTP server still responds; wire-server smoke client still gets pongs. |
| **R2** | **Schema drift between V0 (`src/seon/agent.cljs`) and the overlay during Phase 4.** Has happened before (commit 5a82742 broke the overlay). | HIGH | MED | Phase 2 makes `src/seon/schema.cljc` the single registry both sides read from (already CLJC). After Phase 4 file migration, there IS no V0 — drift impossible. Until then: Phase 4 happens AFTER MVP track signs off on a schema freeze window. |
| **R3** | **LLM HTTP through WASI doesn't work.** `js/fetch` via wasm-rquickjs `node-http` feature is documented as working but unverified in our codebase. | MED | HIGH | Phase 5 spike: build a minimum WASI guest that calls `js/fetch` to a known echo endpoint. If it works → keep `seon.ai.deepseek.cljs` as-is (Bucket C adjustment is null). If it doesn't → add a WIT-bound `seon:server/llm@0.1.0` import (~80 LOC Rust + ~30 LOC CLJS overlay). Either way, deepseek.cljs is the only file affected. |
| **R4** | **MVP track work-in-flight collides with Phase 4 file move.** | MED | MED | Communicate Phase 4 plan to MVP track via this doc. Phase 4 happens in ONE atomic commit (all 24 files moved at once). MVP track frozen for the ~2-day Phase 4 window. After Phase 4, MVP track works in `pod-host/guest/src/seon/*.cljs` instead of `src/seon/*.cljs` — same files, new location. |
| **R5** | **`(user/reset)` semantics change when the wire-server is added.** The Integrant `suspend-key!` / `resume-key` story for the wire-server must NOT halt during normal `(user/reset)` (sockets stay alive, JVM child stays alive). | MED | MED | Wire-server's `:suspend-key!` returns state, `:resume-key` reuses if config unchanged (the existing nREPL pattern at `system.clj:137-144`). Smoke after Phase 2: 10× `(user/reset)`, observe socket file unchanged, wire-server uptime unbroken. |
| **R6** | **WASI capability gaps for FS/HTTP/child processes the V0 substrate quietly relies on.** | MED | MED | Phase 5 inventory: every `js/require` and `js/fetch` in the migrating files. Phase 5 starts with a 1h capability audit, before any code lands. |
| **R7** | **Tauri integration assumptions break (Phase 8, deferred).** | LOW | MED | Phase 8 is out of scope for cutover. Plan it after Phase 7. No risk during this transition. |
| **R8** | **Datahike commit semantics change under per-session JVM isolation.** Per-session was multi-JVM in V2's PoC (Phase PF). Under option (c), per-session is one DB-name in one JVM. `seon.db/flow` already supports namespaced DB-names. | MED | LOW | The `seon.db/flow` API takes a `db-name` keyword. Wire-server maps `session-name` → `db-name`. Already the JVM's mechanism. |
| **R9** | **Test infrastructure survives the move.** `seon.test.runner` (user-named gold standard) must keep working through the move. | LOW | HIGH | `test.runner.cljs` is in Bucket B (verbatim move). Test invocations via `(user/run-tests)` are JVM-side and unchanged. CLJS tests under `test/seon/*.cljs` get path adjustments via `seon.dev.test-preload` (1 file). |
| **R10** | **Schema-registry collisions between agent-time schemas and substrate-time schemas.** | LOW | MED | Already mitigated by `seon.schema/register!` being idempotent on the same key. The "Sean's locked-in decision 3" in `docs/prds/agent-runtime/STATUS.md` makes registry global by design. |
| **R11** | **Rust host process death not handled inside the JVM.** | LOW | MED | `:seon.server/rust-host` Integrant component has a supervisor (clojure.java.process + a watchdog thread). Death → log + restart, capped at 5 restarts in 60s. |

---

## §6 Phased plan

Each phase: **Goal • Scope • Pre-condition • Done-when • Verification • Rollback • Time estimate • MVP-track impact.**

### Phase 0 — Current cleanup (smallest possible green checkpoint)

- **Goal:** Ship the in-flight atom-state PRD's CAS bug fix and remove dead/stale code so the branch is clean before any structural changes.
- **Scope:** (a) Fix any open CAS bug from the atom-state PRD work. (b) Remove userland-ALS code in `pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/als.cljs` that no longer has callers (atom replaces it). (c) Commit anything in `git status` that's working.
- **Pre-condition:** Branch as found 2026-05-26 (the 9 modified files + 8 untracked research files in git status).
- **Done-when:** `git status` shows only intentional new files; `(user/run-tests)` green on the JVM; `clj -M:cljs:cljs-sidecar release v0-probe` green; sidecar Phase D 15s smoke green.
- **Verification:**
  ```bash
  (user/run-tests)                         # JVM tests
  clj -M:cljs release client               # V0 pod build (no overlay)
  clj -M:cljs:cljs-sidecar release v0-probe # overlay build
  cd pod-host/sidecar-poc && ./build-sidecar-agent --run --duration-ms=15000
  cd jvm-writer && clojure -M:test         # 25 tests, 105 assertions
  ```
- **Rollback:** N/A (cleanup commits; revert individually if any specific fix is wrong).
- **Time:** **2-4 hours.**
- **MVP-track impact:** None. They keep iterating on `src/seon/*.cljs`.

### Phase 1 — Directory restructure (pure mechanical)

- **Goal:** Rename `pod-host/sidecar-poc/` to its final shape. NO behavior changes.
- **Scope:**
  - `pod-host/sidecar-poc/rust-host/` → `pod-host/host/`
  - `pod-host/sidecar-poc/guest-cljs/src/sidecar_poc/` → `pod-host/guest/src/seon/guest/` (renaming the ns prefix `sidecar-poc.*` → `seon.guest.*`)
  - `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/` → `pod-host/guest/src/seon/` (overlay becomes primary)
  - `pod-host/sidecar-poc/jvm-writer/` → STAYS AT `pod-host/sidecar-poc/jvm-writer/` until Phase 2 (it's about to be merged, no point moving it first).
  - `pod-host/sidecar-poc/build-sidecar-agent` → `pod-host/build-guest`
  - Update `shadow-cljs.edn` source-paths to point at new locations.
  - Update `deps.edn` `:cljs-sidecar` alias `:extra-paths` to point at new locations. Rename to `:cljs-guest`.
  - Update Rust `Cargo.toml` paths.
  - Update `wit/sidecar.wit` package name from `seon:sidecar` to `seon:server`.
- **Pre-condition:** Phase 0 green.
- **Done-when:** All sidecar-poc smokes still pass at their new paths; V0 `:client` build still green; `:v0-probe` build still green.
- **Verification:**
  ```bash
  clj -M:cljs release client                              # V0 unchanged
  clj -M:cljs:cljs-guest release v0-probe                 # renamed alias
  cd pod-host && ./build-guest --run --duration-ms=15000  # Phase D smoke
  cd pod-host/sidecar-poc/jvm-writer && clojure -M:test
  cd pod-host/host && cargo build --release
  ```
- **Rollback:** `git checkout` the rename commit. Single atomic rename commit, easy revert.
- **Time:** **3-5 hours** (mostly grep-and-replace across 200+ files of paths/namespaces).
- **MVP-track impact:** None — their files at `src/seon/*.cljs` are untouched. They might need to update one path in any dev script.

### Phase 2 — Multi-DB datahike + wire-server (DATABASE LAYER ONLY)

> **REVISED 2026-05-26 PM:** This phase used to be "the big merge" — JVM becomes the V2 server. Now it's scoped to the database layer ONLY. Wire-server lands in the JVM; HTTP/inspector/render do NOT merge. The CLJS pod stays the agent-runtime substrate. See §0b.

- **Goal:** JVM datahike becomes multi-DB. Wire-server lets CLJS pod clients connect to JVM-hosted agent DBs. Delete `pod-host/sidecar-poc/jvm-writer/`. HTTP/inspector/render in JVM stay UNCHANGED — no merge of CLJS web stack into JVM web stack.
- **Scope:**
  - Create `src/seon/server/{wire,codec,transit,broadcast,session}.clj` (~740 LOC total — porting + adapting `jvm-writer/src/seon/sidecar/*.clj`).
  - Add `:seon.server/wire-server` Integrant init/halt/suspend/resume in `src/seon/system.clj` (~30 LOC).
  - Add `:seon.server/rust-host` Integrant component (optional, ~50 LOC) — spawns the Rust host as a child via `clojure.java.process`.
  - Wire both into `resources/system.edn` (~15 LOC).
  - Migrate tests `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/*.clj` (~700 LOC of test code) to `test/seon/server/*.clj` and convert to the JVM's test infrastructure (`deftest`, `seon.test-utils`, `(user/run-tests …)`).
  - DELETE `pod-host/sidecar-poc/jvm-writer/`.
  - Update `pod-host/host/src/main.rs` to launch the Rust host independently of the JVM (CLI path), OR to be launched-by-JVM via stdin/stdout (the Integrant path). Both modes coexist.
  - Update the Rust host to NOT spawn its own JVM writer — connect to the JVM's wire-server sockets instead.
- **Pre-condition:** Phase 1 green.
- **Done-when:** Phase D smoke (N=3 guests, 300s) green against the seon JVM's wire-server. `(user/reset)` cycles cleanly with the wire-server up. JVM test suite green.
- **Verification:**
  ```bash
  # Start seon JVM (wire-server starts as Integrant component)
  ./bin/run &
  # In a REPL:
  (user/status)             # see :seon.server/wire-server :ok
  (user/run-tests)          # full JVM test suite
  # Run Phase D smoke against JVM's wire-server (Rust host launches independently):
  cd pod-host/host
  cargo run --release -- \
    --guest-wasm ../guest/build/sidecar_guest.wasm \
    --multi-agent --multi-duration-ms 300000 \
    --connect-existing-jvm  # NEW flag: don't spawn JVM, connect to seon JVM
  # Smoke: reset cycles
  # In REPL: 10× (user/reset) — wire-server stays up, broadcast subscribers unaffected
  ```
- **Rollback:** `git revert` the Phase 2 commit. The Rust host's old PoC mode (spawn its own JVM writer) is preserved as `--spawn-own-jvm` flag for emergency fallback; remove in Phase 7.
- **Time:** **18-25 hours.** Biggest phase.
- **MVP-track impact:** Light. MVP track doesn't touch JVM. But they will see new logs from the wire-server. Communicate the new component before merging.

### Phase 3 — Atom-pattern ship (replace ALS)

- **Goal:** Implement `seon.agents/!instances` and replace overlay's ALS usage.
- **Scope:**
  - Create `pod-host/guest/src/seon/agents.cljs` (~150 LOC per the atom-state PRD §4).
  - Schema registrations.
  - `get-instance`, `get-key`, `set!`, `set-volatile!`, `rebuild-instances!` fns.
  - Wire `start-agent!` (in `agent_boot.cljs` once Phase 4 extracts it; until then in `pod-host/guest/src/sidecar_poc/agent.cljs` synthetic agent) to seed the slot.
  - **Per §4.3:** Edit the overlay's `pod-host/guest/src/seon/db.cljs` (formerly `src-overlay/seon/db.cljs`):
    - Delete `^:dynamic *agent-id*` — replace with `seon.agents/!my-agent-id`.
    - Replace `^:dynamic *tx-context*` body of `with-tx-context` with the merge pattern (already done in overlay).
  - Edit `pod-host/guest/src/seon/eval.cljs` to use `^:dynamic *warnings-bucket*` (already done in overlay).
  - Add the agent-atom-creation warning per atom-state PRD §6 (Phase 4 of that PRD).
- **Pre-condition:** Phase 2 green (wire-server up).
- **Done-when:** Phase D smoke still passes. Multi-agent guests use the atom pattern. `@seon.agents/!instances` from any guest's REPL returns the current state.
- **Verification:**
  ```bash
  cd pod-host && ./build-guest --run --duration-ms=300000
  # Plus: a new ALS-replacement test
  cd pod-host/host
  cargo run --release -- --guest-wasm ... --multi-agent
  # Add a test that spawns 3 guests, each sets a per-agent key, asserts isolation
  ```
- **Rollback:** Revert Phase 3 commit; overlay reverts to pre-atom-pattern state.
- **Time:** **6-9 hours.**
- **MVP-track impact:** None — atom replacement is on V2 only. V0 ALS code keeps working in V0 until Phase 4 deletes it.

### Phase 4 — Migrate V0 CLJS to `pod-host/guest/src/seon/` (file move)

- **Goal:** Move the 24 Bucket B + Bucket C files from `src/seon/*.cljs` to `pod-host/guest/src/seon/*.cljs`. Apply Bucket C edits. Bucket A files are already in place (the overlay shipped). Bucket D files are deleted.
- **Scope:**
  - **Bucket B (~17 files)**: verbatim file moves via `git mv`.
  - **Bucket C edits** (per §3a Bucket C):
    - `fs.cljs`: 50 LOC of changes to use WASI preopens + path-normalization.
    - `eval.cljs`: 2 lines (bootstrap path constant).
    - `log.cljs`: 1 line (`*log-file*` default).
    - `ai/deepseek.cljs`: 0 or ~80 LOC depending on §8 Q4 outcome.
  - **Bucket D (~4 files)**: `git rm` for `client.cljs`, `web/{serve,broadcast,inspector,sse}.cljs`, `wasm_smoke.cljs`, `wasm_eval_smoke.cljs`.
  - Extract `agent_boot.cljs` from `client.cljs` before deleting (the parts that are substrate, not the V0-pod-only HTTP-server / smoke-test / deepseek-detection code).
  - Update `shadow-cljs.edn` `:client` build to compile from new location (or DELETE `:client` build — see Phase 6).
  - Update `pod-host/guest/src/seon/dev/test_preload.cljs` paths.
- **Pre-condition:** Phase 3 green. MVP track schema-freeze window communicated (48 hours).
- **Done-when:** Phase 5 spike (next phase) starts cleanly. Tests pass. Guest builds clean.
- **Verification:**
  ```bash
  clj -M:cljs:cljs-guest release v0-probe       # still green
  cd pod-host && ./build-guest                  # CLJS bundle builds
  cd pod-host && ./build-guest --run --duration-ms=30000  # Phase D smoke
  ```
- **Rollback:** Revert the migration commit. ONE atomic commit covering all 24 files.
- **Time:** **10-14 hours.** Mechanical moves are fast; Bucket C edits + the `client.cljs` extraction take most of the time.
- **MVP-track impact:** **HIGH.** They freeze for ~2 days during this phase. After the move, they iterate in the new location.

### Phase 5 — Real V0 turn smoke (THE PROOF)

- **Goal:** Drive one full LLM-backed turn through a wasm guest. End-to-end.
- **Scope:**
  - Build a WASI preopen mount for `/bootstrap` (out/bootstrap analysis caches).
  - Add the LLM HTTP capability per §8 Q4 decision.
  - Add a `run-real-turn` WIT export to the guest.
  - Wire the Rust host to invoke `run-real-turn` with an agent-id + a user message.
  - Run it.
- **Pre-condition:** Phase 4 green.
- **Done-when:** One `seon.agent/run-turn!` completes end-to-end in a wasm guest. Persists `:seon.turn`, `:seon.message`, `:seon.eval` entities via the wire-server. Emits tx events.
- **Verification:**
  ```bash
  cd pod-host && ./build-guest
  cd pod-host/host
  cargo run --release -- \
    --guest-wasm ../guest/build/sidecar_guest.wasm \
    --agent-role real-turn \
    --user-message "hello, world"
  # In seon JVM REPL:
  (seon.db/query :seon '[:find ?id ?role ?content
                         :where [?m :seon.message/id ?id]
                                [?m :seon.message/role ?role]
                                [?m :seon.message/content ?content]])
  ;; Expect: [user, "hello, world"], [assistant, "..."]
  ```
- **Rollback:** Revert; Phase 4 state remains the working baseline.
- **Time:** **12-18 hours.** WASI capabilities + first-real-turn debugging.
- **MVP-track impact:** None — they're on the new location now.

### Phase 6 — Cutover (V2 is default)

- **Goal:** V2 is the default. V0 `:client` build deleted. `src/seon/*.cljs` deletion confirmed.
- **Scope:**
  - Delete `:client` build from `shadow-cljs.edn`.
  - Delete `src/seon/*.cljs` files that haven't already moved (should be zero — Phase 4 moved everything).
  - Update `bin/run` / `bin/seon` to launch the new V2 stack.
  - Update CLAUDE.md "Current focus" section.
  - Archive `pod-host/sidecar-poc/CUTOVER.md`.
  - Promote `pod-host/sidecar-poc/AGENT.md` content into mainline docs.
- **Pre-condition:** Phase 5 green. User explicit sign-off ("V2 is the default now").
- **Done-when:** A fresh git clone + `bin/run` boots V2 end-to-end. Browser hits the seon JVM port, sees the inspector. Agent created, chat works.
- **Verification:**
  ```bash
  git clean -fdx                       # fresh tree
  bin/run                              # JVM boots
  cd pod-host && ./build-guest         # guest builds
  # Open http://localhost:8080         # inspector renders
  # Send a chat                        # agent responds
  ```
- **Rollback:** Revert the cutover commit. V0 `:client` build returns. Heavy commit; revert is messy but possible.
- **Time:** **4-6 hours.**
- **MVP-track impact:** Their iteration loop changes — `:client` is gone; they iterate via the new `:guest` build now (renamed). Document the new loop in CLAUDE.md.

### Phase 7 — Clean up

- **Goal:** Delete dead paths. Fix any lingering platform-specific code. Update docs.
- **Scope:**
  - Delete `pod-host/host/src/main.rs --spawn-own-jvm` flag (Phase 2's emergency rollback affordance).
  - Delete any remaining `node:async_hooks` references in commented-out V0 code.
  - Update CLAUDE.md "Where we are" / "Where we're going" sections.
  - Archive the agent-runtime PRDs that are now historical (move to `docs/prds/agent-runtime/archive/`).
  - Update `MEMORY.md` user-preferences pointers to reflect the new tree.
- **Pre-condition:** Phase 6 green and stable for ≥1 week.
- **Done-when:** A new contributor reading CLAUDE.md + the top-level README understands the system without needing to read 4 PRDs.
- **Verification:** Code review pass by user.
- **Rollback:** Trivial.
- **Time:** **3-5 hours.**
- **MVP-track impact:** None.

### Phase 8 — Tauri integration (deferred)

- **Goal:** Ship V2 inside Tauri.
- **Scope:** Out of this transition plan. Track separately.
- **Time:** **TBD** (rough guess: 20-40 hours of Tauri + signing/notarization/distribution).

### Total time estimate

| Phase | Hours |
|---:|---:|
| 0 | 2-4 |
| 1 | 3-5 |
| 2 | 18-25 |
| 3 | 6-9 |
| 4 | 10-14 |
| 5 | 12-18 |
| 6 | 4-6 |
| 7 | 3-5 |
| **Subtotal (cutover)** | **58-86** |
| 8 (Tauri) | deferred |

**Round to ~62-92 hours of focused engineering.** Calendar time: depends on user's pace. At 4h/day → 4 weeks. At 8h/day → 2 weeks.

---

## §7 Smoke / verification gates

A green Phase N requires ALL of these to pass.

### Universal gates (every phase)

```bash
# JVM tests
(user/run-tests)            # in JVM nREPL — all green
# JVM boot smoke
bin/run                     # JVM starts cleanly; no errors in logs/app.log
# JVM HTTP smoke
curl -s http://localhost:8080/ | head -5    # HTML response, not 500
# Browser smoke
# Open http://localhost:8080 in a browser; observe inspector renders
```

### Phase-specific gates

| Phase | Additional checks |
|---|---|
| **0** | V0 `:client` build green: `clj -M:cljs release client`. v0-probe build green: `clj -M:cljs:cljs-sidecar release v0-probe`. Sidecar Phase D 15s: `./build-sidecar-agent --run --duration-ms=15000`. jvm-writer tests: `cd jvm-writer && clojure -M:test`. |
| **1** | Same as 0 but with new paths (rename verification). |
| **2** | `(user/reset)` 10× cycle, wire-server uptime preserved. Phase D smoke against the JVM's wire-server (not the old jvm-writer). `lsof -U /tmp/seon-poc-default-{req,pub}.sock` shows the seon JVM PID, not a separate JVM. |
| **3** | Multi-agent ALS-replacement test: 3 guests, each sets a per-agent atom key, assert isolation across the 3 keys. |
| **4** | Same Phase D smoke; plus: every Bucket C edit smoke (fs preopen test, log path test, eval bootstrap path test). |
| **5** | The PROOF: one real LLM turn end-to-end. Persist + observe a `:seon.turn` entity. |
| **6** | Fresh-clone smoke: `git clean -fdx && bin/run && ./build-guest && open http://localhost:8080`. |
| **7** | Code review pass. |

### Test suite runs

Maximum **ONCE per phase**. JVM tests run via `(user/run-tests)` (in-REPL — ~10s); CLJS guest tests via `(seon.test.runner/run-vars …)` over MCP (~30s). Full sidecar Phase D smoke runs 5min (or 15s short).

---

## §8 Open questions

These are the things I don't have enough data to decide. Flag for the user.

**Q1 — Should agent JVMs (the existing `:seon.flow/pool` of nREPL JVMs) be retired in favor of wasm guests?**
- The JVM has a pool of pre-warmed JVMs at port 7900-7902. These are agent JVMs in the V0 sense (separate processes hosting agent code).
- Wasm guests do the same job with stronger isolation.
- I don't know if the JVM-pool agents have features wasm guests don't (e.g. heavy Java interop).
- **Recommendation:** Keep the JVM pool as-is during this transition; revisit in Phase 8 or later.

**Q2 — Does `(user/reset)` (the JVM Integrant restart cycle) need any change to coexist with the wire-server?**
- Best-guess from reading `system.clj`: NO. The wire-server is a normal Integrant component; `:suspend-key!` keeps the socket open across reset.
- But `:seon.db/flow` halts during reset. The wire-server depends on `seon.db/transact!`. Does the wire-server need to pause while the flow restarts?
- **Recommendation:** The wire-server's request loop should catch and return `{::error :db-restarting}` if `seon.db/flow` is mid-restart. Client retries. Quick to implement.

**Q3 — Will the existing `seon.web.*` handlers work as the V2 user-facing UI?**
- The handlers today render `:seon.ns`, `:seon.fn`, `:seon.eval`, `:seon.schema`, etc. via the renderer dispatch — all data is in `seon.db`.
- If V2's wasm guest writes via the wire-server into the same `seon.db`, the HTML handlers reading from that db see the same entities. **They should work unchanged.**
- The unknown is the SSE inspector — it currently listens to a JVM tx-listener. Wire-server txes will fire that same listener (it's on the same conn). **Should work.**
- **Recommendation:** Phase 2 ships with the existing handlers UNCHANGED; verify in Phase 2's browser-smoke step.

**Q4 — LLM HTTP capability: WIT-typed (JVM-proxied) or in-guest (`js/fetch`)?**
- `js/fetch` via wasm-rquickjs `node-http` cargo feature exists in our build. Not yet verified at runtime against an external endpoint.
- JVM-proxied = `seon:server/llm@0.1.0` WIT import that the JVM implements by calling deepseek directly. Adds a Rust↔WIT layer; ~110 LOC total.
- JVM-proxied has security advantages (the WASI sandbox doesn't get to hit arbitrary URLs).
- In-guest has simplicity advantages (no Rust↔WIT code path; one less component).
- **Recommendation:** Phase 5 spike tests in-guest first (1 hour). If it works AND the user accepts the security posture, ship in-guest. Otherwise, JVM-proxied.

**Q5 — Logging unification: one log stream across JVM + Rust + guests?**
- Today: JVM logs to `logs/app.log`, sidecar logs to `pod-host/sidecar-poc/data/`, wasm guests log to `wasi:logging/logging` which the Rust host forwards to its own stderr.
- Unified would be: all three pipe to `logs/app.log` (or `logs/seon.log`) with source-tags (`[jvm]`, `[host]`, `[guest:<agent-id>]`).
- **Recommendation:** Phase 7 cleanup. Not blocking.

**Q6 — Does the JVM need any change to host per-session JVMs as a multi-tenant boundary?**
- Today the V2 PoC ran one JVM per session (multi-session isolation). Under option (c), it's one JVM total, with `:seon.db/flow`'s namespace-keyword pattern handling multi-session.
- Multi-tenant SECURITY isolation under one JVM is weaker than separate processes.
- For the user's stated use case (personal AI infrastructure, not multi-user SaaS), one JVM is fine.
- **Recommendation:** One JVM. Document that multi-tenancy at the OS-process boundary is out of scope; if needed later, run multiple seon JVM instances side-by-side.

**Q7 — What's the live-V1-session migration story?**
- V0 stores its agent's DB in a `:memory` datahike — there is no persistent V0 DB at all (per `client.cljs:339-346`).
- So there's nothing to migrate. V0 doesn't have a session that survives a pod restart.
- **Confirmed:** No migration needed. Document in Phase 6 cutover notes.

---

## §9 What "done" looks like

### Directory tree (post-Phase 6)

```
seon/
├── src/seon/                       ← JVM substrate, unchanged
│   ├── *.clj                       ← 95 JVM files
│   ├── *.cljc                      ← shared (parse, code, schema, instrument, error/instrument, ui/html, ui/components)
│   ├── server/                     ← NEW (Phase 2): wire-server in the JVM
│   │   ├── wire.clj
│   │   ├── codec.clj
│   │   ├── transit.clj
│   │   ├── broadcast.clj
│   │   └── session.clj
│   └── (NO .cljs files — moved to pod-host/guest/)
├── test/seon/                      ← JVM tests
│   └── server/                     ← NEW (Phase 2): wire-server tests
├── pod-host/
│   ├── host/                       ← Rust wasm host (was rust-host)
│   │   ├── Cargo.toml
│   │   ├── src/{main,guest}.rs
│   │   └── wit/sidecar.wit         ← package seon:server
│   ├── guest/                      ← CLJS guest sources
│   │   ├── src/seon/               ← THE V2 CLJS substrate (was src/seon/*.cljs + overlays)
│   │   │   ├── agent.cljs          ← 1317 LOC (verbatim from V0)
│   │   │   ├── db.cljs             ← 267 LOC (the overlay, refined)
│   │   │   ├── eval.cljs           ← 924 LOC (the overlay, refined)
│   │   │   ├── repl.cljs           ← 47 LOC (the overlay, refined)
│   │   │   ├── agents.cljs         ← NEW (Phase 3): atom-state PRD
│   │   │   ├── agent_boot.cljs     ← NEW (Phase 4): extracted from client.cljs
│   │   │   ├── ... (Bucket B files, ~20)
│   │   │   └── guest/{wit,transit,fs,facts,datahike,agent_synthetic}.cljs   ← V2 support
│   │   └── build/                  ← gitignored: wasm-rquickjs wrapper
│   └── build-guest                 ← top-level build script
├── data/
│   └── sessions/                   ← gitignored: per-session konserve stores
├── shadow-cljs.edn                 ← :guest build (replaces :client)
├── deps.edn                        ← :cljs-guest alias (replaces :cljs-sidecar)
├── bin/run                         ← JVM launcher
├── bin/seon                        ← supervisor
├── docs/seon/                      ← knowledge base (mostly unchanged)
└── CLAUDE.md                       ← updated to reflect V2 as the substrate
```

### Build commands (post-cutover)

```bash
# Start the JVM (includes wire-server, web UI, Datahike, instrumentation)
bin/run

# Build the wasm guest
cd pod-host && ./build-guest

# Run the V2 multi-agent smoke (300s)
cd pod-host && ./build-guest --run --duration-ms=300000

# REPL development
# JVM REPL: nREPL at :7888
# CLJS REPL: when wasm guests are connected
```

### Where each runtime starts

- **JVM:** `bin/run` → `seon.runner/-main` → Integrant bootstrap → wire-server listens on UDS sockets + HTTP server listens on :8080.
- **Rust host:** Either launched standalone (`cargo run --release`) or via `:seon.server/rust-host` Integrant component (which `clojure.java.process/start`s it).
- **Wasm guest:** Spawned by the Rust host. Connects via WIT-bound imports to the JVM's wire-server. Multiple guests per session, multiple sessions in one JVM.

### Dev iteration loop

- **JVM changes:** edit `.clj` file → hook auto-reloads → `(user/run-tests …)` to verify.
- **CLJS guest changes:** edit `pod-host/guest/src/seon/*.cljs` → `clj -M:cljs:cljs-guest watch sidecar-agent` rebuilds incrementally → `pod-host && ./build-guest` to repackage the wasm → re-run.
- **Browser-facing UI:** edit `src/seon/web/*.clj` → hook auto-reloads → refresh browser.

### How a new agent joins the system

- User hits `http://localhost:8080/agents/new` or calls a JVM REPL fn → JVM creates an `:seon.agent` entity in the DB, spawns a wasm guest via the Rust host bound to that agent-id → guest seeds its `seon.agents/!instances` slot → starts the turn loop.

---

End of plan.
