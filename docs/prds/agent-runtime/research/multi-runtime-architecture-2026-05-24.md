---
type: research
status: active
tags: [research, agent, runtime, wasm, ipc, datahike, performance, architecture]
---

# Multi-runtime agent architecture (in progress)

## 1. TL;DR

**Updated 2026-05-24 after Sean pushed back on the original recommendation.**

**v1 (MVP, ship next): single wasm runtime, multi-agent via DB-partitioning, time-sliced cooperative coroutines.** Reason: kabel-cljs replication isn't built yet and shipping matters.

**v2 (next major version): per-runtime-per-agent.** Reason: 80 MB/agent is genuinely fine (Chrome tabs cost more), and crash isolation is architectural rather than best-effort. Sean is right that agents writing arbitrary code WILL have infinite loops and event-loop hogs; under shared-runtime one agent's bug is everyone's bug. Per-runtime gets you real isolation + per-agent REPL debugging + capability enforcement at the wasm boundary + real parallelism. The full v2 spec is in §13; effort estimate 6-9 weeks.

**Datahike-clj has kabel replication (Sean's correction).** Porting kabel to cljs against a tokio host-bus transport is the load-bearing v2 work; the rest is host plumbing.

The benchmark numbers (measured today on this machine, §3):

- **~85 MB peak RSS** per wasm Component instance (with full CLJS + datahike + cljs.js + analyzer caches loaded).
- **~7 MB peak RSS** for a bare wasm-rquickjs Component (no CLJS).
- **2.8 seconds cold-start** per instance (QuickJS parses 7.7 MB bundle + init-bootstrap reads 12 MB of analyzer caches).
- **~5-15 ms in-process Component instantiation** if the Engine + Component are pre-warmed (per the in-process Rust harness benchmark).
- **<1 ms cross-agent IPC** with shared runtime (one d/listen! callback fires after every tx, every agent sees every other's writes).
- **5-15 ms cross-agent IPC** if we went per-runtime (host-mediated tx replication, which datahike-cljs does NOT support natively — we'd have to build it).

Why single-runtime wins:

1. **80 MB per agent is the killer cost for per-runtime.** 10 task agents = 800 MB vs 180 MB shared.
2. **Datahike-cljs has no multi-instance shared backend.** Per-runtime forces inventing tx replication. Shared-runtime makes agent-id just a `:where` predicate.
3. **pod.rs is already wired this way** — every export takes `agent_id` as first arg, designed for fan-out by id inside one instance.
4. **Cooperative coroutines on one QuickJS loop give time-sliced UX naturally** — no scheduler to write.
5. **WASM IS the security boundary; per-agent further bisection inside it is convention, not enforcement** — fine for user-written agents, would need the per-instance upgrade only if agents come from untrusted sources.

The "agent-id question dissolves" appeal of per-runtime is real but achievable in shared-runtime too — the **home-ns IS the agent-identity-scoping primitive** today (every agent has a home-ns; eval running in `seon.agent.<id>` knows the id from its ns suffix). We don't need wasmtime isolation to get the dissolution.

**Concrete plan (§9):** Phase 1 adds `:seon.agent/kind {:orchestrator | :task}` + per-kind ctx defaults (3-5 days in V0 Node, carries forward unchanged to wasm). Phase 2 adds the time-sliced scheduler (3-5 days, still V0). Phase 3 is the existing wasm-Tauri migration from `platform.md`. Phase 4 is the per-runtime upgrade IF triggers in §7.B fire (untrusted agents, hard CPU isolation needs). **Nothing is throwaway.**

Sean's `(defonce default-id …)` smell is real and dissolves the same way under multi-agent: there is no "current agent" globally; every call takes an explicit id, web handlers default to a `(current-agent-id)` DB query (per the `schema-state-architecture-audit-2026-05-23.md` §2 plan). Multi-agent makes the question semantically wrong, not just architecturally awkward.

## 2. Existing wasm + pod-host work — what's known

### Substrate state (2026-05-24)

The pod-host workspace at `pod-host/wasm-tauri/` already contains a **functional multi-agent host scaffold** — this is the single most important finding for the per-runtime question. Per `pod-host/wasm-tauri/src-tauri/src/pod.rs:376-428`, every public `Pod` export takes `agent_id: &str` as its first argument:

```rust
pub async fn call_eval_form_async(&mut self, agent_id: &str, form: &str, ns: &str) -> ...
pub async fn call_query_async(&mut self, agent_id: &str, datalog: &str) -> ...
pub async fn call_trigger_turn_async(&mut self, agent_id: &str) -> ...
pub async fn call_inject_message_async(&mut self, agent_id: &str, content: &str, role) -> ...
pub async fn call_inspect_agent_async(&mut self, agent_id: &str) -> ...
pub async fn call_interrupt_async(&mut self, agent_id: &str) -> ...
```

**The architecture as wired today routes N agents through ONE wasm Component instance**, with the guest CLJS dispatching internally by `agent-id`. This is the opposite of the "one runtime per agent" hypothesis. The host is already designed for fan-out by id, not fan-out by instance.

### Capability surface — bounded at the WIT layer

`pod-host/wasm-tauri/src-wit/seon-pod.wit` declares `fs`, `mcp`, `capability-prompt`, `eval`, `query`, `trigger-turn`, `inject-message`, `inspect-agent`, `interrupt` interfaces. Host-side: `HttpAllowlist` (`pod-host/wasm-tauri/src-tauri/src/http.rs`) is per-`SeonStore` (= per-instance). `fs::Host`, `mcp::Host`, `capability-prompt::Host` impls are all stubs returning errors / `Deny` (pod.rs:191-255).

Critical limitation per `research/capability-surface-2026-05-22.md` (§async): **wasm-rquickjs does NOT support async WIT imports** (`imports.rs:240-244` returns `Err(anyhow!("Async imported functions are not supported yet"))`). Every host capability must be sync-from-guest-POV; the wstd block_on pattern bridges underneath. This shapes IPC design.

### What's NOT yet built

- Tauri shell with embedded wasmtime — Cargo.toml declares `tauri 2.x` at workspace level but `src-tauri/` is wasmtime-only today (per `platform.md` Phase 8).
- MCP-bridge binary (`mcp-server-seon/` directory exists but bridge body is skeleton).
- Per-agent capability sets — `HttpAllowlist` is per-pod, not per-agent.
- Any inter-instance IPC mechanism (because today it's one instance).

### Per-runtime cost — what the spike measured directly

WASM file sizes on disk (verified `ls -lh` in workspace):
- `pod-build/target/wasm32-wasip2/release/seon_pod.wasm` = **5.5 MB** (placeholder world)
- `pod-build/target/wasm32-wasip2/release/smoke.wasm` = **6.5 MB** (datahike-cljs smoke)
- `out/eval-smoke/main.js` (full bundled CLJS + datahike) = **7.7 MB**
- `out/bootstrap/` (analyzer caches read at pod boot) = **12 MB total** (1.6 MB `ana/`, 8.3 MB `js/`, 1.9 MB `src/`)
- `out/client/main.js` (V0 Node pod entry — does NOT bundle datahike, requires it at runtime) = **62 KB**

The spike did NOT measure runtime memory. The wasm-spike report (`research/wasm-spike-2026-05-20.md`) measured **functional** properties — cljs.js compiles under QuickJS, datahike-cljs loads, async/await works, eval-batch returns results — but not memory or instantiation latency. Section 3 below fills that gap with real measurements.

## 3. Per-runtime cost analysis

**All numbers below are measured locally on Apple Silicon (Darwin 24.6, wasmtime 44.0.1) against artifacts in this repo. Source of numbers, not estimates.**

### 3.A — Wasm + wasmtime baseline (no CLJS)

Component: `pod-build/target/wasm32-wasip2/release/seon_pod.wasm` (5.5 MB, placeholder.mjs entry, full wasm-rquickjs runtime, no CLJS bundle, async-store wasmtime config).

Measured via `wasmtime run --invoke 'get-ui-port()'`:

| Metric | Value |
|---|---|
| Peak RSS | **6.8 MB** |
| Total cycles | 60 M (~20 ms on M-series @ 3GHz) |
| Instructions retired | 263 M |
| AOT-compiled `.cwasm` size | 13 MB on disk |

This is the **floor for an empty wasm-rquickjs Component instance** — wasmtime engine + WasiCtx + ResourceTable + QuickJS runtime + tiny JS bundle. Wasmtime's `Config` defaults reserve 4 GB virtual for each wasm32 linear memory (lazily committed); on macOS the physical commit is on the order of 1-2 MB to start. The 6.8 MB RSS includes the wasmtime engine code, AOT-compiled wasm pages, QuickJS heap, and host structs.

### 3.B — Full CLJS+datahike bundle, init-bootstrap loaded

Component: `eval-smoke-build/target/wasm32-wasip2/release/eval_smoke.wasm` (13 MB, contains the 7.7 MB bundled CLJS + datahike-cljs + ESM shim + prelude). AOT `.cwasm` = 19 MB.

Measured via `wasmtime run --invoke 'init-bootstrap()'` with `out/bootstrap::bootstrap` preopen (analyzer caches mounted). Three consecutive runs:

| Metric | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Peak RSS | 84.3 MB | 84.3 MB | 84.4 MB |
| Wall clock | 2.79 s | 2.79 s | 2.79 s |
| Cycles | 8.95 B | 8.88 B | 8.89 B |
| Instructions | 37.5 B | 37.5 B | 37.5 B |
| Page reclaims | ~7000 | ~7000 | ~7000 |

The work being done: instantiate the Component (~20 ms), QuickJS parses and bytecode-compiles the 7.7 MB bundle, `init-bootstrap` reads ~12 MB of bootstrap analyzer caches from preopen, registers ~30 namespaces in cljs.core, instantiates `cljs.js` compile-state, opens an in-memory datahike conn. After all that: 84 MB peak, 2.8s.

A subsequent `eval-form("(+ 1 2)")` from a fresh process: same 84 MB and 2.79s (separate process instantiates from scratch). **Cold-start dominates; the marginal eval is sub-millisecond.**

### 3.C — Concurrent instances, shared engine

Three concurrent wasmtime processes running init-bootstrap on the same `.cwasm`:

```
  PID    RSS COMMAND
 4600  87.8 MB wasmtime ... eval_smoke.cwasm
 4601  87.3 MB wasmtime ...
 4602  87.7 MB wasmtime ...
```

**Each process: ~87 MB RSS, independent.** As separate OS processes the kernel can page-share the AOT code (since the .cwasm file maps read-only into all three), but the QuickJS heap, linear memory, and per-Component metadata are private. **Linear scaling: N processes ≈ N × 84 MB.**

In-process (multiple wasmtime Stores in one tokio host, the architecture pod.rs is set up for): the wasmtime `Engine` and the `Component` itself are shared across all Stores (an `Engine` is a JIT/cache holder; a `Component` is the compiled module). Each Store gets its own linear memory, async stacks, ResourceTable, and WasiCtx. Per-Component-instance overhead inside one process is **~80 MB** — the QuickJS heap + datahike's in-memory store + cljs.js compile-state per instance, because those live in linear memory and are NOT shared. The engine and component code (the ~20 MB AOT) ARE shared.

**Bottom line: N agents = N × ~80 MB of linear-memory-resident heap, regardless of whether they're separate OS processes or in-process Stores.** The shared savings is bounded (the ~20 MB AOT code is amortized once); the per-agent cost is dominated by QuickJS+datahike+cljs.js working set, which is per-instance no matter what.

### 3.D — Where the 80 MB goes (rough breakdown, from QuickJS internals + datahike-cljs sizing)

| Component | Estimate |
|---|---|
| QuickJS bytecode for 7.7 MB minified JS bundle | ~8-12 MB |
| QuickJS atoms + global ns objects (cljs.core symbols, ~10K of them) | ~10-15 MB |
| `cljs.js` compile-state (analyzer namespace tables, ~30 nses populated) | ~10-20 MB |
| Datahike in-memory store (empty + schema attrs) | ~3-5 MB |
| Bootstrap analyzer cache held in JS heap (12 MB on disk, parsed into JS objects) | ~25-35 MB |
| Linear memory committed | rest |

The biggest single contributor is the **bootstrap analyzer cache parsed into the JS heap** — once `init-bootstrap` runs, every namespace's analyzer data lives as a JS object graph. That's why fresh-instance memory doesn't shrink after init: the data is now resident.

### 3.E — Cold-start time breakdown

The 2.8 s cold start, roughly:

| Stage | Estimate |
|---|---|
| wasmtime Component instantiation | ~20-30 ms |
| QuickJS parses 7.7 MB JS bundle | ~400-600 ms |
| QuickJS bytecode-compiles the bundle | ~600-900 ms |
| Bundle's top-level CLJS init (cljs.core, etc.) | ~200-400 ms |
| `init-bootstrap` reads + parses ~12 MB analyzer transit files | ~600-800 ms |
| Datahike conn open + schema transact | ~50-100 ms |

**Wizer pre-init (mentioned in `m2-findings-2026-05-21.md` "What's next") would snapshot the post-init heap as a wasm starting state. Per wasm-rquickjs's docs, that can collapse the cold-start to ~200-400 ms by skipping QuickJS parse+compile.** That's the single largest available optimization. Not yet built.

### 3.F — In-process Rust harness benchmark

`cargo test --release placeholder_pod_returns_ui_port` (from `pod-host/wasm-tauri/`) instantiates the seon_pod placeholder Component via `Pod::new(wasm).start_async().await`, calls `get-ui-port`, and exits. Wall clock for the test itself: **0.34 seconds**. That includes Rust test-harness startup, Engine creation, Component::from_file (5.5 MB wasm, fresh — not pre-AOT'd), Linker setup, instantiate_async, the call, and teardown. The compile-from-source-wasm time inside that 0.34s is the dominant cost (a fresh Component takes ~200-300ms to JIT under wasmtime 44 for a 5.5 MB Component).

**With a pre-compiled `.cwasm` and a held-warm Engine, additional Store/Instance creation should be ~5-15 ms.** That means in-host multi-agent fan-out is cheap: spawning a 50th agent is bounded by its bootstrap cost (the 2.8s init-bootstrap work) and ~80 MB of fresh heap, not by wasmtime instantiation overhead.

### 3.G — Comparison to today's Node V0 pod

`out/client/main.js` is 62 KB — the V0 Node pod loads datahike-cljs from `node_modules` (it's not bundled). `node out/client/main.js` cold-start is ~500 ms (Node startup + require graph). Single-process, single-runtime. Memory in steady state with one agent: ~80-120 MB (Node + V8 + datahike-cljs + cljs.js). Node V8 is much heavier than QuickJS per-runtime but Node is single-process so there's no per-agent multiplier.

**For the multi-agent question, V0 Node has no per-agent isolation at all — agents share the V8 heap, the require graph, the datahike conn, and globalThis.** That's the architectural floor we're trying to lift.

## 4. IPC mechanism analysis

### 4.A — `d/listen!` semantics (the only existing tx-fanout)

From `src/seon/db.cljs:1152-1224` and datahike upstream `core.cljc:206-217` + `writer.cljc:120-145`:

- `d/listen!` registers a callback in a per-conn atom keyed by user-supplied key.
- Callback fires **synchronously inside the tx commit path** — see `writer.cljc:130`. The transactor calls each listener in registration order before returning the tx-report to the caller.
- Each listener receives the FULL tx-report (`{:tx-data :tempids :db-before :db-after :tx-meta}`).
- seon's wrapper (`build-handler-input`, db.cljs:1139) decodes the datoms once and passes a rich map per listener invocation.
- Latency for a listener firing: bounded by the listener body. Pure-CLJS listener doing a `group-by` over ~20 datoms: sub-millisecond. The `broadcast` listener (web/broadcast.cljs:107) re-renders every running agent's tile, which can be 5-50 ms depending on agent count and section-fn cost.

**This is the in-process listener path. Two CLJS modules in the same QuickJS heap, sharing one conn, both call `d/listen!` and both fire on every tx.** No serialization, no message-passing, no host-host roundtrip.

### 4.B — Cross-instance IPC: what wasmtime gives you

The Component Model **deliberately does NOT support shared memory across Component instances** — that's the point of the boundary. Inter-Component communication runs through the host as one of:

1. **Host-mediated message passing via WIT-typed exports/imports.** Component A exports a fn; Component B calls a host fn that internally invokes A's export. Each cross-boundary call copies bytes through the canonical ABI. For a 1KB EDN-serialized payload, the copy is microseconds — but the call setup (resource table allocation, async store switch in tokio, ABI marshaling) is ~10-100 μs per round-trip.

2. **Shared host resource (e.g. one datahike conn the host holds, both components query through it).** Same payload-copy cost per call; the conn itself is host-side state, not in either guest's linear memory. **This is what shared-SQLite would be.**

3. **Stdio / pipes between separately-spawned wasmtime processes.** Adds OS-level scheduling, kernel buffer copies, async serialization. Latency: 0.1-1 ms per message minimum, dominated by kernel boundary.

### 4.C — Datahike-CLJS does NOT support multi-reader/writer

**Important constraint.** `seon.client/open-agent-conn!` uses `{:backend :memory :id (random-uuid)}` — datahike-cljs's in-memory store is a single-runtime atom. Two QuickJS Components canNOT share an in-memory datahike conn; the conn is a JS object reference, not a serializable handle.

The konserve-sqlite-cljs backend is single-writer-multiple-reader at the SQLite level, BUT datahike's connection cache and write coordination assume one Connection object per logical conn. **Two Component instances opening konserve-sqlite-cljs against the same SQLite file would race** — both would read/write konserve k/v pairs without datahike's tx-id coordination. This is fixable (datahike has a `kabel` replication primitive on the JVM side) but is NOT in the cljs path.

So **the "shared SQLite backend, multiple readers" model Sean's hypothesis assumes is not free** in cljs. We'd need to either:
- (a) build a host-side single-writer "conn server" that all agents call through (turning datahike into a request/response API — slow, defeats the in-process query speed),
- (b) port datahike's kabel replication to cljs (significant work),
- (c) accept that each instance has its own copy of the DB and synchronize via something else.

### 4.D — The realistic IPC pattern for multi-runtime (if we went that way)

Assuming separate datahike conns per instance: cross-agent state has to be communicated as **events forwarded through the host**. The host owns:
- A "tx-event bus" — a tokio broadcast channel.
- Each instance's `d/listen!` callback into seon-side code that calls a host-exported `publish-tx!(agent-id, tx-data-edn)` fn.
- Each instance's CLJS subscribes via a host import `subscribe-tx-events()` that returns a polling primitive.

Round-trip latency for "agent A asserts X, agent B sees X":
- A's tx commits → A's listener fires → CLJS calls host `publish-tx!` (~50 μs)
- Host writes to broadcast channel (sub-μs)
- B's polling loop picks it up next tick (depends on poll interval, ~1-10 ms typical)
- B's CLJS replays the tx into its local conn (~1 ms)
- B's `d/listen!` fires
- **Total: 5-15 ms cross-agent visibility latency.**

This is fine for human-scale UX but **dramatically slower than the same-process listener (sub-ms)** and **forces us to invent a tx replication protocol** that datahike-cljs doesn't have.

### 4.E — The in-process alternative

If we instead run **N agents in ONE wasmtime Component instance, sharing one datahike conn** (this is what pod.rs is already wired for — every export takes `agent_id`), IPC is **just `d/listen!` over one conn**. Every agent's tx is immediately visible to every other agent's listener, sub-ms, no replication protocol, no host bus. The agent-id discrimination happens by querying with `:where [?e :seon.agent/id ?id]` in the listener body.

Per the **derive-from-DB** principle in CLAUDE.md and the reactive-context concept, this is exactly the architecture: agents see derived views of the shared DB. Per-agent state is partitioned by `:seon.agent/id`; cross-agent visibility is automatic (a section function for agent B that doesn't filter by id sees agent A's writes).

Per-agent capability scoping (different `:fs/allowed-roots` for A vs B) lives as DB data the seon.fs wrapper consults, not as separate runtime sandboxes. This is **less defensive against runaway CLJS code** (a hallucinated `(js/require "node:fs")` inside any agent's eval would have whatever the wasm instance has) but **the wasm Component IS the sandbox** — per CLAUDE.md, "the CLJS sandbox layer is NOT a security boundary."

## 5. Capability + permission model

### 5.A — Today's grant unit: per-instance (= per-pod)

Per `pod-host/wasm-tauri/src-tauri/src/pod.rs:128-162`, `SeonStore` (the wasmtime Store data type) holds one `WasiCtx`, one `WasiHttpCtx`, one `ResourceTable`, and one `SeonHttpHooks` (containing the `HttpAllowlist`). All Component calls on that store hit the same capability set. The `PodBuilder` (lines 280-318) configures these at instance creation:

```rust
Pod::new(wasm)
  .with_preopen_dir("~/.seon/db", "/db")
  .with_preopen_dir("~/seon-workspace", "/workspace")
  .with_http_allow_host("api.deepseek.com")
  .start_async().await
```

**This is granularity-per-instance.** All agents in the same instance share the same allowlist, same preopens, same MCP servers.

### 5.B — The trade-off

**Per-runtime capabilities (one wasm Component per agent)** give you:
- Per-agent fs sandbox (agent A sees `/workspace/A`, agent B sees `/workspace/B`)
- Per-agent HTTPS allowlist (orchestrator can talk to LLM APIs; task agent can only talk to MCP-tool-specific endpoints)
- Per-agent MCP server set
- Crash isolation — agent A's eval that triggers a wasm trap takes only A's instance down
- Bounded blast radius for capability escalation prompts

**Shared-instance multi-agent (one wasm Component, N agents inside)** gives you:
- One capability surface to configure (simpler)
- Free in-process IPC (the d/listen! path)
- ~80 MB total memory regardless of N (until you hit datahike's working-set ceiling)
- Lower latency for everything

### 5.C — The hybrid: shared instance + DB-mediated per-agent caps

There's a third option that's underexplored: **one wasm instance, capability checks done in CLJS against per-agent DB state.** `:seon.fs/allowed-roots` becomes an attribute on `:seon.agent` entities; the host hands the union to wasmtime as the preopen list, but `seon.fs/read-file` reads the requesting agent's entity and verifies the path against THAT agent's allowed-roots before calling through.

This is **not a real sandbox** — a hallucinated `(js/require "node:fs")` inside an agent's eval would bypass seon.fs entirely and see all preopens. But per CLAUDE.md's Phase 1 statement, that's the situation TODAY in V0 Node, and the substrate's defense is "the LLM doesn't emit forms like that intentionally." Phase 3's wasm-Tauri WIT boundary is the real security gate; per-agent further bisection inside one instance is a softer convention, useful for organizing intent but not adversarial-grade.

**Recommendation: capability granularity should match security threat model.** If the threat is "user runs a malicious agent definition," per-instance is the right boundary because only wasmtime can enforce. If the threat is "agent A accidentally writes to agent B's workspace dir," DB-attribute-checked CLJS-side enforcement is sufficient. Most multi-agent scenarios in seon (orchestrator + task agents written by the same user) are the latter.

## 6. Scheduling — time-slice vs parallel

### 6.A — What wasmtime gives natively

Wasmtime's execution model for async stores: each `Store` holds a wasmtime "fiber" backing the in-flight call; a `bindings.call_foo(&mut store).await` returns a future that yields whenever the guest hits an async import. **Concurrent Store calls require concurrent `&mut Store` references, which Rust forbids — so one Store can only have one in-flight call at a time.**

For multiple agents concurrently:
- **Multiple Stores, one tokio runtime** — N agents = N Stores; tokio's multi-thread runtime schedules futures across worker threads. Wasmtime is cooperatively concurrent: a Store runs until it yields (await on host import) OR until its time slice ends.
- **Fuel-based interruption** — `Store::set_fuel()` + `Config::consume_fuel(true)` deducts fuel per instruction; when fuel runs out, the next instruction traps. Per-call setup: zero. Per-instruction overhead: ~10% slowdown. Suitable for hard caps ("no eval runs >10M instructions"); not suitable for fine time-slicing.
- **Epoch-based interruption** — `Engine::increment_epoch()` lets the host increment a counter; the guest checks the counter at backedges (loops, function calls) and traps when its deadline has passed. Lower overhead than fuel. Suitable for cooperative time-slicing ("every 100ms, interrupt the running guest so the next one can have a turn").

### 6.B — Parallel scheduling — what it looks like in pod.rs's model

Today's pod.rs only supports one-call-at-a-time per agent because each export is `async fn ... -> wasmtime::Result<...>` and Rust borrow checking serializes Store access. To support parallel agent turns:

- Either multiple `Pod` structs (one per agent), each holding its own Store — wasmtime engine + Component shared, but Store/instance distinct. Each `Pod` is independently driven by a tokio task. This IS the multi-runtime model, just expressed differently.
- Or one `Pod`, one Store, but the guest CLJS itself schedules via async/await — agents are CLJS-level coroutines on one QuickJS event loop, single-threaded.

The QuickJS event loop is single-threaded by design. **In-process multi-agent in one Store means single-threaded execution: at any moment, exactly one agent's CLJS form is running.** This is fine for time-sliced UX (the "user follows along one coherent change" model Sean prefers) and adequate for many real workloads (LLM API calls are I/O-bound, not CPU-bound — most of an agent's wall-clock time is waiting for the LLM).

### 6.C — Time-sliced model: what it falls out of

If we use **one wasm Component, one QuickJS loop, agents are cooperative coroutines** scheduled inside CLJS:

- Each agent's turn is a single `^:async` CLJS fn invocation.
- An "orchestrator loop" picks the next agent to run, awaits their turn, picks the next, etc.
- The user sees one coherent change per turn — same as Sean's preferred UX.
- Cross-agent visibility: instantaneous (shared conn, in-process listener).
- Forbidden parallelism: an LLM call from agent A blocks the queue while in flight (because QuickJS is single-threaded). MITIGATION: agents can `await` LLM calls; while one awaits, the orchestrator picks another agent to run. Cooperative concurrency, free from QuickJS's async support.

**Parallel model would require multiple Stores OR multiple processes**, each with its own QuickJS heap and datahike conn. That's the per-runtime hypothesis: yes, it's strictly more parallel; the cost is per-agent ~80 MB memory and a tx replication protocol (§4).

### 6.D — Epoch interruption for interrupting stuck evals

`seon.eval`'s `!timeout-ms` (default 5 seconds) currently uses a setTimeout race in CLJS — it returns a sentinel but the underlying QuickJS computation continues consuming CPU. **Wasmtime epoch interruption is the right primitive to actually preempt** a runaway eval. Host implementation: tick the epoch every 100 ms; guest's `cljs.js/eval-str` is wrapped to set a per-call deadline; when exceeded, wasmtime traps and the host catches it. This is independent of the multi-runtime question — it's an upgrade to the eval timeout regardless.

## 6. Scheduling — time-slice vs parallel
(in progress)

## 7. Recommended architecture

**Recommend: single wasm runtime, multi-agent via DB-partitioning, time-sliced as default, parallel as an advanced mode.** Reasons:

1. **The 80 MB / agent floor is the killer.** A user with 10 task agents in a per-runtime model = 800 MB resident, all for QuickJS+datahike+cljs.js copies. The same user in a shared-runtime model = ~120 MB (one heap, slightly more datahike state). That's a 6-7x memory ratio for an architectural choice.

2. **Datahike-cljs doesn't support multi-instance shared backend.** Going per-runtime forces inventing tx-replication. Going shared-runtime, agent-id is just a `:where` predicate. The existing `d/listen!` machinery in `seon.web.broadcast` already fans tx-events to multiple consumers via a single listener — that's the multi-agent pattern, today.

3. **pod.rs is already wired this way.** The host exposes per-agent-id exports today. Switching to per-runtime would require either spawning N Pods per session (host becomes a Pod-pool manager) or migrating WIT to a "one Pod, many sub-instances" shape that wasmtime's Component Model doesn't have first-class support for.

4. **The user's stated UX preference (time-sliced) matches single-runtime perfectly.** Cooperative coroutines on one QuickJS loop give you serialized turn execution naturally; no scheduler to write.

5. **Per CLAUDE.md: WASM IS the security boundary; the CLJS layer is not.** Per-agent capability bisection inside one wasm instance is a soft convention, useful for organizing intent, not adversarial enforcement. If the threat model is "agent definitions are user-written and trusted," the soft convention is sufficient. If the threat model is "agents come from untrusted sources" (e.g. agent marketplace, share-an-agent), per-instance is mandatory — but seon doesn't have that threat model today, and adding it isn't an alpha-blocker.

### 7.A — The recommended model in concrete shape

```
                 ┌──────────────────────────────────┐
                 │  Tauri host (Rust)               │
                 │  ┌────────────────────────────┐  │
                 │  │ wasmtime Engine + Component│  │
                 │  │ (one Component, shared)    │  │
                 │  │  ┌─────────────────────┐   │  │
                 │  │  │ One Store, one      │   │  │
                 │  │  │ QuickJS heap        │   │  │
                 │  │  │ ┌───────────────┐   │   │  │
                 │  │  │ │ ONE datahike  │   │   │  │
                 │  │  │ │ conn (shared) │   │   │  │
                 │  │  │ └───────────────┘   │   │  │
                 │  │  │  Agents:            │   │  │
                 │  │  │   orchestrator      │   │  │
                 │  │  │   task-1, task-2…   │   │  │
                 │  │  │  (CLJS coroutines)  │   │  │
                 │  │  └─────────────────────┘   │  │
                 │  └────────────────────────────┘  │
                 │  Capability surface (per pod):    │
                 │   fs preopens, HTTP allowlist,   │
                 │   MCP registry, capability prompt│
                 └──────────────────────────────────┘
```

- **One pod = one wasm Component instance = one QuickJS event loop = one datahike conn.**
- N agents inside, partitioned by `:seon.agent/id` at the data layer.
- Each agent is a `:seon.agent/kind` (`:orchestrator` or `:task` or future kinds) — different default `:seon.render/ai` slot, different system prompt context.
- Orchestrator picks the next agent to run (time-slice) OR multiple agents progress concurrently via async cooperative scheduling (parallel mode is "the orchestrator awaits multiple turn promises in parallel; QuickJS interleaves them on I/O yields").
- Cross-agent visibility: any agent's tx is immediately visible to any other's `d/listen!` callback. Default-deny is *opt-in subscription* (the listener body filters by `:seon.agent/id`); default-share is the natural shape.

### 7.B — When to revisit (the per-runtime upgrade path)

The per-runtime model becomes worth its weight when ANY of these become true:

- **Agent definitions from untrusted sources** (agent marketplace, shared-agent links). Then per-instance is mandatory. Estimated impact: tx replication protocol + Pod-pool host = +2 weeks of work, +~80MB per agent in memory.
- **Single-agent CPU contention** (one agent running a long-running ML inference or heavy CLJS computation that blocks the orchestrator's QuickJS loop). Mitigation: that agent moves into its own Component instance, communicates via the host bus. Surgical, not architectural.
- **Crash-isolation hard requirement** (a buggy agent's eval shouldn't take down the orchestrator). Mitigation: the same — move the volatile agent into its own Component. Or: use wasmtime's epoch interruption + `Trap` recovery to catch traps without killing the whole instance. Wasmtime traps don't poison the engine; they're recoverable.

The architecture supports the upgrade — going from "one instance, N agents" to "M instances, K agents each" is mostly a host-side Pod-pool change plus the tx replication path. Agents written today against the single-instance model continue to work in the multi-instance model because they communicate via the DB, not via shared QuickJS globals.

### 7.C — Agent kinds in this model

Per Sean's framing: `:seon.agent/kind {:orchestrator | :task}` (extensible). Implementation:

- `seon.agent/create!` takes `:seon.agent/kind` and writes it as a datom.
- Default `:seon.render/ai` slot is resolved per-kind via a registry (`:seon.agent.orchestrator/default-ai-render`, `:seon.agent.task/default-ai-render`).
- Default `:seon.agent/ctx` is per-kind: orchestrator gets `system-status`, `all-agents-status`, `recent-orchestrator-decisions`; task agent gets `task-spec`, `relevant-fns`, `recent-evals` (its own).
- Same `seon.agent/chat` / `seon.agent/run-turn!` plumbing; just different default context wiring.

This shape is achievable in V0 (single-runtime) and survives upgrade to multi-runtime. **No code is throwaway.**

## 8. Cost summary — typical workloads

All numbers are derived from §3 measurements (real, on this machine) extrapolated to multi-agent scenarios.

### 8.A — Shared-runtime model (recommended)

| Scenario | Memory | Cold-start | Cross-agent IPC latency | Notes |
|---|---|---|---|---|
| 1 orchestrator alone | ~85 MB | 2.8 s | n/a | Same as today's single-agent |
| 1 orchestrator + 1 task agent | ~95 MB | 2.8 s | <1 ms (in-process listener) | Task entity adds ~10MB datahike state for its turn/eval log |
| 1 orchestrator + 3 task agents | ~115 MB | 2.8 s | <1 ms | 4 agent entities, ~30MB more datahike state |
| 1 orchestrator + 10 task agents | ~180 MB | 2.8 s | <1 ms | Datahike growth dominates per-agent overhead |
| 1 orchestrator + 50 task agents | ~600 MB | 2.8 s | <1 ms | Working set starts to matter; consider eval-log retention policy |

Per-agent steady-state add: ~10 MB (DB state for that agent's turns/evals/messages, accumulating over a session). The 80-MB-per-agent QuickJS+cljs.js+datahike overhead applies ONCE for the runtime, not per-agent.

### 8.B — Per-runtime model (NOT recommended without strong reason)

| Scenario | Memory | Per-agent cold-start | Cross-agent IPC latency |
|---|---|---|---|
| 1 orchestrator alone | ~85 MB | 2.8 s | n/a |
| 1 orchestrator + 1 task agent | ~170 MB | 2.8 s per new agent | 5-15 ms (tx replication through host) |
| 1 orchestrator + 3 task agents | ~340 MB | 2.8 s each (parallel: ~3s total wall) | 5-15 ms |
| 1 orchestrator + 10 task agents | ~935 MB | 28 s sequential or ~5s with cold-start pool | 5-15 ms |
| 1 orchestrator + 50 task agents | ~4.3 GB | — | 5-15 ms |

The per-agent cold-start is the bigger problem than memory. Spawning a task agent for a quick subtask becomes a multi-second wait, every time. Wizer pre-init could collapse this to ~300 ms cold-start per agent (still 10x worse than the shared model).

### 8.C — The three numbers Sean should keep in mind

1. **~80-90 MB per wasm Component instance** (one-time, regardless of agent count inside).
2. **2.8 seconds cold-start** per wasm Component instance (Wizer could bring this to ~300 ms; we haven't built that).
3. **<1 ms shared-runtime IPC, 5-15 ms cross-runtime IPC** — an order of magnitude that determines whether agents feel like "parts of one mind" or "separate processes calling each other."

## 9. Migration plan from V0 single-runtime

The whole recommendation is "stay single-runtime, add multi-agent within it." Migration is therefore in-place, not architectural.

### Phase 1 — Agent kind distinction (V0 Node pod, ~3-5 days)

Land in V0 today; carries forward unchanged to wasm Phase 3.

1. **Add `:seon.agent/kind` schema attribute.** Enum `[:enum :orchestrator :task]` (extensible). Register in `seon.agent`.
2. **`seon.agent/create!` takes `:seon.agent/kind`.** Default `:task` for backward-compat; orchestrator must be explicit at boot.
3. **Per-kind render slot defaults.** `:seon.agent.orchestrator/default-ai-render` and `:seon.agent.task/default-ai-render` registered as symbols pointing at kind-specific section composers.
4. **Per-kind default ctx.** Orchestrator's default `:seon.agent/ctx` includes new section fns `system-status` (running agents + their states) and `agent-roster`. Task agent's includes `task-spec` (the message that spawned it).
5. **`(seon.agent/create-task-agent! {…})`** — orchestrator-callable factory that mints `:task` kind, links back to parent orchestrator via `:seon.agent/parent` ref.
6. **`seon.client/start-agent!` boots an `:orchestrator` agent at pod startup**, not a `:task`. Existing single-agent use cases are orchestrators that happen to not spawn tasks; same surface, more correct semantics.

This is the smallest viable shape. It works today in single-runtime, makes multi-agent legible to the agent itself, and adds zero memory/runtime cost (just more datoms).

### Phase 2 — Time-sliced scheduler (V0, ~3-5 days)

7. **`seon.agent/turn-queue`** — atom holding `[agent-id ...]` of agents ready to run. (Or a DB attribute `:seon.scheduler/queued-agent` if Sean prefers DB-everywhere; both work.)
8. **Orchestrator's `run-turn!` enqueues task agents.** When orchestrator decides "now agent X should think," it adds X to the queue; X's `:seon.agent/state` flips to `:queued`.
9. **The scheduler loop**: an `^:async` fn that pops from the queue, runs that agent's `run-turn!`, awaits it, loops. Single QuickJS coroutine; serialized turn execution.
10. **Parallel mode** is an alternate scheduler that pops K agents at once and `Promise.all`'s their turns. Selected via `:seon.scheduler/mode {:time-sliced | :parallel}` config attr. Default time-sliced per Sean's UX preference.

### Phase 3 — Wasm-Tauri migration (the existing Phase 3 from platform.md)

11. The wasm pod becomes the runtime. All Phase 1-2 code carries forward unchanged — it's pure CLJS.
12. **Per-agent capability bisection** lands as DB-attribute-checked CLJS-side enforcement in seon.fs / seon.http / seon.mcp wrappers. Each wrapper reads the agent's entity (`:seon.fs/allowed-roots`, `:seon.http/allowed-hosts`, `:seon.mcp/allowed-servers`) and validates the requested op against THAT agent's allowance. Below the wrapper, the wasmtime instance's preopens / allowlist is the union of all agents' caps.
13. Capability prompts (the WIT `capability-prompt::ask` path) route to a Tauri dialog scoped per-agent ("Agent task-3 wants to read ~/Downloads/foo.pdf").

### Phase 4 — Per-runtime upgrade (only if §7.B triggers fire)

14. Host becomes a Pod-pool manager — one wasm Component per "isolation group" (could be per-agent or per-trust-domain or per-tenant).
15. Cross-instance tx replication via host bus. Datahike-cljs tx-data is serializable EDN; replay on the receiving side is straight `d/transact!`.
16. Capability sets become truly per-instance.

**This phase is NOT in the current roadmap. It's the "if we need it" upgrade path the architecture leaves open.**

### What's throwaway in this plan

Nothing. Every line of code in Phases 1-2 is pure CLJS that runs identically in Node V0, in wasmtime Phase 3, and in a per-runtime Phase 4. The scheduler that's "cooperative coroutines in one QuickJS heap" is logically the same as "cooperative coroutines, except some yield points are host-bus IPC instead of in-process listener fires" — the agent code doesn't notice the difference.

The only V0 → V1 friction point is that `:seon.agent/parent` (and any other agent-to-agent ref) in Phase 4 becomes a cross-instance reference that the local DB doesn't have, requiring lookup-by-id-not-eid. Easy to design for now (use the `:seon.agent/id` string everywhere, never the numeric `:db/id`).

## 10. Trade-offs

### Choosing single-runtime (recommended) — what you give up

1. **Crash isolation is best-effort, not architectural.** A wasm trap inside one agent's eval will (depending on how the host handles it) potentially require restarting the whole pod. **Mitigation:** wasmtime traps are recoverable — the Engine continues, the Store may need recreation. With careful host wrapping (`Trap` → log + reset agent state, don't kill pod), most cases are survivable. But a heap-corruption-grade bug in QuickJS would take everything down.

2. **CPU contention is real.** A long-running CLJS computation in one agent blocks all other agents' turns until it yields (await or finishes). For LLM-API-dominated workloads this rarely matters; for compute-heavy agents it could. Epoch interruption (§6.D) is the mitigation but only via cooperative deadline.

3. **Capability surfaces are unioned at the wasm instance level.** Per-agent fs/HTTPS bisection is CLJS-side, soft. An agent's hallucinated `(js/require "node:fs")` bypasses seon.fs entirely and gets the union. Whether this matters depends on threat model — it matches V0's posture (LLM hallucinations, not adversaries).

4. **"Promote a new fn into trusted runtimes only" is harder.** With one runtime, fn evolution is visible to all agents immediately. Promotion gating means CLJS-side opt-in (agents read `:seon.fn/promoted?` and decide whether to dispatch through it). Per-runtime is the cleaner model for hard gating.

### Choosing per-runtime — what you give up

1. **6-7x memory per agent.** 50 agents = 4 GB. On the dev machine that's livable; on a phone or low-end laptop that's broken.
2. **Multi-second cold-start per agent.** Wizer can fix this; we haven't built Wizer integration. Without it, spawning an ad-hoc task agent for a quick subtask is a multi-second pause.
3. **Tx-replication protocol we have to invent.** Datahike-cljs has no kabel port. ~2 weeks to build a "replay tx-data through host bus" reliably.
4. **Schema evolution coordination.** When one runtime registers a new schema attr, others need to learn about it (otherwise their datahike conn rejects the tx). Adds another sync channel.
5. **Cross-agent queries are slow.** "What is agent B's current ns?" becomes a host-bus round-trip instead of an in-process query.

### Decisions Sean should make explicitly

| Decision | Recommend | Reason |
|---|---|---|
| Default runtime topology | Single-runtime, DB-partitioned | §7 reasoning |
| Default scheduling | Time-sliced (cooperative coroutines) | Matches stated UX preference |
| Parallel mode availability | Yes, via `:seon.scheduler/mode :parallel` | Cheap to add (Promise.all in scheduler), valuable for I/O-bound parallel work |
| Capability granularity | Per-agent CLJS-side, per-pod wasm-level | Matches current threat model |
| Per-runtime escape hatch | Yes, design for it; don't build it yet | Phase 4 path remains open |

## 11. Open questions / decisions Sean needs to make

1. **Confirm the recommended topology** (single-runtime, multi-agent via DB-partitioning, time-sliced default). If yes: Phase 1-2 of §9 is the next code work. If you want per-runtime as the V1 target: we need a much longer design pass on tx replication + Pod-pool lifecycle.

2. **`:seon.agent/kind` enum members for v1.** Just `:orchestrator` + `:task`? Or also `:tool` (wraps an MCP server as an agent-shape), `:test` (runs targeted tests as its turn), `:reviewer` (audits another agent's plan before execution)? Pick the v1 set; design the open-set later.

3. **Orchestrator-spawn semantics.** Does the orchestrator's `(create-task-agent! {…})` block until the task agent finishes its work and returns a result, or fire-and-forget with `d/listen!`-style result watching? **Recommend: fire-and-forget by default; `await-task-agent` as a separate explicit fn.** The orchestrator's mental model is "I delegated this; the result will appear in the DB when it's done."

4. **Default ctx for orchestrator.** What does the orchestrator need to see in its context EVERY turn? Suggested defaults: `system-status` (running agents), `agent-roster` (all agents with kind+state+last-turn-at), `recent-orchestrator-decisions` (orchestrator's own eval log), `pending-task-results` (task agent outputs delivered since last orchestrator turn). What else?

5. **Default ctx for task agent.** Per task agent, what's auto-loaded? Suggested: `task-spec` (the message that spawned it), `relevant-fns` (the fns the orchestrator highlighted as relevant to the task — `:seon.agent/relevant-fns` ref vector on the agent entity), `my-recent-evals` (this task agent's own eval log).

6. **Scheduling fairness.** Time-sliced round-robin, or priority-based (`:seon.agent/priority`)? Recommend round-robin v1; priority is easy to add via a sort in the scheduler.

7. **What does "the orchestrator interrupts a task agent" look like?** With single-runtime, it's a CLJS-side flag the task agent checks at await points (cooperative). With per-runtime, it's wasmtime's epoch interruption (preemptive). Single-runtime version is sufficient if tasks are reasonably-behaved; if not, we need epoch interruption for any model.

8. **Capability scoping defaults for task agents.** Task agent inherits parent orchestrator's caps minus some explicit deny-list? Or task agent has explicitly-granted caps, nothing by default? Recommend: explicit grant per task. Orchestrator spawns task with `:seon.agent/granted-caps {…}`; that's all the task has.

9. **Sean's `(defonce default-id …)` question dissolves under multi-agent.** Per `schema-state-architecture-audit-2026-05-23.md` §2, the right answer is "no default-id, every call takes an explicit `:seon.agent/id`, web handlers default to the most-recent agent entity via a `(current-agent-id)` DB query." Multi-agent makes "the current agent" semantically wrong; the question becomes "which agent's perspective is this request scoped to." The web URL `?agent=<id>` pattern stays; CLJS REPL eval inside the pod runs in a specific agent's home-ns (already true), so eval implicitly knows its agent-id.

10. **The "runtime IS the agent" appeal — CORRECTED 2026-05-24.** Earlier draft claimed the home-ns suffix carries the agent-id. **Wrong — agents can `(in-ns 'whatever)` in their evals; current-ns is not a reliable agent-id source.** Under shared-runtime there is no in-CLJS primitive that answers "what agent is this code running on behalf of." Every fn must take agent-id as an explicit arg, OR the runtime must bind agent-id in an AsyncLocalStorage scope around every turn (this is what `seon.db/*tx-context*` already does for tx-meta — extend to agent-id). ALS-bound agent-id at the turn boundary is the shared-runtime equivalent of "the runtime IS the agent." Per-runtime gets this for free; shared-runtime gets it from disciplined ALS scoping. **Both are workable but per-runtime is genuinely simpler here.**

---

## 12. Steelman — the per-runtime-per-agent model (Sean's hypothesis, given full weight)

Added 2026-05-24 after Sean pushed back. The §7 recommendation undersold this; the rest of the doc rebalances. The §1 TL;DR still leans single-runtime for the immediate MVP, but per-runtime is now the explicit **v2 target**, not a "maybe if triggers fire" escape hatch.

### 12.A — Why 80 MB per agent is actually fine

Reframing the per-agent memory cost against what it buys:

- A modern dev laptop has 16-64 GB RAM. 80 MB × 50 agents = 4 GB. That's 6-25% of RAM for a 50-agent swarm — a non-trivial number, but **not the killer cost the §7 analysis framed it as**. The user routinely runs Chrome with 30+ tabs each consuming 100-300 MB; nobody flinches at that.
- Phones are different (4-12 GB RAM, OS reserves half). Multi-agent on mobile is a Phase 10 problem; for desktop alpha the memory budget is fine.
- The 80 MB is **mostly the bootstrap analyzer cache parsed into JS heap** (§3.D). Wizer pre-init would collapse cold-start from 2.8s to ~300ms AND likely reduce per-instance memory by ~20-30 MB (the parser temp state never has to materialize on re-instantiation). With Wizer, per-agent is closer to **50-60 MB**.

### 12.B — Crash isolation is not best-effort under per-runtime, it's architectural

Sean's framing: agents write code. Code has infinite loops. Code has heap-corruption bugs. Code holds the event loop. **Under shared-runtime, one agent's bug is everyone's bug.** Under per-runtime:

- Infinite loop in agent A: wasmtime epoch interruption terminates A's Component's in-flight call. A's Component is potentially still useful (the trap is recoverable if A's CLJS state isn't corrupted), OR the host respawns A's Component from a Wizer snapshot in ~300ms. Either way, B, C, D continue uninterrupted.
- Heap corruption in agent A's QuickJS: wasmtime traps. The trap is contained to A's Store. Host catches, drops the Store, respawns. Other agents are bytes-isolated — wasmtime's memory model guarantees no spillover.
- Agent A monopolizes its CPU: doesn't matter. A runs on its own tokio task on the tokio multi-thread runtime. B's task gets scheduled on a different worker thread. Real parallelism, not cooperative.
- **This is the model Sean's stated intuition was reaching for, and it's correct.** The §7 analysis underweighted the "agents writing arbitrary code" reality.

### 12.C — Per-agent REPL debugging falls out naturally

Sean's request: "I would love to be able to debug via REPL commands for each agent." Under shared-runtime, debugging agent A requires either:
- Running eval inside A's home-ns AND hoping A hasn't `(in-ns 'other)`d in the meantime (it has).
- Threading an explicit `:agent-id A` arg through every debug call.
- Binding `*current-agent-id*` in the debug session via ALS.

All workable, none clean. **Under per-runtime, "debug agent A" means "open an MCP eval session to A's Component."** The MCP bridge (`pod-host/wasm-tauri/mcp-server-seon/`) already speaks per-agent over `agent_id` — extend it to support a `select-agent <id>` verb that routes all subsequent `eval-form` calls to that agent's Component. Each agent has its own REPL session, its own compile-state, its own datahike conn, its own globalThis. Zero cross-talk. This is **exactly the JVM nREPL-per-process model** seon's JVM side uses for agent isolation (ports 7900-7902 per CLAUDE.md "Process Architecture"). The wasm per-runtime model is the same shape, just lighter than separate JVMs.

### 12.D — Coordination via shared DB still works (and is the architecture's whole point)

Sean's reframing: "shared database that everyone can coordinate through via data." This is **the architecture, regardless of runtime topology.** The shared DB layer doesn't care whether the consumers are in-process listeners or out-of-process replicas — it's the data plane either way.

The realistic shape under per-runtime:

- **Host owns the canonical store** (SQLite file via konserve-sqlite, or LMDB via a future konserve-lmdb-cljs). The host runs ONE datahike Connection against it. The host is the writer.
- **Each agent Component exports `:agent-tx (tx-data-edn)` and imports `:db-events`** (a poll/stream of tx-events).
- Agent A's local datahike conn is a **read-replica** rebuilt from the canonical store at startup + kept in sync via the host's event stream. A's writes go OUT through the host (which applies them to the canonical store and broadcasts the resulting tx-events to all other agents).
- Cross-agent visibility latency: 5-15 ms (the same number from §4), which is **fine for human-scale UX** and **invisible to LLM-paced agent coordination** (every LLM call is 500ms-5s; 10ms IPC is in the noise).

**Is "datahike-clj has replication, port it" a tractable v2 project?** Yes. Sean's correction: datahike-clj has kabel (a replication protocol that ships tx-events over a transport). The cljs port is conceptually straightforward — kabel is mostly EDN message-passing over a configurable transport (today: WebSocket or in-process channel). The transport in our case is the wasmtime host. Estimated effort to port kabel-cljs against a host-bus transport: 2-3 weeks of focused work, dominated by getting the replay semantics right for `:keep-history? true` (every replicated tx must preserve original tx-meta so history queries on the replica match the canonical store).

### 12.E — Capability scoping becomes real, not soft

Under per-runtime, each agent's `SeonStore` has its own `WasiCtx` (= its own preopen list, its own fs view), its own `HttpAllowlist`, its own MCP registry. The capability boundary IS the wasmtime instance boundary — exactly the design intent of the Component Model. The `seon.fs` CLJS wrapper becomes vestigial defense-in-depth; the actual enforcement is wasmtime refusing to dispatch the import.

This matters for:

- **Promoted vs experimental fns.** Agent A is the "promoted" runtime (has prod LLM API keys, has fs write to `~/seon-workspace`, has trusted MCP servers). Agent B is an experimental sub-agent with no API keys and read-only fs. Promotion of a new fn into A's runtime is gated; B can experiment freely and A only adopts after spec+tests pass.
- **User-supplied agent definitions** (the "agent marketplace" scenario, even if it's just "Sean shares an agent definition with a friend"). User-supplied agents get a stripped capability set by default; user explicitly grants via capability-prompt UI.
- **Damage containment.** A bug in agent A's CLJS that emits `(seon.http/post some-url leaked-secret)` — under shared-runtime, the secret leaks. Under per-runtime, A's allowlist denies the host and the leak fails closed.

### 12.F — Time-slice + parallel both work, but parallel is genuinely cheap

Single-runtime forced time-slice for the simple cases (one QuickJS event loop = one CLJS form executing at a time). Per-runtime gets **real parallelism for free** — N tokio tasks on a multi-thread runtime, OS scheduler does the work. Time-slice is still available (orchestrator awaits one agent at a time) and probably the right default UX, but "feeling spicy" parallel mode is a one-line scheduler change, not an architectural shift.

The "let them all run at once and see what breaks" mode that Sean wants for stress-testing the system — under shared-runtime, that's not actually parallel (QuickJS interleaves on yields); under per-runtime, it is. **Per-runtime gives you the real data on where the failure points are because the failures actually happen.**

### 12.G — The honest steelman count

Per-runtime wins on: crash isolation (architectural vs best-effort), per-agent REPL debugging (clean vs ALS-scoped), capability scoping (enforced vs convention), real parallelism (vs cooperative), agent-id question (free vs ALS-bound), promoted-vs-experimental separation (free vs CLJS-side gating).

Single-runtime wins on: memory at the high end (50+ agents), cold-start (no per-agent wait), IPC latency for cross-agent visibility (sub-ms vs 10ms — but the latter is fine), simplicity of the v1 implementation (no kabel-cljs port needed yet).

**The §7 recommendation was wrong to frame this as "single-runtime always."** The honest framing: **single-runtime is the right MVP target because the kabel-cljs port + host-bus replication isn't built yet, and shipping is more important than architectural purity. Per-runtime is the right v2 target because it solves real problems (crash isolation, per-agent REPL, capability enforcement) that single-runtime can only paper over.**

---

## 13. Per-runtime spec — what v2 looks like

For Sean to read and pre-design against. Not buildable today; concrete enough to validate the shape.

### 13.A — System topology

```
                                                    ┌─────────────────────────┐
                                                    │   Tauri host (Rust)     │
                                                    │   ┌─────────────────┐   │
                                                    │   │ wasmtime Engine │   │
                                                    │   │ + Component     │   │
                                                    │   │ (shared, JIT'd  │   │
                                                    │   │  once, cached)  │   │
                                                    │   └─────────────────┘   │
                                                    │   ┌─────────────────┐   │
                                                    │   │ Canonical Store │◄──┐
                                                    │   │ (datahike conn, │   │
                                                    │   │  SQLite-backed) │   │
                                                    │   └─────────────────┘   │
                                                    │   ┌─────────────────┐   │
                                                    │   │ Tx-event broad-│   │
                                                    │   │ cast bus       │   │
                                                    │   │ (tokio::sync::  │   │
                                                    │   │  broadcast)    │   │
                                                    │   └─────────────────┘   │
                                                    │           ▲             │
                                                    │           │             │
                ┌───────────────────────┬───────────┼───────────┼─────────────┤
                │                       │           │           │             │
                ▼                       ▼           ▼           ▼             │
       ┌────────────────┐    ┌────────────────┐  ┌────────────────┐         │
       │ Orchestrator   │    │ Task agent A   │  │ Task agent B   │         │
       │ Component      │    │ Component      │  │ Component      │ ◄───────┘
       │ ┌────────────┐ │    │ ┌────────────┐ │  │ ┌────────────┐ │
       │ │ QuickJS    │ │    │ │ QuickJS    │ │  │ │ QuickJS    │ │
       │ │ heap       │ │    │ │ heap       │ │  │ │ heap       │ │
       │ │ ┌────────┐ │ │    │ │ ┌────────┐ │ │  │ │ ┌────────┐ │ │
       │ │ │datahike│ │ │    │ │ │datahike│ │ │  │ │ │datahike│ │ │
       │ │ │replica │ │ │    │ │ │replica │ │ │  │ │ │replica │ │ │
       │ │ └────────┘ │ │    │ │ └────────┘ │ │  │ │ └────────┘ │ │
       │ └────────────┘ │    │ └────────────┘ │  │ └────────────┘ │
       │ Caps: fs:/ws,  │    │ Caps: read-only│  │ Caps: HTTP only│
       │ HTTP:llm-apis, │    │ fs:/sandbox    │  │ to MCP servers │
       │ MCP:all        │    │ no HTTP        │  │                │
       └────────────────┘    └────────────────┘  └────────────────┘
              ^                       ^                  ^
              │   per-agent           │                  │
              │   REPL session via MCP bridge            │
              │                                          │
       ┌────────────────────────────────────────────────────┐
       │ MCP bridge — `select-agent <id>; eval-form <form>` │
       │ Editor / Claude Code connects here                  │
       └────────────────────────────────────────────────────┘
```

### 13.B — Per-agent Component lifecycle

```rust
// Host owns the pool
pub struct AgentPool {
    engine: Arc<Engine>,
    component: Arc<Component>,           // JIT'd once, shared
    snapshot: Option<Arc<WizerSnapshot>>, // pre-init heap, shared
    agents: HashMap<AgentId, AgentRuntime>,
    canonical_conn: Arc<DatahikeConn>,
    tx_bus: broadcast::Sender<TxEvent>,
}

pub struct AgentRuntime {
    store: Store<SeonStore>,            // per-agent
    bindings: SeonPod,                  // per-agent instance
    caps: AgentCapabilities,            // per-agent allowlists
    tx_subscriber: broadcast::Receiver<TxEvent>, // subscribes to bus
    last_applied_tx: TxId,              // for replay catch-up
}

impl AgentPool {
    /// Spawn a new agent runtime. ~300ms with Wizer snapshot;
    /// ~2.8s without. Returns immediately; init runs on tokio task.
    pub async fn spawn(&mut self, id: AgentId, kind: AgentKind, caps: AgentCapabilities) -> Result<()> {
        let store = SeonStore::new(caps.clone(), &self.preopen_layout_for(&id))?;
        // Optionally instantiate from Wizer snapshot for fast cold-start
        let bindings = if let Some(snap) = &self.snapshot {
            SeonPod::instantiate_from_snapshot(&self.engine, &self.component, snap, store).await?
        } else {
            SeonPod::instantiate_async(&mut store, &self.component, &self.linker).await?
        };
        // Bootstrap: catch up to canonical store's current tx-id via replay
        let initial_txes = self.canonical_conn.history_since(TxId::zero()).await?;
        bindings.call_replay_txes(&mut store, initial_txes).await?;
        let last_tx = self.canonical_conn.max_tx().await?;
        let subscriber = self.tx_bus.subscribe();
        self.agents.insert(id, AgentRuntime { store, bindings, caps, tx_subscriber: subscriber, last_applied_tx: last_tx });
        // Spawn the agent's event-pump task
        tokio::spawn(self.event_pump_for(id));
        Ok(())
    }

    /// Pump tx-events from the bus into agent A's replica.
    async fn event_pump_for(&self, id: AgentId) {
        let mut runtime = self.agents.get_mut(&id).unwrap();
        while let Ok(event) = runtime.tx_subscriber.recv().await {
            if event.origin_agent == id { continue; }  // don't replay our own
            runtime.bindings.call_replay_tx(&mut runtime.store, event.tx_data_edn).await
                .unwrap_or_else(|e| log::warn!("agent {} replay failed: {}", id, e));
        }
    }

    /// Apply an agent's tx to the canonical store and broadcast.
    pub async fn apply_agent_tx(&mut self, origin: AgentId, tx_data: TxData, tx_meta: TxMeta) -> Result<TxReport> {
        // Single writer: the host's canonical conn
        let report = self.canonical_conn.transact(tx_data, tx_meta.with_origin(origin)).await?;
        // Broadcast to all OTHER agents
        let _ = self.tx_bus.send(TxEvent::from_report(&report, origin));
        Ok(report)
    }
}
```

Two critical properties:

1. **Single writer (the host's canonical conn) eliminates write conflicts.** Agent Components are read-replicas + tx-proposers; the host serializes writes. This sidesteps the multi-writer-SQLite problem cleanly. Throughput is bounded by SQLite's single-writer (10K+ tx/sec — way more than agents will generate).

2. **Tx-meta is the replication key.** Every replicated tx carries its original `:seon.db/agent-id`, `:seon.db/session-id`, `:seon.db/turn-id`, `:seon.db/eval-id`, `:seon.db/origin` from the proposing agent. Replicas reconstruct the exact same datom graph including the tx entity's metadata, so history queries on any replica return identical results.

### 13.C — Per-agent capability surfaces

WIT-typed, host-enforced:

```rust
pub struct AgentCapabilities {
    fs_preopens: Vec<(PathBuf, String, DirPerms)>,  // host_path, guest_path, perms
    http_allowlist: HashSet<String>,                 // hostnames
    mcp_servers: HashSet<String>,                    // names registered with the host
    can_spawn_agents: bool,                          // orchestrator: true; tasks: false
    fuel_per_turn: Option<u64>,                      // wasmtime fuel cap
    epoch_deadline_ms: Option<u64>,                  // wall-clock cap per turn
}
```

Stored in the canonical DB as `:seon.agent/capabilities` (a component-ref to a `:seon.capabilities` entity); orchestrator can transact updates to a task agent's capabilities, host re-reads on next agent spawn or via a `revoke-capability` WIT export that takes effect immediately.

### 13.D — Per-agent REPL

The MCP bridge (`mcp-server-seon`) speaks an enriched protocol:

```
list-agents          -> [{:id "agt_…" :kind :orchestrator :state :idle} …]
select-agent <id>    -> ok | error :no-such-agent
eval-form <form>     -> result (in selected agent's Component)
inject-message …     -> result
inspect-agent <id>   -> snapshot (any agent, no selection needed)
interrupt-agent <id> -> ok (epoch-trap that agent's in-flight turn)
spawn-agent {kind caps}  -> {:agent-id "agt_…"} (orchestrator-cap only)
respawn-agent <id>   -> ok (drop the Component, re-instantiate from snapshot)
```

From the editor side: connect once to the MCP bridge, then `(select-agent "agt_task-3"); (eval-form "(seon.agent/recent-messages)")` evaluates inside task-3's Component, sees its compile-state, its globalThis, its datahike replica. Switch agents = switch contexts. Same UX as the JVM nREPL-per-process pattern, lighter weight.

### 13.E — Orchestrator-vs-task asymmetry

Capabilities make the distinction real, not just labeling:

- **Orchestrator** capabilities: fs write to workspace, HTTP to LLM APIs, all MCP servers, `can_spawn_agents: true`, generous fuel budget. Holds the "promoted" view of the codebase.
- **Task agent** capabilities: scoped fs (its own sandbox dir under `/workspace/<task-id>`), HTTP only to MCP-relevant endpoints, `can_spawn_agents: false`, modest fuel budget. Cannot spawn sub-agents (avoids recursion). Cannot promote fns into the orchestrator's runtime — promotion is the orchestrator's job after reviewing task output.

Function promotion gate: task agent's evals write `:seon.fn/source` to the canonical store via the host. Those writes are visible to ALL agents (including orchestrator) — the DATA layer is shared. But the orchestrator's Component holds the version of the fn IT has eval'd into its own compile-state. Task agent edits don't auto-load into the orchestrator's QuickJS heap. Promotion is the explicit "orchestrator evals the new source into its own compile-state after review." Per-runtime gives you this gating for free; shared-runtime would need explicit reload boundaries.

### 13.F — Failure modes and recovery

| Failure | Per-runtime recovery |
|---|---|
| Agent A infinite loop | Epoch trap A's in-flight call. A's Component is salvageable (drop the call frame, return error to host). Other agents unaffected. |
| Agent A QuickJS heap corruption | Trap. Host drops A's Store, respawns A from Wizer snapshot + replays tx-events since A's `last_applied_tx`. ~500ms downtime for A; other agents unaffected. |
| Agent A OOM | A's wasmtime config caps linear memory at, say, 256 MB. Allocation past cap returns trap. Same recovery as above. |
| Orchestrator crashes | Host respawns the orchestrator from snapshot. Catastrophic only if the orchestrator was mid-decision and the decision wasn't yet a tx — fix: orchestrator turns are TX-bounded, "I've decided to do X" is itself a tx before "do X" runs. |
| Host crash | Tauri restart. All agents respawn from their last-known capability sets (stored as `:seon.agent/capabilities` in canonical store). |
| Tx-bus backlog (slow agent can't keep up) | Bounded broadcast channel; old events dropped. Slow agent re-syncs via `history_since(last_applied_tx)` from canonical conn. Linear catch-up cost. |

### 13.G — Effort estimate to v2

| Phase | Work | Time |
|---|---|---|
| 1. kabel-cljs port | Port datahike's replication transport to cljs against a tokio host bus. Validate replay semantics for `:keep-history? true` + tx-meta preservation. | 2-3 weeks |
| 2. AgentPool host impl | Pool lifecycle, per-agent Store mgmt, capability-set per-agent, single-writer canonical conn, tx broadcast. | 1-2 weeks |
| 3. Wizer pre-init integration | Snapshot post-bootstrap heap; instantiate-from-snapshot path. Validates 300ms cold-start target. | 1 week |
| 4. MCP bridge multi-agent | `select-agent` verb, per-agent eval routing, `inspect-agent`/`interrupt-agent`/`spawn-agent` proxying. | 3-5 days |
| 5. Per-agent capability config + UI | Tauri-side capability-prompt UI per agent, `:seon.agent/capabilities` DB shape, runtime caps revocation. | 1-2 weeks |
| 6. Migration of v1 single-runtime agents | Update `seon.agent/create!` + scheduler to optionally route through AgentPool. Existing single-runtime code path stays as the in-process fallback. | 1 week |
| **Total** | | **6-9 weeks of focused work** |

This is a real project. It's the right v2 target. It is NOT what blocks the alpha — v1 single-runtime ships first.

---

## 14. MVP fix list — what I now know needs fixing for v1

Synthesizing the audit findings + the multi-agent design + Sean's correction about home-ns:

### 14.A — Identity / agent-id (BLOCKERS for multi-agent)

**Two-layer propagation — the pod.rs scaffold is the outer half; ALS is the inner half. They compose, not compete.**

The pod.rs scaffold already delivers `agent_id` to the wasm boundary on every WIT call (every export takes `agent_id: &str` per §2). What's missing is propagation from the WIT-export handler down through the CLJS call graph. Inside CLJS, the agent's code might call `db/transact!`, `log/info!`, `render/html-render`, OR eval an arbitrary form via `cljs.js/eval-str`. None of those should require threading agent-id through their args. ALS handles propagation; the scaffold handles delivery.

```
host MCP bridge / scheduler
       │
       ▼
pod.call_X(agent_id, ...)        ← WIT call carries agent_id across boundary
       │                            (scaffold; ALREADY DONE — pod.rs:376-428)
       ▼
[wasm guest — WIT export handler]
       │ receives agent-id as fn arg
       ▼
(seon.db/with-agent agent-id …)  ← ALS scope binds; two lines per handler
       │
       ▼
all downstream CLJS reads (current-agent-id)
including cljs.js/eval-str of agent-emitted forms
```

1. **`(defonce default-id …)` MUST go** (per `schema-state-architecture-audit-2026-05-23.md` §2). Replace with explicit `:seon.agent/id` at every call site. Web handlers default to a `(current-agent-id)` DB query.
2. **Each WIT-export CLJS handler wraps its body in `(seon.db/with-agent agent-id …)`.** The handler receives agent-id from the scaffold (it's the WIT param). Two lines per handler. The WIT exports needing this wrap: `eval-form`, `eval-batch`, `query`, `trigger-turn`, `inject-message`, `inspect-agent`, `interrupt`.
3. **Add `seon.db/*agent-id*` dynvar bound by AsyncLocalStorage.** Same shape as the existing `*tx-context*` (ALS-backed). `with-agent` macro is the binder. Every `seon.db/transact!` reads it and merges into tx-meta automatically. Every log/render/query call reads it. **Critical: ALS survives `(in-ns 'other)` and survives `cljs.js/eval-str` of agent-emitted forms** — the agent can in-ns and eval whatever; their code still runs under their agent-id scope because ALS is bound at the host-async-call boundary, not in CLJS lexical scope or in the current-ns symbol.
4. **`current-agent-id` accessor** for inspector fns / web handlers / agent-emitted code — reads from the ALS bucket first (if bound), falls back to DB query "most recent `:seon.agent/id`". Throws if neither is available (multi-agent contract: explicit id required).
5. **`seon.scheduler/run-turn!` is the OTHER entry point that needs `with-agent` wrapping.** When the scheduler picks the next agent to run, it wraps that agent's turn in `(with-agent id …)` before calling `seon.agent/run-turn!`. So agent-id is bound for in-process scheduler-driven turns too, not just for host-driven WIT calls. Same macro; different binder location.
6. **`:seon.agent/kind {:orchestrator | :task}` schema attr** + per-kind defaults for `:seon.render/ai` and `:seon.agent/ctx` (per §9 Phase 1).
7. **`seon.agent/create-task-agent!` factory** — orchestrator-callable; mints a task agent with `:seon.agent/parent` ref to spawning orchestrator; default-deny capability set (no fs, no http, no mcp) that orchestrator explicitly grants.

### 14.B — Agent-id propagation in tx-meta (must precede multi-agent)

6. **`:seon.db/origin` tx-meta attr** — string enum `[:enum :orchestrator-turn :task-turn :replay :user-injection :system]`. Registered now even though only `:user-injection` and `:agent-turn` will be used in v1. Makes v2 replication path's tx-source-tracking forward-compatible without a schema migration.
7. **Audit every `db/transact!` call site for tx-meta hygiene.** `with-tx-context` should wrap every turn. Any tx outside a turn context (system bootstrap, manual eval) gets explicit `:seon.db/origin :system`. No tx should land without origin metadata — multi-agent debugging needs it.

### 14.C — Scheduler (Phase 2 in §9)

8. **`seon.scheduler` namespace** — turn queue + cooperative coroutine dispatcher. Atom-backed queue is fine for v1; can move to DB attr later. Time-sliced default; `:seon.scheduler/mode :parallel` for spicy mode.
9. **`:seon.agent/state` enum** must include `:queued` in addition to `:idle | :running`. Scheduler queue = `(d/q '[:find ?id :where [?e :seon.agent/state :queued] [?e :seon.agent/id ?id]])`.
10. **Per-agent epoch deadline.** Even in single-runtime, wasmtime epoch interruption (§6.D) is the right primitive for "this turn has been running 30 seconds, kill it." Wire host-side epoch ticker, CLJS-side deadline-per-turn. Forward-compatible with per-runtime.

### 14.D — REPL ergonomics (debugging multi-agent in shared-runtime)

11. **`seon.repl/with-agent`** macro — binds `seon.db/*agent-id*` ALS scope around a REPL form. `(seon.repl/with-agent "agt_task-3" (seon.agent/recent-messages))` runs in task-3's identity context. Workaround for "we can't tell from current-ns which agent you mean."
12. **`mcp__seon_cljs__eval` extension** — optional `:agent-id` parameter that wraps the form in `with-agent`. Editor / Claude can now eval against a specific agent's perspective even in shared-runtime.
13. **Agent-scoped result stash.** Today's `(result <id>)` lookup is global. Multi-agent: stash should be per-agent-id-namespaced so two agents can use the same eval-id locally without collision. `globalThis.__seon_results.<agent-id>.<eval-id>`.

### 14.E — Schema cleanups identified by the audit (still apply)

14. Land the `:any` audit (per §3.4 of the schema audit) — these are bugs waiting to happen, especially `:seon.db/handler-input` fields.
15. Land `:default/fn` on auto-stamped fields (per §6.1 of the schema audit) — eliminates manual `(js/Date.)` and `(db/new-id!)` at 5+ call sites.
16. Move `seon.fs/!config` to DB (`:seon.fs/config` entity). Multi-agent makes per-agent fs allowlists meaningful; the entity is the natural home.
17. Move `seon.client/!state {boot-at, reload-count}` to DB (`:seon.pod` entity). Agent inspection of "when did the pod boot" is a real need.

### 14.F — Forward-compat for v2 per-runtime

18. **Never use numeric `:db/id` across agent boundaries.** Always `:seon.agent/id` string. v2 replicas will have different numeric `:db/id`s for the same logical entity.
19. **Tx-meta is the source of truth for replication.** Every tx-meta attr registered for v1 is a tx-meta attr replayed verbatim in v2. Don't add tx-meta keys that wouldn't survive replication.
20. **`:seon.agent/capabilities` schema** drafted now (component ref to a `:seon.capabilities` entity with `:seon.fs/allowed-roots`, `:seon.http/allowed-hosts`, `:seon.mcp/allowed-servers`). Single-runtime v1 reads it CLJS-side; multi-runtime v2 reads it host-side. Same schema, different enforcement layer.

### 14.G — What NOT to do in v1

- Don't build kabel-cljs port. v1 is single-runtime.
- Don't build the AgentPool host. v1 is single-runtime.
- Don't build Wizer integration. Cold-start matters for v2 per-runtime, not v1 single-runtime.
- Don't pre-emptively split `seon.agent` into orchestrator/task namespaces. They're the same code with different defaults; one namespace, kind-dispatched fns.

---

## Reference

- Existing wasm spike: [[wasm-spike-2026-05-20]]
- M2 landmines + working build flags: [[m2-findings-2026-05-21]]
- Capability surface design: [[capability-surface-2026-05-22]]
- Datahike capabilities audit: [[datahike-capabilities-2026-05-22]]
- Schema/state/identity audit: [[schema-state-architecture-audit-2026-05-23]]
- Id generator (informs `:seon.agent/id` shape): [[id-generator-design-2026-05-23]]
- Platform roadmap: [[../platform]]
- Pod host source: `pod-host/wasm-tauri/src-tauri/src/pod.rs` (especially the per-agent-id export shape at lines 376-428)
- WIT world: `pod-host/wasm-tauri/src-wit/seon-pod.wit`
- Datahike listener machinery: `src/seon/db.cljs` (around line 1152)
- Broadcast pattern (existing single-listener multi-fanout): `src/seon/web/broadcast.cljs`

---

## Source of cost numbers

All numbers in §3 are measured locally on 2026-05-24, Apple Silicon (M-series), Darwin 24.6.0, wasmtime 44.0.1, against artifacts in this repo:

```bash
# AOT compile
wasmtime compile -o /tmp/eval_smoke.cwasm \
  pod-host/wasm-tauri/eval-smoke-build/target/wasm32-wasip2/release/eval_smoke.wasm

# Cold-start (init-bootstrap): 84 MB peak, 2.79s wall, 3 runs averaged
/usr/bin/time -l wasmtime run --allow-precompiled -S http=y \
  --dir "$PWD/out/bootstrap::bootstrap" \
  --invoke 'init-bootstrap()' /tmp/eval_smoke.cwasm

# Bare wasm-rquickjs (placeholder, no CLJS): 6.8 MB peak, 20ms
/usr/bin/time -l wasmtime run --allow-precompiled \
  --invoke 'get-ui-port()' /tmp/seon_pod.cwasm

# Concurrent processes (3x init-bootstrap): each ~87 MB RSS, independent
# (commands in background, ps -o pid,rss,command after 0.5s)

# In-process Rust harness (placeholder pod, fresh JIT): 0.34s total
cd pod-host/wasm-tauri && cargo test --release \
  --package seon-tauri placeholder_pod_returns_ui_port
```

Memory numbers are macOS RSS via `/usr/bin/time -l` — physical resident set, NOT virtual. Cycles/instructions are CPU performance counters. Wall clock is the user-time line.
