---
type: research
status: active
tags: [research, agent, flow]
---

# Execution mechanism — loop vs flow vs workers (parallelism + fault isolation) (2026-06-25)

## TL;DR

The owner's real motivation is **true multiprocessor work + fault isolation** —
"run things independently so a random call doesn't lock up the whole system."
With that as the load-bearing criterion the answer changes shape, and one fact
decides it:

> **In ClojureScript, core.async channels give you DECOUPLING and BACKPRESSURE
> but NOT parallelism or isolation.** CLJS core.async has no `thread` — only
> `go`, a state machine running on the single JS event loop (verified:
> `cljs/core/async.cljs` has only `go`; its own comment says "single-threaded
> runtime", async.cljs:345). A hung or CPU-bound call still freezes the one
> event loop. **Channels ≠ parallelism in CLJS.**

So the three real options are:

- **A — single-process loop/async (current).** Simple, durable, all state in the
  DB. But the pod hosts **multiple agents in ONE Node event loop** (confirmed:
  `client.cljs` resumes the whole "agent roster" and arms a wake trigger per
  agent, lines 1911-1917, 2165). **One hung/CPU-bound eval locks ALL agents** —
  and worse, it blocks the very ticker the spec relies on to enforce deadlines.
- **B — CLJS core.async/flow port.** Nicer decoupling + backpressure, but **still
  single-threaded** — does NOT solve the lock-up. A runaway eval still freezes
  everything. flow's thread-pool parallelism (the reason it's tempting) is a
  **JVM-only** property that does not transfer to the pod.
- **C — `worker_threads` / process-per-agent + DB-coordination + a flow-like
  API.** Real OS-thread parallelism + fault isolation: a hung call freezes only
  its worker, which the supervisor can `terminate()`. This is the Erlang/OTP
  let-it-crash + supervision model, and it **extends the architecture we already
  have** — the pod↔wire-server split — by making each worker a pod-like client of
  the single-writer DB.

