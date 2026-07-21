---
type: prd
status: draft
tags: [prd, agent, database, flow]
---

# Platform V2 — Node-first plan (V2.0 → V2.1) — 2026-06-03

> **Audience:** Platform track + MVP track. This is the shared-alignment doc.
> It sets the V2 shape, the Node-first decision, the reuse/new split, the phase
> plan, REPL access, and the track-coordination rules. Detailed plumbing lives
> in [clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md);
> the reactive design lives in [reactive-agent-topology](reactive-agent-topology.md);
> the cutover checklist in [../../../client-runtime/docs/CUTOVER.md](../../../client-runtime/docs/CUTOVER.md).

## The mission

Launch *many* **clusters** — each a single datahike DB + N agents collaborating
on a shared DB toward a different task — with per-cluster metrics, where every
core DB function (incl. listen/broadcast routing) works reliably under quality
tests. One JVM hosts many DBs. This is the production load shape: each user gets
their own DB + their own agent population.

## The decision: Node-first, then containerize

**V2.0 — "prove the architecture":** agents run as **Node CLJS processes** (no
wasm sandbox) against the JVM multi-DB wire-server. Trusted / single-user
posture. Goal: prove the whole stack — multi-DB, reactive engine, clusters,
real LLM agents, REPLs, metrics — end to end.

**V2.1 — "harden the boundary":** swap the Node process for a `wasm32-wasip2`
guest in wasmtime; add WIT-typed capabilities. Same wire protocol, same agent
CLJS, same reactive engine, same cluster config. Multi-tenant-safe against
untrusted agent code.

### Why Node-first is the right call (not just a shortcut)

The wasm sandbox is the source of nearly every hard, slow, risky constraint:

| Constraint | wasm guest | Node process |
|---|---|---|
| Timers | `setTimeout`/wasi:clocks HANG under wstd | work natively |
| Reactive loop | needs cooperative `block_on` redesign (~5–8 days) | trivial async/await |
| REPL failure domain | single fiber → in-guest REPL shares fate; needs host watchdog + epoch interruption | separate OS process → own REPL, `SIGKILL`+restart, `--inspect` breaks into stuck loops |
| LLM HTTP | needs WIT capability or Rust proxy (unbuilt) | native `fetch` — V0 deepseek client already runs in Node |
| Iteration | shadow→wasm-rquickjs→cargo, ~6.8MB, non-reproducible builds | V0 dev loop |
| Wire quirks | BigInt coercion at the WIT boundary | none |

Node-first front-loads all the **reusable, high-value** work and defers the
**sandbox-specific** work. When we port to wasm (V2.1), we debug only sandbox
quirks against an already-proven architecture — not the design.

### Node-first IS the track convergence

V2.0 = **MVP's real agent loop repointed at Platform's wire-server.** Today the
two tracks are: MVP = a real LLM agent in a Node pod with *in-process*
datahike-cljs; Platform = a multi-DB JVM wire-server with *synthetic* agents.
The Node phase joins them: MVP's `agent.cljs`/`eval.cljs`/deepseek loop, with its
`seon.db` pointed at the wire-server over a socket instead of in-process
datahike, run as N processes against one JVM. MVP's agent work is the input to
V2.0 — the tracks come together here, they don't fork.

## Locked decisions (2026-06-03, with Sean)

