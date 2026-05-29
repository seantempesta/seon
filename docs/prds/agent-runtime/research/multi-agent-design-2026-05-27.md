---
type: research
status: draft
tags: [research, agent, architecture, wasm]
---

# Multi-agent architecture design

**Date:** 2026-05-27
**Branch:** `feature/agent-runtime`
**Predecessors (locked):**
`integration-architecture-2026-05-26.md`,
`execution-waves-2026-05-26.md`,
`mvp-completion-plan-2026-05-27.md`,
`research/wasm-spike-2026-05-20.md`,
`pod-host/sidecar-poc/SESSIONS.md`,
`pod-host/sidecar-poc/RECOMMENDATION.md`.

## TL;DR

**Recommendation: Path A* — N V0 CLJS pods as the interim multi-agent
substrate, evolving structurally into Path B (wasm guests) by swapping the
runtime container without changing the agent body's source code.** The "*"
is the load-bearing detail: V0 pod and V2 wasm guest run the **same
shadow-cljs build artifact** (a single CLJS module that imports its DB API
from a swappable shim), differing only in the runtime that hosts it. This
is not aspirational — the pattern already exists at
`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` (a wire-server
DB shim) and the WIT contract at `pod-host/wasm-tauri/src-wit/seon-pod.wit`
already takes `agent-id` on every export (the substrate is **structurally
multi-agent already**). What is missing is (a) the V0 pod becoming
multi-agent in one process via `worker_threads`, and (b) the host process
that owns multiple agent containers.

**The elegant pitch (one sentence):**

> The seon JVM is the substrate; each agent is a CLJS bundle running in
> a swappable container (V0: Node `worker_threads` inside one pod
> process; V2: a wasmtime-hosted `wasm32-wasip2` component inside the
> Rust host); both containers connect to the JVM over the same Transit
> wire, so the agent body's source code is identical and the container
> swap is a deployment-time choice, not a rewrite.

**Headline tradeoff:** ALS removal (mvp-completion-plan Phase 6) is in
**direct conflict** with multi-agent-in-one-process. Either keep ALS
fiber-local (the V0 substrate **already supports concurrent agents** —
see `src/seon/db.cljs:465-474`), or accept that V0 stays one-agent-
per-process and move to multi-process for multi-agent. This doc
recommends **keeping ALS** (or its Promise-context equivalent) until V2
wasm cutover; Phase 6 of the MVP plan should be re-scoped from "delete
ALS" to "replace ALS with an explicit Promise-threaded context that
still survives `await`."

---

## Problem framing

"Multi-agent" in seon means: **N concurrent agent loops** (each running
its own LLM turn cycle, eval-batch, transact, listener fan-out) sharing
**one seon JVM as the substrate** (datahike + schema + wire-server +
inspector), with each agent owning its own **program-graph view**
(`:seon.fn` / `:seon.ns` / `:seon.schema` entities) inside one (shared or
isolated) **session DB**.

The orthogonal axes are:

| Axis | Choices | Today |
|---|---|---|
| **Session topology** | One session per agent (isolation) ↔ N agents per session (collaboration) | Solved: `seon.server.session/!registry` + `!agents` (Item E shipped `882276e`) |
| **Agent process topology** | One pod-per-agent (OS isolation) ↔ N agents-per-pod (process sharing) ↔ N agents-in-fibers (cooperative) | **Unsolved**. V0 pod hardcodes ONE agent via `!agent-conn` defonce |
| **Container substrate** | Node (V0) ↔ wasmtime (V2) | V0 in prod; V2 cutover wave in progress |
| **DB I/O path** | In-process datahike-cljs ↔ wire-server to seon JVM | V0 = in-process today; V2 = wire-server (locked) |