**Recommendation: C, introduced in two graduated steps.** First, isolate the one
genuinely dangerous operation — the **SCI `eval-batch!`** — into a worker pool
with a hard, terminate-on-timeout deadline. That single move converts the spec's
*soft* deadline into a **CPU-proof kill switch** and directly delivers "a random
call can't lock the whole system," at a fraction of the cost of full
process-per-agent. Second, when agents genuinely need to think/eval
*concurrently*, graduate to **worker-per-agent**, each running the *same simple
loop* (A's design) inside its own isolate, coordinating through the DB. Keep
coordination (wake triggers, DB reads, renders, the ticker, the LLM `await` — all
cheap or already-async) on the main loop. **Do NOT port core.async.flow** — mine
its *supervision/lifecycle patterns* (start-paused, control-priority, error-chan
let-it-crash, ping, compute-timeout) and re-express them over workers + the DB.

---

## 1. The decisive criterion: parallelism + fault isolation

The owner doesn't primarily want prettier dataflow; they want **independence**:
one misbehaving call must not take down the others. Three sub-properties:

1. **Parallelism** — two agents (or two stages) doing real CPU work *at the same
   time* on different cores.
2. **Fault isolation** — a crash, exception, infinite loop, or CPU-bound stall in
   one unit cannot freeze or corrupt the others.
3. **Recoverability** — a hung/crashed unit can be killed and restarted without
   bringing down the system (let-it-crash + supervision).

### Why the current pod fails this (the real, present problem)

`src/seon/client.cljs` boots **every armable agent in one Node process** and arms
a per-agent wake trigger (`(doseq [id ids] (fsm/install-wake-trigger! ...))`,
1911-1917). Every agent's loop, every turn, every `eval-batch!` runs on the
**same single JS event loop**. Consequences:

- A synchronous CPU-bound eval (an LLM-generated `(loop [] (recur))`, an
  accidental `O(n!)`, a giant `pr-str`) **blocks the event loop entirely** — no
  other agent wakes, no HTTP/SSE inspector response, no timers.
- **It even blocks the spec's own safety mechanism.** The spec (§"The one
  ticker") closes overdue runs from an external `setInterval` ticker — but a
  `setInterval` callback **cannot fire while the event loop is blocked by a
  synchronous eval**. So the deadline is unenforceable against exactly the
  failure mode it exists to catch. The spec's deadline is *soft*: it catches a
  slow *async* LLM (which yields the loop), not a runaway *synchronous* eval.

This is precisely the "a random call locks up the whole system" the owner wants
gone, and **no channel API fixes it** — only moving the dangerous work off the
main thread does.

---

## 2. What core.async.flow ACTUALLY is (from the source)

Read: `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
`flow/spi.clj`, `flow/impl.clj`, `flow/impl/graph.clj`.

### The construct (flow.clj:16-53)

> "A library for building concurrent, event driven data processing flows out of
> communication-free functions, while centralizing control, reporting, execution
> and error handling. Built on core.async."

A **flow** = a directed graph of **processes** (*generally, threads* —
flow.clj:20) joined by **channels**, plus a centralized control/report/error
plane. Note the parenthetical: on the JVM each process is a **thread**, which is
where flow's parallelism + isolation come from — and exactly what does not exist
in CLJS.

### The step-fn — 4 arities (flow.clj:163-281, spi.clj)

One fn, four arities — the abstraction worth stealing regardless of mechanism:

| Arity | Name | Signature | Role |
|---|---|---|---|
| 0 | describe | `() -> {:params :ins :outs :workload :signal-select}` | the process CONTRACT |
| 1 | init | `(arg-map) -> state` | initial state (once) |
| 2 | transition | `(state trans) -> state'` | lifecycle: `::flow/resume`/`pause`/`stop` — cleanup/mode |
| 3 | transform | `(state in-id msg) -> [state' {outid [msgs]}]` | main body; **state explicit and RETURNED** |

State is explicit and returned — no hidden mutation (step-functions.md:22), which
is what makes a process pingable (live state) and hot-reloadable (pass a **var** →
redefining updates behavior mid-stream, flow.clj:175-178).

### Supervision / isolation / lifecycle patterns (the owner-relevant part)

Mined specifically for how flow isolates failure and manages process lifecycle:

1. **Start paused; resume on command** (flow.clj:108-109, spi.clj:26-30). A
   process comes up `:paused`, produces no output, and only runs on an explicit
   `::flow/resume`. Lifecycle is a gated state machine, not a free-running thread.
2. **Control-channel priority** (spi.clj:32-34, impl.clj:285-293, 230-232).
   "Whenever reading OR writing any channel a process MUST `alts!!` and include a
   read of `::flow/control`, giving it priority." Even a process *blocked writing
   to a full downstream channel* still responds to pause/stop (impl.clj:230-232).
   **Caveat the owner must hear:** this isolates a process blocked on **I/O**, not
   one stuck in a **tight CPU loop** that never reaches an `alts!!`. A CPU-bound
   `transform` that never yields will not see control on the JVM either — flow's
   answer for that is the `:compute` workload (below), and the pod's answer can
   only be a worker you can `terminate()`.
3. **Error channel = let-it-crash-lite** (spi.clj:56-58, impl.clj:309-317). "If a
   process encounters an error it MUST report it on `::flow/error` and **attempt
   to continue**." The proc loop catches every `Throwable` from `transform`,
   pushes `{:pid :state :count :ex}` to `error-chan`, and **keeps looping**. One
   process's exception never takes down the flow.
4. **Fail-fast on start** (impl.clj:161-163). If a process fails to *start*, the
   impl broadcasts `::flow/stop` to all — supervised teardown.
5. **`:compute` workload + `compute-timeout-ms`** (flow.clj:271-281,
   impl.clj:256-258). For CPU work, flow runs each `transform` in a **separate
   executor thread** and `.get`s the future with a timeout; a timeout is reported
   on `::flow/error`. **This is the JVM equivalent of "offload the risky eval to
   a worker and kill it on timeout"** — and it is precisely the pattern option C
   re-expresses with `worker_threads`, because CLJS has no threads to run it on.
6. **`ping` → live `{:pid :status :state :count}`** (flow.clj:136-140,
   impl.clj:270-276) + `datafy` of the whole flow (impl.clj:86-89) — uniform
   monitoring without bespoke per-process code.
7. **Channels addressed by data coords `[pid io-id]`, never passed** (spi.clj:53)
   — processes are decoupled from each other's identity; the supervisor resolves
   coordinates. (Maps cleanly onto "address an agent by its DB id, route through
   the DB" rather than holding a live channel/port.)

### How Seon already uses flow — JVM track only, paused

`src/seon/flow/topology.clj` + `harness.clj`: every namespace is a flow process
(`namespace-as-process.md`); `topology/request!` = promise + `inject` +
reply-router; backpressure = a queue cap of 32. **JVM-only, and the JVM track is
paused.** The pod is deliberately core.async-free (native `^:async`/`await`).

---

## 3. Three-option evaluation (parallelism + isolation first-class)

| Option | Parallelism | Fault isolation | What it buys | What it costs | Fit to DB-reactive model |
|---|---|---|---|---|---|
| **A — single-process loop/async** (current) | no — one event loop | no — one hung eval freezes all agents + the ticker | dead-simple; all state durable in the DB; one `listen!` + one ticker is the whole active surface; fencing falls out of the run-id | the lock-up the owner wants gone; soft deadline unenforceable vs CPU-bound eval | native — the loop IS a fn of the run's data |
| **B — CLJS core.async/flow port** | no — **still one event loop** (`go` = state machine; no `thread`) | no — a runaway `go`/sync eval still freezes the loop | decoupling + backpressure + a uniform step/ping/inject surface | **reimplement, not port** (flow is JVM-only: `j.u.c` Executors/Future/ReentrantLock + blocking `alts!!`/`>!!`, impl.clj:17-18,230,281 — none exist in CLJS); reverses the core.async-free decision; go-chans don't compose with `^:async`/`await` (bridge Promise↔chan everywhere) — **all that, and it STILL doesn't deliver the goal** | poor: ephemeral channels reintroduce in-process state the DB was chosen to replace, and add no isolation |
| **C — `worker_threads`/process-per-agent + DB-coord + flow-like API** | yes — real OS threads / processes on multiple cores | yes — a hung worker freezes only itself; supervisor `terminate()`s it; the DB stays consistent (single writer) | the owner's actual goal: independence + let-it-crash + supervision; the deadline becomes a **hard** kill | worker orchestration; V8-isolate startup; per-worker SCI bootstrap; `postMessage` structured-clone serialization across the boundary; harder debugging | **best** — extends the existing pod↔wire-server split; each worker = a pod-like DB client; coordination stays in the DB |

### The nuance the owner asked to nail, stated plainly

- **CLJS channels = decoupling + backpressure, NOT parallelism or isolation.** A
  `go`-block is cooperative concurrency on one thread; a CPU-bound block freezes
  it. Verified against the CLJS source (only `go`, no `thread`; "single-threaded
  runtime", async.cljs:345).
- **On the JVM, core.async.flow gets thread-pool parallelism for free** (each
  proc is a thread; `:compute` uses an executor). That is a real reason flow is
  tempting — **and it is exactly the part that does not transfer to the pod.**
- **The thing that delivers the goal is the THREADING/PROCESS model, not the
  channel API.** Node `worker_threads` (real OS threads, isolated V8 isolates,
  `postMessage`) or process-per-agent gives genuine parallelism + isolation. The
  channel-vs-loop debate (A vs B) is orthogonal to it: whatever runs *inside* a
  worker can be the simple loop.

---

## 4. Recommendation — C, in two graduated steps

### Step 1 (do first): isolate the SCI eval into a worker pool with a hard timeout

The single highest-leverage move. Route `seon.eval/eval-batch!` (turn.cljs:329)
into a small pool of `worker_threads`. Each eval runs in a worker; the supervisor
arms a timer and **`worker.terminate()`s on timeout** (or on the run's deadline).
This:

- makes the spec's deadline a **hard, CPU-proof kill switch** — the one thing the
  single event loop cannot do;
- isolates the *only* genuinely dangerous operation (untrusted LLM-generated code
  that can infinite-loop or burn CPU) — everything else on the main loop is cheap
  or async;
- is the direct CLJS realization of flow's `:compute` + `compute-timeout-ms`
  pattern (impl.clj:256-258), which the JVM does with a thread + future and the
  pod must do with a worker;
- is far cheaper than process-per-agent: a *pool* of warm workers amortizes the
  SCI bootstrap, and only the eval crosses the boundary.

### Step 2 (when needed): worker-per-agent

When agents must think/eval **concurrently** (true multi-agent parallelism, not
just isolation), graduate to one worker (or OS process) per agent. Each worker
runs the **same simple loop** (A's design) in its own isolate; the main process
is a **supervisor** that spawns, pings, pauses/resumes, and restarts workers.
Coordination stays in the DB — each worker is a pod-like client of the
single-writer wire-server, reading local lazy db values and writing over the
wire. This is the existing pod↔wire-server split, fanned out per agent.

### The line: needs-a-worker vs fine-on-the-main-loop

| Runs in a worker (CPU-bound or risky — can lock the loop) | Stays on the main loop (cheap or already async) |
|---|---|
| the **SCI `eval-batch!`** (untrusted LLM code; infinite-loop / CPU-bound risk) | wake triggers / `listen!` handlers (DB-snapshot reads) |
| heavy synchronous compute (big `pr-str`, deep walks, render of huge data) | DB reads — lazy db values, sub-ms |
| the bootstrap-CLJS *compile* of risky agent code | cheap section renders / `state-snapshot` fingerprint |
| (Step 2) an agent's whole turn, if agents must run concurrently | the **ticker** + fencing (must stay responsive — that's the whole point) |
|  | the **LLM call** — it's async I/O; `await` yields the loop, never blocks it |

Note the LLM call is *not* the lock risk (it's async). The lock risk is
**synchronous CPU work**, which is the eval. That is why Step 1 targets eval
specifically.

### A flow-like API over workers: `seon.agent.flow`

Wrap worker lifecycle in a thin flow-shaped API so the call sites read like flow
without being flow:

- **`describe`** — a worker's contract (what task kinds it accepts).
- **`spawn`/`start` paused → `resume`** — mirror flow's start-paused gate.
- **control plane** — a dedicated control `MessagePort` the supervisor uses for
  pause/resume/ping (flow's control-priority), **plus `terminate()`** for the
  CPU-bound case the control port can't reach (the honest gap flow has too).
- **`ping` → status/state** — a `postMessage` round-trip returning the worker's
  current run/turn snapshot (flow's `ping`).
- **error/exit = let-it-crash** — listen to the worker's `error`/`exit` events;
  on crash, the supervisor closes the run (run-id fencing in the DB makes a
  restart safe — a late write from the dead worker is rejected) and respawns.
  This is flow's `error-chan` "report and continue" upgraded to true isolation.

This gives the owner flow's *vocabulary and supervision discipline* with real
parallelism + isolation underneath — without porting core.async.

---

## 5. Honest cost of option C

- **V8 isolate startup.** Each `worker_thread` spins a fresh V8 isolate and must
  load the bundle — tens of ms to ~100ms+. Mitigate with a **warm pool** (Step 1)
  rather than spawn-per-eval.
- **Per-worker SCI / bootstrap-CLJS compile-state.** The pod's compile-state (the
  bootstrapped CLJS compiler) is heavy to construct and **cannot be `postMessage`d**
  (it's full of functions/closures — not structured-cloneable). Each worker must
  build its own, costing memory + startup. The pool amortizes this;
  process-per-agent multiplies it. This is the single biggest cost and the main
  reason to start with a *pool for eval*, not a *worker per agent*.
- **`postMessage` serialization (structured clone).** You cannot pass the live db
  value, functions, or compile-state across the boundary. Workers receive only
  serializable **task descriptors** (source string, ns, ids) and read the DB
  themselves over their own wire connection. This actually fits the existing
  model (a worker = a pod-like wire-server client) but means **each worker needs
  its own UDS connection to wire-server**, and results cross back as serializable
  envelopes (eval outcomes, not live values — already how the eval log works).
- **Debugging.** Stacks/console are per-worker; no shared REPL; an error in a
  worker is one `postMessage` removed. Need a story for surfacing worker
  errors/stdout into the inspector (the reactive warning sections can render
  worker-crash datoms — the DB-coordination makes this natural).
- **What's cheap:** coordination stays in the DB, which is *already* the single-
  writer model — so the hardest part of distributed coordination (who writes,
  ordering, fencing) is **already solved**. C is mostly worker orchestration +
  serialization, not a new coordination substrate.

---

## 6. Option-C concept map (workers + DB ↔ flow)

| flow concept | C realization (workers + DB) |
|---|---|
| process = thread | a `worker_thread` (real OS thread / V8 isolate) or OS process |
| step-fn (describe/init/transition/transform) | the per-worker agent loop; `seon.agent.flow` exposes the same arities |
| `::flow/control` + priority | a control `MessagePort` for pause/resume/ping **+ `terminate()`** for CPU-bound hangs |
| start paused → resume | spawn the worker paused; `resume` to begin the loop |
| `error-chan` "report & continue" | worker `error`/`exit` events → supervisor closes the run (DB fencing) + respawns (true let-it-crash) |
| `:compute` + `compute-timeout-ms` | Step 1: eval in a worker, `terminate()` on the run deadline — the **hard** kill |
| `ping` → `{:status :state :count}` | a `postMessage` status request → the worker's run/turn snapshot (or just query the DB) |
| `inject` (external stimulus) | `message!` / a transact (the DB is the queue) |
| channels by `[pid io-id]` coord, never passed | address an agent/worker by its **DB id**; route through the DB, not live ports |
| bounded-channel backpressure | the DB queue + `turn-limit`/`deadline` bounds (coarse, durable) + a bounded worker pool (rejects/queues when full) |
| `report-chan` | the derived `activity-log` (loop.cljs:399-451) + inspector |
| state explicit & returned | the **run entity** in the DB |
| var step-fn → hot reload | upsert-a-symbol / DB-is-the-running-system (per worker re-reads the program graph) |

### What still genuinely needs in-process channels

Inside a single worker, a tight render→eval pipeline is sequential and needs no
channels. **Channels would only earn their place** for sub-ms fan-out among many
units *within one isolate* — which is the case worker-per-agent specifically
*avoids* by putting the parallelism at the worker boundary. So C makes the "port
core.async to the pod" question moot: parallelism comes from workers, durability/
coordination from the DB, and the loop stays simple inside each worker.

---

## 7. Open calls flagged for the owner

1. **Scope of Step 1** — eval-only worker pool vs whole-turn worker. Recommend
   eval-only first (smallest boundary, biggest safety win, cheapest serialization).
2. **Pool vs per-agent for Step 2** — true concurrent thinking (worker-per-agent)
   vs a shared eval pool sufficing for a long time. Recommend measuring real
   contention before paying per-agent SCI-bootstrap cost.
3. **`worker_threads` vs child processes** — workers share memory primitives and
   are lighter; processes give stronger isolation (separate heap, OS-level kill)
   and match "process-per-agent" literally. Recommend `worker_threads` first
   (lighter; `terminate()` is enough for the CPU-bound case).
4. **`:paused` agent state** — flow procs start paused; add `[:enum :idle
   :running :paused :terminated]`? Useful as the supervisor's "hold" command.
   (Touches the spec enum + loop stop policy + ticker skip-set.)
5. **Worker-error surfacing** — render worker crashes as reactive warning
   sections from crash datoms (fits DB-coordination) vs a side channel. Recommend
   datoms + a section.

---

## Cross-references

- Spec: `docs/prds/agent-fsm/agent-runtime-spec.md` (§"The one ticker" — note the
  soft-deadline gap a worker closes; §"What we deliberately DON'T adopt")
- Current loop/turn: `src/seon/agent/loop.cljs:189-278`,
  `src/seon/agent/turn.cljs:329` (the `eval-batch!` call to offload), `:397-455`
- Multi-agent-in-one-process reality: `src/seon/client.cljs:1911-1917, 2117-2274`
  (the "agent roster" boot)
- CLJS core.async (no `thread`): `reference-code/core.async/src/main/clojure/cljs/core/async.cljs`
  (only `go`; "single-threaded runtime" :345)
- flow source: `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
  `flow/spi.clj` (ProcLauncher lifecycle law), `flow/impl.clj:161-163` (fail-fast
  start), `:217-239` (control-priority writes), `:256-258` (`:compute` timeout —
  the worker-offload analogue), `:268-320` (proc loop), `:309-317` (error =
  report & continue)
- JVM-track flow (paused, contrast): `src/seon/flow/topology.clj`,
  `docs/seon/concepts/namespace-as-process.md`, `docs/seon/concepts/step-functions.md`
- DB-reactive doctrine: `docs/seon/concepts/reactive-context.md`,
  `docs/seon/concepts/code-as-data-runtime.md`