- **Name:** *cluster* = DB + N agents + task + metrics. *session* = the
  DB-isolation boundary (one cluster's DB). *agent* = one runtime instance
  (Node process now, wasm guest later).
- **Isolation now:** one JVM, many DBs. Agents run outside the JVM, so the JVM
  only runs datahike (pure queries) → robust. **Later:** process/instance-split
  for crash blast-radius; the single-DB case is the degenerate path, kept free.
- **Concurrency:** no core.async time-slicing. Per-conn write serialization
  (datahike's own model) + parallel immutable-value reads, thread-per-request
  dispatched by db-name. Wire-server stays flow-free by design.

## Reuse vs new (the work)

| Piece | Status | For V2.0 |
|---|---|---|
| Multi-DB wire-server (P1) | building | shared by both runtimes |
| Real agent loop / turn driver / LLM client | exists in V0, runs in Node | **reuse** |
| Host-side reactive engine (Posh in `listen!`) | other track, JVM-side, runtime-agnostic | shared |
| Wire-op contract (generative tests) | **done — 68 tests / 268 assertions green** | shared |
| **Node-side wire client** (CLJS: UDS + CBOR + Transit; mirror JVM smoke client `src/seon/server` `:client`) | new | small–medium |
| Repoint agent `seon.db` → wire client (overlay mechanism built for this) | overlay exists | medium |
| Node agent supervisor (spawn N per cluster, bind db-name + REPL port) | new | small–medium |
| Reactive listener over socket (Node — timers OK) | new, trivial vs wasm | small |
| Per-agent REPL (socket-REPL/nREPL or `--inspect` per process) | new — **verify ergonomics** | small–medium |

Everything in the "shared" rows carries forward to V2.1 unchanged.

## Phase plan (Node-first)

- **P0 — wire-op contract tests. ✅ DONE.** Generative Transit roundtrip
  (class-preserving) + `handle-op` invariants (write→read, basis-t monotonic,
  reads don't advance basis-t). 68/268 green, no production bugs.
- **P1 — multi-DB wiring (the shared seam).** `wire.clj` resolves conn-per-request
  via `session.clj`; real db-name on broadcast events; per-DB routing; the
  per-conn `d/listen!` hook the reactive engine plugs into; Rust host flips
  `SessionRegistry` from spawn-per-session to one-writer + DB-registry + pub
  demux by db-name. **P1 step 0:** dev-mode, localhost-bound, flag-gated nREPL
  in the wire-server — one REPL reaches every cluster via `!registry`; makes the
  rest REPL-driven. Tests: two DBs in one process, zero cross-bleed; broadcast
  routing; listen! hook.
- **P2 — cluster config + launch.** Declarative `clusters.edn`
  (`{:name :db :agents [{:id :role :model :prompt :tools}] :task :metrics}`);
  supervisor spawns/teardowns Node agents reliably + idempotently, each bound to
  a cluster, each with a REPL port. Tests: launch N from config, each isolated.
- **P3 — real agent in a Node process + LLM.** Repoint the V0 agent loop at the
  Node wire client; LLM HTTP is native (no capability work in V2.0). Tests: an
  agent completes a real LLM turn, persists turn datoms, emits tx events.
- **P4 — metrics / measurement.** Per-cluster tx/latency/token/task-completion,
  comparable across clusters — "measure how well they perform."
- **V2.1 — wasm port.** Swap Node runtime for wasmtime Store; WIT-typed
  capabilities (incl. the LLM HTTP capability + the in-guest REPL + the
  cooperative-block reactive loop, all deferred from V2.0). Architecture already
  proven, so this is sandbox-quirk debugging only.

## REPL access (summary; full design in research doc)

Hard requirement: **the diagnostic plane is external, with an independent
failure domain from the data path.**

- **JVM/DB-server REPL** — dev-mode, localhost, flag-gated; one REPL → every
  cluster via `session.clj` `!registry`. The always-available external
  diagnostic. (P1 step 0.)
- **DB-as-telemetry** — agent eval is recorded as `:seon.eval` datoms; the JVM
  REPL (a different process) can inspect what any agent did/is doing by querying
  the DB, without touching a wedged agent. Agents write a liveness signal
  (heartbeat datom / last-wake basis-t) so "stuck" is detectable by query.
- **Per-agent REPL** — in V2.0 this is **trivial and independent**: each agent
  is its own Node process with its own REPL port; `--inspect` breaks into a
  stuck event loop; `SIGKILL`+restart is clean (state durable in the DB). This
  is the direct answer to "if it gets stuck, how do we know and fix it" — the
  thing that's *hard* in wasm is *free* in Node. The in-**wasm** in-guest REPL
  (single fiber → host watchdog + epoch-interruption preemption) defers to V2.1.

Full investigation + the watchdog/recovery design (applies to both runtimes):
`research/repl-access-design-2026-06-03.md` (in progress).

## Track coordination

- **One branch (`feature/agent-runtime`), both tracks.** Don't fork — V2.0 is
  the convergence (MVP's agent loop + Platform's server). Lane discipline holds:
  `src/seon/server/` + `guest-cljs/` + Node-host = Platform; `src/seon/*.cljs` =
  MVP; CLJS/CLJ siblings + the `src-overlay` mechanism keep the two db layers
  from colliding. The real merge is the **cutover** (delete V0 pod, one atomic
  commit) once V2.0 is proven — a convergence, not a split.
- **Shared seam = P1.** I provide the per-conn `listen!` hook + real db-name
  tagging + per-DB routing + the kept raw `tx` event (for dedup/catch-up); the
  reactive track builds the engine inside the hook + defines the "changed-scouts"
  event payload.
- **The one hazard — entity schema (reactive gap b).** Agent/scout/summary/fn
  entities: **decide reuse-V0-schemas (promote `:seon.agent`/`:seon.fn` to
  shared) vs fresh-server-side BEFORE either track writes schema.** Per "don't be
  a dumbass," reuse if portable. This is the single place the tracks will
  contend; resolve it by decision, not by branch.
- **Build:** the guest `.wasm` rebuild shares the live shadow-cljs server
  (:9630). Coordinate timing; back up any working `.wasm` before rebuilding.
  (Less pressing under Node-first — wasm builds move to V2.1.)

## Open / to verify

- Cleanest CLJS Node wire-client shape (mirror `src/seon/server` `:client`).
- Per-Node-process REPL ergonomics (self-hosted CLJS nREPL vs socket-REPL vs
  `--inspect`).
- The gap-b schema reuse decision (needs both tracks + Sean).
- Spawn-budget / cascade-depth cap before multi-cluster (quiescence; "agents
  spawn agents" × "many clusters" = runaway risk).

## References

- [clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md)
  — P1 plumbing + the reactive seam.
- [reactive-agent-topology](reactive-agent-topology.md) — scouts/orchestrators,
  Posh host-side engine (other track).
- [research/reactive-db-sandbox-design-2026-05-29](research/reactive-db-sandbox-design-2026-05-29.md)
  — the wasm reactive plumbing that V2.1 inherits.
- [research/repl-access-design-2026-06-03](research/repl-access-design-2026-06-03.md)
  — REPL access design (in progress).
- [../../../client-runtime/docs/CUTOVER.md](../../../client-runtime/docs/CUTOVER.md)
  — the V1→V2 retirement checklist.