The Layer 1 question (which DB does this agent's tx go to?) is answered
by `seon.server.session` — that's done. The Layer 2 question — **what
process structure hosts N agent loops?** — is what this doc decides.

---

## Current state

### What's already in place (load-bearing, do not rewrite)

| Capability | Location | Notes |
|---|---|---|
| Per-agent session DB routing | `src/seon/server/session.clj` | `!registry` (db-name → conn) + `!agents` (agent-id → db-name). 14-LOC `resolve-agent` is the routing primitive. Wave 4.5 Item E shipped `882276e`. |
| `(seon.session/with-agent id …)` JVM-side scope | `src/seon/session.clj` | Binds `*conn*` + `*current-agent-id*` for the dynamic extent of a body. MCP eval routing key. |
| MCP eval addressed by `:seon.agent/<id>` | `bin/mcp-server` + locked in §13 of integration-architecture | End-to-end verified — two sessions × two agents, datoms land in the right place. |
| WIT contract that takes agent-id everywhere | `pod-host/wasm-tauri/src-wit/seon-pod.wit` | Already multi-agent. `eval-form(agent-id, form, ns)`, `inject-message(agent-id, content, role)`, `query(agent-id, datalog)`, etc. No retrofit needed. |
| Transit wire-server (one JVM, N sessions) | `src/seon/server/wire.clj` + `src/seon/server/codec.clj` | Wave 4a ported verbatim from V2 PoC. 47 tests / 189 assertions. Option B: socket-per-session, one wire-server-instance per session. |
| Sidecar guest overlay (wire-based `seon.db`) | `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` | The drop-in shim that swaps in-process datahike for wire calls. Phase D: 3 wasm agents × 300s, 0 errors. |
| V2 multi-agent stress smoke | `pod-host/sidecar-poc/` Phase D + PF | 3 wasm guests in one session (Phase D) and 2 sessions × 2 agents (PF) both green. |
| Fiber-local agent-id via ALS | `src/seon/db.cljs:466-544` | **The V0 substrate IS designed for concurrent agents.** ALS survives `await` boundaries; `with-agent` opens a scope; `current-agent-id` reads inside it. Explicitly contrasted with `binding` (which clobbers under overlapping awaits — Probe 13 confirmed). |

### What's missing

| Gap | Where | Severity |
|---|---|---|
| `start-agent!` writes to a process-global `!agent-conn` defonce | `src/seon/client.cljs:230` | High — actively prevents N agents in one V0 pod |
| `set! db/*conn* conn` in `start-agent!` | `client.cljs:731` | High — `db/*conn*` is set ONCE at the process level, so per-agent conn binding is impossible without a `binding` scope (which doesn't survive await) |
| Host process that owns N V0 pods OR N wasm guests | Doesn't exist yet | Mid — bin/seon supervises ONE pod. Multi-host is `pod-host/sidecar-poc/rust-host/` for the wasm case |
| Substrate-source seeding tag (`:seon.eval/seed?`) shared across agents in a session | Pending (Wave 4.5 Item B deferred) | Low — only matters when N agents share a session |
| Per-agent CLJS analyzer / compile-state | One `*ns-state*` atom today | High — two agents redefining the same fn name in cljs.user would clobber each other's analyzer state. **This is the trickiest CLJS-side multi-agent issue.** |
| LLM client (DeepSeek HTTP) per-agent rate limiting | `src/seon/ai/deepseek.cljs` | Mid — N agents hammering the API need per-agent budget + global cap |

### The ALS conflict with mvp-completion-plan Phase 6

The MVP plan §5 "Phase 6 — ALS removal" replaces `als-instance` with a
single process-global atom + push/pop. The reasoning given:

> "the CLJS pod is single-threaded and the turn loop is serialized."

**This is only true under one-agent-per-pod.** The minute two agents
run concurrently — both kicking their `run-agentic-loop!` off
overlapping `await` chains — the global atom races. Probe 13 in
`research/impl-finding-tx-context-promise-2026-05-22.md` showed this
empirically: `binding` (which compiles to set! + try/finally on one
global slot) **silently clobbers** under overlapping awaits.

Phase 6 needs a rewrite. Options:

- **Keep ALS** (the current `als-instance` + `agent-id-als`). They work,
  they survive `await`, they're empirically tested. The only "cost" is
  the `node:async_hooks` import — not a real cost for the V0 Node pod.
- **Thread context explicitly through every transact!** — the MVP plan's
  §8.3 alternative. Verbose but correct. Painful to retrofit.
- **Per-eval context atoms keyed by an agent-id arg** — also correct,
  but you have to pass the agent-id explicitly anyway, which defeats
  the point of the abstraction (the whole reason ALS exists is to avoid
  threading agent-id through every call site).

**Recommendation: keep ALS in V0; design WASM context propagation to
match (wasm-rquickjs supports an async-context substrate per the QuickJS
docs, but if not, the WIT layer can carry agent-id on every call — which
it already does).**

---

## Architecture commitments

The 3-5 load-bearing decisions that make this design cohesive:

### C1. The agent body is one CLJS bundle, runtime-agnostic

The agent's source code (`agent.cljs`, `eval.cljs`, `handler.cljs`,
`handlers/*.cljs`) compiles to **one** shadow-cljs artifact that runs
unmodified in either:

- **V0 (today):** a Node `worker_thread` inside the pod process,
  connecting to the seon JVM's wire-server over a TCP/UDS socket from
  the worker.
- **V2 (cutover):** a `wasm32-wasip2` component instance inside the
  Rust host, connecting to the seon JVM via a WIT-imported `db`
  interface that the host proxies to the same wire-server.

The swap point is the `seon.db` namespace. V0 uses in-process datahike-
cljs today; once the wire-shim version of `seon.db` lands (lifted from
`pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs`), V0 and V2
share the same agent-body code.

**Implication:** the multi-agent build is mostly substrate work — the
agent body barely changes. Most agent.cljs code already takes
`agent-id` explicitly (kick handler, with-agent, etc.); the rest reads
via `(current-agent-id)`.

### C2. The agent-id is the only identity that matters across the wire

Sessions are a **storage detail** — they exist because datahike conns
are session-scoped. From outside the substrate (MCP, inspector, host),
addressing is always by `:seon.agent/<id>`. The session is resolved
server-side via `seon.server.session/resolve-agent`. This is already
locked in §13 of the integration architecture and shipped in Item E.

**Implication:** no client (MCP, host, CLI) ever needs to know which
session an agent lives in. The wire-server / WIT interface looks up the
session from the agent-id on every call.

### C3. The seon JVM owns ALL durable state; agents are stateless containers

Datahike conns, schema registry, instrumentation registry, the program-
graph entities, the inspector tx-listener, and the session-entity rows
all live in the seon JVM. An agent body is a **stateless compute
container**: it has compile-state (the analyzer's atom + globalThis),
the per-eval atom (warnings, tx-context, the kick-handler closure), and
nothing else worth persisting. Restart an agent body and replay-program-
graph! reconstructs it from datoms.

**Implication:** killing an agent container does not lose data. Scaling
agents up/down is just spawning/stopping containers. This is why the
ALS / explicit-context debate matters less than it looks: per-eval
state is short-lived; only the agent-id and the session-conn need to
flow through every async boundary, and the wire-server is the canonical
source of both.

### C4. The host is the one process supervising N agent containers

We need a process that supervises N agent runtimes. Today `bin/seon`
supervises ONE pod. The host can be:

- **V0 host (interim):** a thin Node process or even a CLJ `bb` script
  that spawns N V0-pod `worker_thread` instances (or N child Node
  processes, see Path A' below). It feeds each agent its `agent-id`,
  its DeepSeek API key, and its wire-server endpoint. ~200 LOC.
- **V2 host:** `pod-host/sidecar-poc/rust-host/` (renamed `host/` in
  Wave 6). Already supervises N wasm guests; Wave 6 trims JvmSupervisor
  so it connects to the existing seon JVM. ~3h Rust work.

**Implication:** the V0 multi-agent host is a stepping stone to the V2
Rust host; the wire protocol between them is identical (Transit-JSON
over UDS), so once the V0 host exists we can swap containers underneath
it without touching the supervisor logic.

### C5. CLJS analyzer / compile-state must be per-agent in V0 (and naturally is in V2)

This is the gnarly one. V0's `bootstrap-cljs` has ONE compile-state
atom (`repl/ensure-bootstrap!`). Two agents in the same process would
clobber each other's `cljs.user`, their analyzer would see each other's
defns, and `replay-program-graph!` for agent A would re-eval into
agent B's namespace map.

**In V2 this is FREE** — each wasm component instance has its own
QuickJS engine, its own globalThis, its own analyzer state. Two wasm
guests in the same host process are physically isolated.

**In V0 this is the load-bearing reason to choose `worker_threads`
over fibers** for multi-agent. Each Node `Worker` has its own V8
isolate, its own globalThis, its own require-cache. The compile-state
atom is per-worker by construction. Cost: ~30-50 MB RSS per worker
(V8 isolate baseline) + the bootstrap-CLJS analysis-cache footprint
(~10 MB). For 10 agents, ~500 MB. Acceptable for desktop, tight for
shared servers.

**Implication:** V0 multi-agent = `worker_threads`, NOT ALS-based
fibers in one isolate. The ALS substrate solves async-context
propagation INSIDE one agent's call chain; it does NOT solve compile-
state isolation across agents. Use the right tool for each job.

---

## Path analysis

Reviewing the orchestrator's three proposed paths plus an alternative.

### Path A — N V0 CLJS pods (interim)

Three sub-variants depending on how "N pods" is implemented:

**A1 — N child Node processes.** A host process spawns N `node out/client/main.js`
children, each with `SEON_AGENT_ID=<id>` env. Each child opens its own
in-process datahike-cljs conn (today's V0 behavior). The host owns the
seon JVM connection on behalf of each agent (or each agent opens its
own).

- **Pros:** Maximum OS isolation. Crash one agent, others survive. No
  CLJS-side changes if each agent stays single-agent internally.
- **Cons:** N JVMs worth of memory baseline (~50-80 MB per Node child).
  Cold start ~3s per agent. Doesn't address the in-process datahike-cljs
  → wire-server transition.

**A2 — N `worker_thread` instances inside one pod.** Recommended.

- **Pros:** Memory ~30 MB/worker (no full Node baseline), share heap-
  analysis cache where the bundle is identical, single supervisor in
  the parent process, ~500ms warm-up per worker (no Node boot cost
  beyond the parent). Compile-state isolation is automatic (worker =
  isolate = own globalThis). Inter-worker messaging via `parentPort.postMessage`
  is fast.
- **Cons:** Workers can't share `node:async_hooks` ALS state with each
  other (which we don't want anyway). The pod process becomes a non-
  trivial supervisor — needs back-pressure, worker-died restart,
  port juggling.

**A3 — One pod, multi-agent via ALS.** This is "Path C — one pod, ALS-
based concurrency" from the orchestrator's enumeration.

- **Pros:** Lowest LOC. ALS substrate already works.
- **Cons:** **Compile-state collision.** Two agents writing `(def foo …)`
  in their respective `cljs.user` would land in the same analyzer atom.
  Two agents redefining the same fn would race. The ALS handles per-
  request context but does NOT carve out per-agent compile state. To
  fix this you'd need per-agent analyzer atoms keyed by agent-id —
  workable but invasive. Also, a CPU-bound `cljs.js` compile in one
  agent blocks the event loop for all other agents (Node is single-
  threaded outside workers).

### Path B — N wasm guests (V2 endgame)

- **Pros:** Structural isolation (each wasm component = own engine + own
  memory). WIT-typed capability surface. The locked architecture.
  Already proven in Phase D + PF (Rust host with 3 wasm guests, 300s
  smoke, 0 errors).
- **Cons:** Cold-start cost: each wasm instance load is ~250ms + WASI
  ctx ~150ms = ~400ms (per `SESSIONS.md` §4). Bootstrap-CLJS init
  inside QuickJS is unmeasured but probably ~2-5s based on V0 baseline.
  Memory: ~6.8 MB per wasm release component (compressed) ≈ ~20-40 MB
  RSS per instance once instantiated. The `cljs.js` smoke under wasm-
  rquickjs is the still-open go/no-go (per wasm-spike §"Risk: cljs.js
  bootstrap"); we have the eval-smoke build, but a complete
  `start-agent!` under wasm hasn't been verified end-to-end.

### Path C — One pod, ALS fibers (rejected)

See A3 above. Fails on compile-state isolation. Rejected.

### Path D (NEW) — V0 worker_threads as a stepping stone to V2 wasm

**This is the recommendation.** Both V0 worker_threads and V2 wasm
guests have the same architectural shape:

```
   ┌─ Host process ─────────────────────────────────┐
   │   Supervisor (Node script or Rust binary)      │
   │   ┌──────────┐  ┌──────────┐  ┌──────────┐    │
   │   │ Container│  │ Container│  │ Container│    │
   │   │ (worker  │  │ (worker  │  │ (worker  │    │
   │   │  OR wasm)│  │  OR wasm)│  │  OR wasm)│    │
   │   │ agent A  │  │ agent B  │  │ agent C  │    │
   │   └─────┬────┘  └─────┬────┘  └─────┬────┘    │
   └─────────┼─────────────┼─────────────┼─────────┘
             │ Transit/UDS │             │
             └─────────────┴─────────────┴─────────┐
                                                   │
                ┌─ seon JVM ────────────────────┐  │
                │  wire-server (per-session)    │←─┘
                │  ┌─ registry ─┐               │
                │  │ alice  ─► datahike conn │  │
                │  │ bob    ─► datahike conn │  │
                │  └────────────┘               │
                │  master :seon.runtime DB      │
                └───────────────────────────────┘

```

The supervisor and the wire format are identical across A2 and B. The
**container type** is what changes. Migration from A2 → B is:

1. Confirm the agent body's CLJS bundle compiles to wasm-rquickjs
   (existing `:eval-smoke` build already does the hard part — `cljs.js`
   under QuickJS). One open question per wasm-spike §Risk.
2. Replace the Node `worker_thread` Worker constructor in the
   supervisor with the Rust host's wasmtime instantiate call.
3. The agent body's `seon.db` namespace is already wire-shim'd; no
   change.

**Estimated cost of D→B once D is shipped: 1-2 weeks** (cljs.js wasm
smoke + WIT wiring + Rust host trim). This is dramatically lower than
"build B from scratch" (4-6 weeks per wasm-spike) because the entire
multi-agent supervisor + wire substrate is already proven by the V0
worker_thread substrate.

### Comparison matrix

| Property | A1 N children | A2 worker_threads | B wasm | C ALS one-pod |
|---|---|---|---|---|
| Compile-state isolation | ✅ free | ✅ free | ✅ free | ❌ collision |
| Memory baseline / agent | ~80 MB | ~30 MB | ~25 MB | <5 MB |
| Cold start / agent | ~3s | ~500ms | ~400ms (est.) | <100ms |
| OS-level crash isolation | ✅ | partial (worker-died) | ✅ | ❌ |
| Locked-arch compatibility | ⚠ stepping stone | ✅ stepping stone | ✅ endgame | ❌ reverses Phase 6 |
| Time to ship multi-agent | ~1 week | ~1-2 weeks | 4-6 weeks (alpha-blocking) | ~3 days |
| Implementation risk | low | medium (workers are well-trodden) | high (cljs.js wasm) | high (analyzer races) |
| Path to V2 | swap container | swap container | already there | major rewrite |

**A2 wins** because it's the lowest-risk path that:
- ships multi-agent in 1-2 weeks
- preserves the V2 locked architecture (no rewrites later)
- shares the supervisor + wire substrate with B
- isolates compile-state correctly

---

## Answers to specific questions

### Q1. What's the unit of agent isolation?

**Process for the program-graph; "fiber" (Promise-async-context) for
the per-eval context.**

The agent's program-graph (its `:seon.fn` / `:seon.ns` / `:seon.schema`
entities, its CLJS analyzer state, its globalThis namespace map) needs
**process-level or worker-level isolation**. Two agents in one V8
isolate would clobber each other's `cljs.user`. That rules out
Path C / A3.

The per-eval context (which agent-id is making this transact, which
turn-id, which warnings bucket) lives at the **fiber level** — ALS
in V0, the call-stack in V2 (where every WIT call already carries
agent-id). That's not "isolation"; it's just context propagation,
and it's already solved.

### Q2. Can one shadow-cljs build target both Node pod and wasm-rquickjs?

**Yes, with one specific affordance: the `seon.db` namespace must be
wire-driven** (not in-process datahike). The `:eval-smoke` shadow build
already proves cljs.js + bootstrap.node + clojure.string + cljs.test
compile and run in wasm-rquickjs (per `shadow-cljs.edn:97-131`). The
`:sidecar-agent` build proves a sidecar agent compiles to a wasm-ready
shape (per `shadow-cljs.edn:174-181`).

The blocker is the `seon.db` namespace today does `(:require [datahike.api])`
which pulls in datahike-cljs + its konserve backend. Datahike-cljs uses
`async_hooks` and the Node-flavored konserve file backend that needs
WASI-flavored fs. The path forward is **swap in the wire-server shim**
(already exists at `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs`)
as the canonical `seon.db` — both V0 (after this swap) and V2 use
wire-server I/O. The seon JVM is the only datahike host.

**This is also the cleanest path through C3** — the agent becomes
genuinely stateless once it stops owning datahike state.

### Q3. What's the agent body's bootstrap cost?

Measured V0 today (per `start-agent!`): ~2-4s cold (most of it is
`shadow.cljs.bootstrap.node/init` reading the bootstrap analysis cache
plus `replay-program-graph!` re-eval'ing existing entities).

Per-instance projections:

- **A2 worker_thread:** ~500ms warm (bootstrap cache shared via require-
  cache across workers in the parent's heap snapshot), ~3s cold (first
  worker has to load bootstrap).
- **B wasm guest:** ~400ms instantiate + bootstrap-cljs init inside
  QuickJS. Unmeasured for `start-agent!`; the eval-smoke build runs but
  the full agent boot hasn't been timed.

**Warmth pattern (defer until needed):** pre-warm a worker pool at
host startup. The first user request grabs a warmed worker, replaces
it with a fresh one in the pool. ~5 workers warmed at startup gives
sub-100ms agent spawn for the next 5 user requests. Don't build this
until measurement shows pain.

### Q4. How do N agents share the substrate without contention?

Per-component:

- **Datahike conn (per-session):** datahike-jvm's transact is
  serialized internally (single writer fiber). Two agents in the same
  session both transacting take turns at the conn — measured in V2 PoC
  Phase D: writer + mixed agents shared a session, 1828 commits in
  300s, 0 conflicts (they used CAS-pickups). LMDB write lock is
  beneath that; not user-visible.
- **wire-server (per-session socket pair):** the wire-server already
  serializes per-session (Option B in Wave 4a — one wire-server
  instance per session). Two agents transacting in the same session
  queue at the wire-server's request socket, not at the conn.
- **wire-server (cross-session):** independent. Agent A in session X
  and agent B in session Y don't see each other at all at the wire
  level.
- **Schema registry (`seon.schema`):** read-mostly, registered at boot.
  Multi-reader-safe. Agents that `schema/register!` at runtime go
  through the same atom but that's rare and the cost is negligible.
- **Inspector tx-listener:** one tx-listener per agent (see
  `web.inspector.cljs`). Each fires only on its own agent's txs (filtered
  by `:seon.db/agent-id` on tx-meta). N agents = N tx-listeners on the
  same conn; datahike handles that natively.

The contention story is good. The risk is **LLM HTTP** — N agents
hitting DeepSeek concurrently need rate limiting per the API's quota;
that's a per-agent state machine (back-pressure on a 429), not a
substrate-level concern.

### Q5. Where does the agent's program-graph live?

**Per-session.** All `:seon.fn` / `:seon.ns` / `:seon.schema` entities
for agents A and B in session "alice" land in the `:seon.session/alice`
DB. They are tagged with `:seon.db/agent-id` on tx-meta — so audit
queries can filter by author, but the DB itself is one corpus.

This is the **right answer** because:

- Two agents in one session ARE collaborating — they should see each
  other's fns.
- The substrate-source seeding (deferred Item B) writes substrate
  forms ONCE per session, with `:seon.eval/seed? true` and no agent-
  id. All agents in that session share the substrate.
- Agent-defined forms (the agent's own `(defn …)`) carry agent-id and
  are visible cross-agent via the tx-meta join. Conflict resolution
  (two agents redefining the same fn) is "last write wins" — same as
  V0 today.

For **strict isolation** (the agent should not see other agents' code),
spin up a session per agent. That's already supported — the host
decides at `ensure-db!` time which session to assign.

### Q6. What's the wire protocol surface a guest needs?

Today's wire-server handles `q`, `transact`, `transact-batch`, `pull`,
`entity-pull`, `pull-many`, `schema`, `reverse-schema`, `db-filter`,
`q-filtered`, `filter-release` (per PROTOCOL.md). Pub channel pushes
`tx` events.

For multi-agent, the additions needed:

- **`subscribe-tx` with agent-id filter** — today the pub channel
  fans out every tx; a guest doesn't need every other agent's txs.
  Filter at the wire-server, not at the guest. ~30 LOC.
- **`eval-result-broadcast` (deferred)** — if one agent's eval result
  should appear in another agent's render. Probably not needed for
  MVP; cross-agent visibility happens via tx (the eval landed as a
  `:seon.eval` datom, visible via the tx-listener).
- **`agent-attach` / `agent-detach`** — the host tells the wire-server
  "this socket is now hosting agent X." Today the registry is
  populated by `register-agent!` from a JVM-side call (used by Item
  E's MCP routing). For wire-side use, expose an `op :register-agent`
  that calls `seon.server.session/register-agent!`. ~20 LOC.
- **Per-agent listener tag** — when a guest subscribes to tx events,
  pass the agent-id so server-side can filter. ~10 LOC.

The protocol is mostly there. The additions are small.

### Q7. How does Malli instrumentation work across the wire?

**Two-sided.**

- **JVM-side instrumentation:** all `:malli/schema`'d fns in `src/seon/**/*.clj`
  are instrumented at JVM init via the `:seon.dev/instrumentation`
  Integrant component. Independent of agents. Calls into the JVM
  (from anywhere) are validated.
- **CLJS-side instrumentation:** per the MVP plan Phase 3 (shipped
  `06f0226`), every `(defn … {:malli/schema …})` form an agent evals
  through `eval-batch!` auto-instruments the new var via
  `seon.instrument/instrument-one!`. Per-agent (each worker has its
  own analyzer state, see C5). Wire-server fns are CLJ; the CLJS
  agent's calls into them go through the wire, not through the CLJS
  instrument layer — so the JVM-side schemas are what enforce wire
  contracts.

The clean split: **CLJS instruments the agent's own code; JVM
instruments the substrate surface.** No cross-cutting magic.

### Q8. Killer counterexample to the locked V2 design?

I tried hard to find one. The closest:

**Scenario:** Agent CPU-bound work. An agent runs a 30-second
`(reduce + (range 1e9))`. The wasm runtime can't yield to other
agents during that loop unless the host preempts. Wasmtime supports
fuel-based preemption (deterministic), which gives us deadline-style
timeouts (similar to V0's `seon.eval/budget`) — that's good. But it
also means an LLM-driven agent that emits a tight loop locks up its
wasm instance until fuel exhausts. Per-component isolation means
other agents are unaffected; per-agent it means that one agent's eval
is unresponsive to interrupts until the deadline.

Mitigation: fuel limit + interrupt op (already in WIT). Each call site
explicitly times out at, say, 30s. Same model as V0 (per-form eval
timeout). Not a counter-example, just a property to manage.

**Capability latency:** every fs read from a wasm guest goes through
WIT → Rust host → libc. Probably 50-200µs vs direct fs ~5µs. For an
agent that does many small reads (e.g. reading a 5k-line file
line-by-line), that's ~50ms vs ~5ms. Mitigation: chunky reads,
agent-side buffering. Not architectural, just a code pattern.

**Worker-thread limit (V0):** Node `worker_threads` are workable but
not infinite. ~50-100 workers per parent process is the practical
ceiling on most systems. For 10-20 agents (the realistic ceiling
for personal use), no issue. For "100 parallel agents," V0 won't do
it — only V2 wasm will. Mention in the recommended-path forward.

No fatal counter-example. The locked architecture holds.

### Q9. The elegant pitch (one paragraph)

The seon JVM is the **substrate** — it owns datahike, the schema
registry, the wire-server, the inspector, and the program-graph
projections. Agents are **stateless compute containers** that run a
single CLJS bundle in a swappable runtime (Node `worker_thread` today;
`wasm32-wasip2` tomorrow). A **host process** supervises N containers,
feeds each its `agent-id`, and proxies their I/O to the JVM over a
Transit-JSON wire. Because the WIT contract already takes `agent-id`
on every export, and because the JVM-side session registry already
resolves agent-id → session-conn (Wave 4.5 Item E), the substrate IS
structurally multi-agent — what's missing is the container substrate
(workers or wasm) and the host that supervises N of them. Build the
host once; swap the container type when wasm-rquickjs' `cljs.js`
smoke is green.

The five commitments (C1-C5 above) make this cohesive: same bundle
across containers (C1), agent-id is the only cross-wire identity (C2),
JVM owns durable state (C3), one host supervises N containers (C4),
compile-state is per-container (C5).

---

## Source-code findings

### `client.cljs:230, 731` — the single-agent assumption

```clojure
;; client.cljs:230
(defonce !agent-conn (atom nil))

;; client.cljs:727-731 (in start-agent!)
(let [conn (or @!agent-conn (await (open-agent-conn!)))
      _    (reset! !agent-conn conn)
      _    (set! db/*conn* conn)
      ...]

```

This is the load-bearing single-agent assumption. Two `start-agent!`
calls in one process share the conn (because `or @!agent-conn` short-
circuits), then `set! db/*conn*` clobbers the dynamic root. Multi-agent
in one V0 process is **not viable without changing this** — and once you
change it, you've decided per-agent compile state + ALS scoping, which
is the C5 path.

### `db.cljs:481-528` — ALS already does the heavy lifting

```clojure
(defonce ^:private als-instance
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

(defonce ^:private agent-id-als
  (let [AsyncLocalStorage (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (AsyncLocalStorage.)))

```

Comment at line 465:

> "v1 supports concurrent agents in one pod, so a fiber-local primitive
> is required."

The substrate was designed for multi-agent from day one. The
mvp-completion-plan's Phase 6 "delete ALS" would undo this. **Re-scope
Phase 6.**

### `agent.cljs:447-451` — kick handler re-enters ALS

```clojure
;; setTimeout breaks the ALS scope — re-enter `with-agent`
;; (fn [] (db/with-agent id #(run-agentic-loop! input)))

```

This is the pattern: any time the agent's execution flow crosses an
event-loop boundary (setTimeout, fetch callback), the kick handler
re-enters `with-agent id`. That's idiomatic for ALS substrates and
will work in `worker_threads` (each worker has its own ALS) and in
wasm-rquickjs (per QuickJS' async-context substrate, if it works; if
not, the WIT call already carries agent-id explicitly).

### `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` — the wire shim already exists

The V2 PoC's overlay swaps in-process datahike for wire calls. Its
namespace is `seon.db` (overlay-path takes precedence over `src/seon/db.cljs`
per `shadow-cljs.edn:42` comment). Every public V0 datahike call has a
wire equivalent. This is the artifact that lets the V0 pod and V2 wasm
guest share the same agent-body code.

### `pod-host/wasm-tauri/src-wit/seon-pod.wit:200-205` — WIT already multi-agent

```wit
export eval-form:      func(agent-id: string, form: string, ns: string) -> result<eval-result, string>;
export query:          func(agent-id: string, datalog: string) -> result<query-result, db-error>;
export trigger-turn:   func(agent-id: string) -> result<turn-report, run-error>;
export inject-message: func(agent-id: string, content: string, role: message-role) -> result<string, string>;
export inspect-agent:  func(agent-id: string) -> result<agent-snapshot, string>;
export interrupt:      func(agent-id: string) -> result<_, string>;

```

Every export takes agent-id. The locked V2 contract is **already**
multi-agent. The Rust host already routes by agent-id. The wire-server
already resolves agent-id → conn. The MCP server already routes
`:seon.agent/<id>` to `with-agent`. The substrate IS multi-agent. What's
missing is just the V0-side container that hosts N agents.

### `pod-host/sidecar-poc/RECOMMENDATION.md` migration plan day 5

> "Day 5 — multi-agent V0. Spawn N V0 pods sharing one sidecar writer.
> Each pod is a separate wasm component, each owns its conn (one
> subscribe-tx per pod, fan-out per-pod via the overlay's listener
> registry). The Phase D pattern transfers directly."

The V2 PoC team already foresaw this. The recommendation here is to
adopt that plan, swapping "wasm component" with "Node worker_thread"
for the interim. The Phase D pattern transfers directly to both.

### Shadow-cljs build verdict

The existing builds prove the bundle is target-agnostic:

- `:client` — Node pod with full V0 (in-process datahike).
- `:smoke`, `:eval-smoke` — Node-script but stripped for wasm-rquickjs
  consumption. `:eval-smoke` runs cljs.js in wasm.
- `:sidecar-agent` — sidecar guest agent, wasm-ready.
- `:v0-probe` — V0 substrate ns'es going through the overlay seon.db,
  measuring how much V0 code "just works" through the wire shim.

The same source tree produces all of these. **One bundle for N
container types is already real.**

---

## Risks and counter-examples

### R1. wasm-rquickjs `cljs.js` smoke might fail

Open since wasm-spike. The eval-smoke build runs but `start-agent!`
isn't end-to-end verified in wasm. If `cljs.js` chokes on QuickJS,
B is blocked until wasm-rquickjs adds the missing surface (or we
upstream a fix). **Mitigation:** ship A2 (worker_threads) first so
multi-agent ships independent of the wasm risk. If B is still red
12 weeks later, the wasm-spike's "6-8 weeks realistic" estimate has
slipped; A2 keeps the product moving.

### R2. `worker_threads` `node:async_hooks` interop

Each worker has its own ALS instance — not shared with parent. The
agent body's ALS state stays inside the worker; the parent supervisor
talks to the worker via `postMessage`. This is correct, but it means
the parent process can't "see" the agent's `current-agent-id` ALS
state. **Mitigation:** the parent doesn't need to. The agent-id is
known to the parent because the parent assigned it. ALS is for code
inside the worker.

### R3. Per-agent DeepSeek rate limit collision

N agents calling DeepSeek concurrently may hit account-wide rate
limits. Worth a per-LLM-vendor throttle in the host. **Mitigation:**
out of scope for the multi-agent architecture; surface as a follow-up
PRD ("per-tenant LLM quota"). The current per-agent budget pattern
(`seon.ai.deepseek` has an AbortController + timeout) handles single-
agent retry; multi-agent needs a host-side semaphore.

### R4. Inspector pane doesn't yet aggregate multiple agents

`seon.web.inspector` renders one agent's view at `/agent/<id>`. For
N agents we need a dashboard at `/`. **Mitigation:** trivial UI work
once the substrate is multi-agent. Add a section function that
queries `(list-agents)` and renders cards. Pattern matches the
"reactive context — derived by default" CLAUDE.md principle.

### R5. Substrate-source seeding multi-tenancy

The Wave 4.5 Item B "substrate-source seeding" is deferred. When it
lands, seeding runs **once per session**, not per agent. Two agents
in the same session see the same substrate; two agents in different
sessions see separate copies. **Implication:** seeding logic must
write `:seon.eval/seed?` rows without `:seon.db/agent-id` tx-meta, so
they're not attributed to any one agent. Document this in the
seeding PRD.

### R6. MCP server today routes to ONE pod

`bin/mcp-server-cljs` today connects to ONE nREPL on port 7889. If we
have N workers (or N wasm components), MCP needs to route eval requests
to the correct one. **Mitigation:** the MCP server already takes
`session_id=":seon.agent/<id>"` (Item E). Add a layer: the JVM resolves
agent-id → container, the JVM proxies the eval to the right
worker/wasm-instance. The pattern is the same as `with-agent` but
crossing the worker/wasm boundary instead of just rebinding `*conn*`.
~50-80 LOC of router code in the host process.

### R7. The "Slow Is Fast" rule says don't build A2 unless A3 is impossible

CLAUDE.md says "if a generator, fn, schema, or namespace already exists
and you're 'fixing' it, the fix lives in the existing one." Does A2
violate this by duplicating effort that B will subsume? **Answer: no.**
A2 builds the host process (the supervisor logic, the per-agent routing,
the dashboard UI) which is identical for B. The container type is the
only thing that swaps. A2 is the stepping stone, not a parallel branch.

---

## Recommended path forward

Sequenced waves, each independently shippable. Effort estimates are
calendar time for one focused implementer (not parallel agents).

### Wave M0 — De-conflict Phase 6 (1 day, BLOCKING)

**Re-scope the mvp-completion-plan's Phase 6 "ALS removal" to "ALS-
preservation + explicit warning bucket."** Specifically:

- Keep `als-instance` and `agent-id-als` in `seon.db`.
- Delete `warnings-als` in `seon.eval` (replace with an explicit
  per-eval atom passed through `eval-batch!`'s frame). This was the
  good idea inside Phase 6 — the per-eval warnings bucket doesn't
  need fiber-local because it lives on the eval's call stack.
- Document the rationale in `mvp-completion-plan-2026-05-27.md` §5
  Phase 6 update.

**Deliverable:** Phase 6 plan amended; ALS stays. Multi-agent unblocked.

### Wave M1 — Per-agent conn in V0 pod (1-2 days)

**Make V0 pod's `start-agent!` per-agent-conn-aware.** Specifically:

- Delete `!agent-conn` defonce in `client.cljs:230`.
- Stop calling `set! db/*conn*` at the process level. Instead, the
  per-agent conn is stored in `seon.server.session/!registry` (already
  in place on the JVM side) and **looked up via `(seon.server.session/resolve-agent agent-id)`**
  every time an agent needs a conn — or held in a `:seon.agent/conn`
  key on the per-agent atom map.
- Threading: every `(seon.db/*)` call that today reads `db/*conn*`
  reads `(or @!agent-conn (resolve-agent (current-agent-id)))` —
  ALS-tracked.
- Single-agent path remains correct (one agent per pod still works).

This is the architectural commitment C3 reified in V0 code. After this
wave, a single V0 pod has the *internal* shape to host multiple agents
even though the supervisor only spawns one.

**Deliverable:** V0 pod runs unchanged, but with per-agent conn lookup
threaded through. Existing tests pass. (~2 days, careful refactor of
client.cljs + handler.cljs + the kick-handler chain.)

### Wave M2 — Wire-shim `seon.db` in V0 (3-5 days)

**Replace V0's in-process datahike-cljs with the wire-server shim.**

- Lift `pod-host/sidecar-poc/guest-cljs/src-overlay/seon/db.cljs` into
  `src/seon/db.cljs` (replacing the in-process implementation).
- Run the seon JVM as the data host for V0 too. `bin/seon start jvm`
  plus the JVM's wire-server are now the substrate for V0.
- Migrate the V0 `:memory` test-conn pattern to either (a) a `:memory`
  konserve config inside the JVM, (b) a JVM-side test fixture, or (c)
  retain a stub for unit tests that don't need the wire.
- This is the V0 → V2 migration day 1-2 from RECOMMENDATION.md.

**Deliverable:** V0 pod no longer owns a datahike conn. The seon JVM is
the only DB host. Memory baseline of V0 drops; cross-agent visibility
becomes possible (because all agents share the JVM's view).

**Risk:** existing V0 tests that assume in-process datahike need to be
ported. ~100-200 tests touched but mostly mechanical.

### Wave M3 — Host process supervising N worker_thread agents (1 week)

**Build the multi-agent host.** Concretely:

- New `src/seon/host.cljs` (or `bin/seon-host` Node script) ~300 LOC:
  - At startup, reads a `seon-host.edn` config naming N agents
    (each with `:agent-id`, `:session`, `:llm-config`).
  - For each agent, `seon.server.session/ensure-db! + register-agent!`
    (via wire RPC into JVM).
  - For each agent, spawn a `worker_thread` running the same agent
    bundle (`out/client/main.js`), passing `agent-id`, `session-name`,
    and the JVM wire-server endpoint as workerData.
  - Routes MCP eval requests by `agent-id` → worker's `postMessage`
    channel.
  - Dashboard at `/` listing agents + their statuses (a section fn
    on `:seon.runtime`).
  - Restart-on-die for worker_threads.
- Worker side: the V0 bundle's `start-agent!` now reads `workerData`
  to know its agent-id + session, opens a wire-server connection
  (NOT a local datahike conn), and runs the V0 agent loop.

**Deliverable:** `bin/seon start host` brings up 3 agents in one
process. `/agent/alice` and `/agent/bob` and `/agent/carol` all render
in the inspector. MCP eval to each routes correctly. ~1 week.

### Wave M4 — wasm-rquickjs `start-agent!` smoke (1 week, RISK GATE)

**Verify the existing agent bundle runs in a wasm guest.**

- Take the M3 worker_thread bundle and build it as a wasm-rquickjs
  component. Use the existing `:sidecar-agent` build pattern.
- Wrap the bundle in the WIT contract from `seon-pod.wit` (already
  exists).
- Run `start-agent!` end-to-end inside `wasmtime` CLI. Drive a couple
  of stub-LLM turns. Verify datoms land in the JVM session DB.

**Go/no-go.** If green, proceed to M5. If red, debug wasm-rquickjs
or upstream a fix; M3 keeps multi-agent shipping in the meantime.

### Wave M5 — Swap worker_thread → wasm container in host (1-2 weeks)

**Build the Rust host that supervises N wasm guests.**

- Resurrect / generalize `pod-host/sidecar-poc/rust-host/` per
  Wave 6 of the integration-architecture plan. Drop `JvmSupervisor`;
  connect to the existing seon JVM's wire-server.
- The host's per-agent supervisor logic is **identical** to M3 (same
  config, same wire endpoint, same MCP routing) — only the container
  instantiation differs (wasmtime instead of worker_threads).
- A flag (`--container=worker|wasm`) lets us A/B them.

**Deliverable:** `bin/seon start host --container=wasm` brings up the
same 3 agents as M3, but in wasm components. Phase D + PF smokes
retarget the new host. ~1-2 weeks.

### Wave M6 — Tauri shell + capability prompts (1-2 weeks)

Out of scope for multi-agent per se; brought in here because it's
the natural V2 endgame. Per wasm-spike §"Smallest-demonstrable-
milestone path" steps 4-6. ~1-2 weeks.

### Total timeline

| Phase | Calendar | Cumulative | What ships |
|---|---|---|---|
| M0 | 1 day | 1 day | Phase 6 re-scoped; ALS preserved |
| M1 | 2 days | 3 days | V0 pod per-agent-conn internally |
| M2 | 3-5 days | ~8 days | V0 uses wire-server; JVM is the data host |
| M3 | 1 week | ~15 days | **Multi-agent shipping (worker_threads)** |
| M4 | 1 week | ~22 days | wasm `start-agent!` smoke green |
| M5 | 1-2 weeks | ~32 days | **Multi-agent on wasm (V2 container cutover)** |
| M6 | 1-2 weeks | ~46 days | Tauri shell + native UX |

**Multi-agent ships at M3 (~3 weeks).** V2 cutover at M5 (~5 weeks).
This is consistent with the wasm-spike's "4-6 weeks to alpha" estimate
and faster than building B from scratch.

---

## Appendix: external research

No external consultation was performed for this design. The source
material in-repo (wasm-spike, integration-architecture, SESSIONS.md,
RECOMMENDATION.md, mvp-completion-plan, the V0 source) was sufficient
to plant the flag. If the reviewer wants a Gemini cross-check, the
question to ask would be:

> Given (a) Node `worker_threads` for multi-agent CLJS isolation today,
> (b) wasmtime + wasm-rquickjs for multi-agent CLJS isolation tomorrow,
> and (c) a Transit-over-UDS wire protocol shared across both — is
> there an architectural seam I'm missing where the worker_threads
> substrate creates a sunk cost that the wasm cutover invalidates?

My answer (without Gemini): no, because the substrate IS the supervisor
plus the wire protocol, and the container type is a leaf detail. But
it's worth verifying.

---

## Open questions for Sean

1. **M1 ALS scope.** Wave M0 + M1 preserves ALS in V0 forever. Is that
   acceptable, or do you want a path to "eventually no `node:async_hooks`
   anywhere" (in which case M5 wasm-cutover is the deadline — wasm-
   rquickjs may or may not have an ALS equivalent; the WIT call
   carries agent-id explicitly so we could thread it through every
   transact instead)?

2. **M3 container choice.** Worker_threads vs N child Node processes
   (A1). Worker is recommended (smaller memory, faster spawn) but
   child-processes give better OS-level crash isolation. For desktop
   use either works; for a future shared-server deployment, child
   processes are safer. Pick one for M3; A1 is ~20% more work than A2
   to ship.

3. **M4 risk acceptance.** wasm-rquickjs's `cljs.js` smoke is the open
   risk. If M4 is red 3 weeks in, do we keep pushing on it, or fall
   back to A2 (worker_threads) as the production substrate
   indefinitely, treating V2 as a research target? My recommendation:
   timebox M4 at 2 weeks. If still red, ship multi-agent on M3 + go
   back to V2 in a separate quarter.

4. **Per-agent vs per-session isolation default.** When a user creates
   an agent without specifying a session, should it get its own
   session (isolation) or join the default session (collaboration)?
   Current Item E test pattern is "one session, two agents" but the
   product question is which is the default UX. My recommendation:
   own session by default; explicit opt-in to collaboration.
