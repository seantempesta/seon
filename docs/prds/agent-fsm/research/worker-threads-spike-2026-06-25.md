---
type: research
status: active
tags: [research, agent, flow]
---

# Worker-threads spike — "are threads cheap?" (measured)

## TL;DR

**Yes, threads are cheap enough.** A bare `worker_threads` worker costs
**~14ms to spawn and ~8MB incremental RSS**. Loading the pod's real bootstrap
analysis caches adds **~17ms and ~8MB more** (full cljs.js compile-state is
heavier — see the SCI/compile-state verdict). **`worker.terminate()` killed a
synchronous `(while true)` hang in 0.8ms while the main thread's heartbeat kept
ticking (91/100 beats during the hang)** — this is the whole point: off-thread
eval is CPU-runaway-proof where the in-loop watchdog is not. Warm
postMessage round-trip is **~0.02ms** with **structured-clone of a realistic
~20KB eval payload at ~0.05ms**. **Step-1 eval-offload pencils out.** A small
warm pool (4–8 workers) is affordable; per-agent-worker is affordable at our
agent counts (tens), per-concern-worker at hundreds gets memory-bound and wants
a shared pool, not 1:1.

All numbers are means of 3 runs on `node v24.2.0`, darwin/arm64. Raw output and
the harness are preserved in
`/private/tmp/.../scratchpad/` (`run-spike.js`, `worker-*.js`, `results.json`);
the measured table is reproduced below.

## What the pod actually evals (so the spike is faithful)

There are **two** interpreter paths in the pod, and the spike targets the
expensive one:

1. **Agent eval — `seon.agent.turn/eval-batch!` → `seon.eval` (cljs.js
   bootstrap).** This is the runaway risk flagged in the execution-mechanism
   research. It compiles agent forms with the **shadow.cljs bootstrap
   compiler**, whose `compile-state` is seeded by `init-bootstrap!`
   (`seon.eval`) reading **`out/bootstrap/ana/*.transit.json`** — 45 files,
   **2.04MB** of analysis caches — into a fresh `cljs.js` state. The compiled
   output defines vars on `globalThis`. A tight CPU loop in an agent form
   blocks the one Node event loop, **including the deadline watchdog**
   (`seon.eval` documents this explicitly: "A tight CPU loop blocks the event
   loop entirely, including the timer, and can NOT be cancelled here. Real
   preemption needs worker_thread").

2. **Live-tile render — `seon.render.sci` (SCI interpreter).** Already bounded
   IN-PROCESS via SCI's `:interrupt-fn` deadline (Layer 1). This does **not**
   need the 15MB bootstrap; it interprets stored source against a reconstructed
   env. Its own docstring notes the residual gap (a native host loop hidden in
   a compiled helper still blocks) → "Bounding that needs a killable worker
   (PRD Layer 2)."

So the per-worker "bootstrap cost" that decides the architecture is the
**cljs.js bootstrap compile-state**, not SCI. The spike measures the dominant,
faithful part of that cost — reading + parsing the real `ana` caches — and
anchors the full-runtime figure against the **live pod's actual RSS**.

## Measured numbers

### Anchors

| Anchor | RSS |
|---|---|
| Empty `node` process | 38.3 MB |
| Main spike process (baseline) | ~41.5 MB |
| **Live pod** (full CLJS: cljs.js + populated bootstrap + SCI + agent loop + HTTP/SSE) | **88.0 MB** |
| Live wire-server (JVM datahike writer, for scale) | 173 MB |

The pod's ~50MB over empty-node is the whole CLJS+compiler+state+server layer.
A worker that replicates only the **eval-relevant** subset (cljs.js compiler +
bootstrap compile-state, no HTTP/agent-loop) lands **between** the proxy figure
below and that ~50MB ceiling.

### 1. Bare worker_threads (floor)

| N | spawn→ready mean | main-RSS delta /worker |
|---|---|---|
| 1 | 15.3 ms | 13.5 MB |
| 10 | 13.8 ms | 8.6 MB |
| 50 | 13.8 ms | 7.9 MB |

Startup is flat at **~14ms** regardless of count (no warm-up penalty at scale).
Incremental RSS settles at **~8MB/worker** (the first worker looks bigger
because it also pulls in shared worker infrastructure).

### 2. Bootstrap-loading worker (per-worker eval-bootstrap cost — proxy)

Each worker reads + `JSON.parse`s all 45 `ana/*.transit.json` (2.04MB) and
retains them, emulating what `init-bootstrap!` ingests into compile-state.

| N | spawn→ready mean | ana-load mean | retained heap /worker | main-RSS delta /worker |
|---|---|---|---|---|
| 1 | 30.3 ms | 16.6 ms | 13.8 MB | 7.2 MB |
| 10 | 31.1 ms | 16.6 ms | 13.8 MB | 7.8 MB |
| 50 | 33.8 ms | 18.3 ms | 13.8 MB | **16.5 MB** |

**Per-worker eval-bootstrap cost (cache ingest only): ~17ms + ~8MB on top of
the bare floor → ~30ms spawn→ready, ~8–16MB/worker.**

> **Honest caveat (under-estimate):** this proxy measures reading + parsing the
> ana caches, which is the dominant *I/O+parse* part of `init-bootstrap!`. It
> does **not** include loading the `cljs.js` compiler runtime itself into the
> worker (in the live pod that code is part of the 70KB `main.js` bundle +
> shadow runtime, and the populated analyzer env). The **realistic full
> per-eval-worker cost is therefore between this proxy (~8–16MB) and the pod's
> eval-relevant footprint (~30–50MB)**. It cannot be measured standalone
> without building a worker-targeted CLJS bundle (out of scope — must not touch
> `src/` or the build) or `require`-ing `main.js` (forbidden — it boots a pod
> and would hit the live wire-server). `sci` is bundled into the CLJS build,
> not a standalone npm dep, so an "SCI as a dep" micro-measure isn't available
> either; the live-pod RSS anchor is the faithful upper bound instead.

> **Shared-process note:** a worker's own `process.memoryUsage().rss` reports
> the *whole shared process* (workers are threads, not processes), so it climbs
> to 760MB at N=50 — that is NOT 760MB of new memory, it is the cumulative
> process view. The real incremental cost is the **main-process RSS delta**
> columns above (~8–16MB/worker).

### 3. Isolation proof (the whole point)

Worker enters a synchronous `(while true)` spin on command; main thread runs a
10ms heartbeat counter, lets it spin 1s, then `terminate()`s it.

```json
{
  "beatsDuringHang": 91,         // ~100 expected — main NEVER blocked
  "expectedBeats": 100,
  "mainStayedLive": true,
  "workerKilled": true,
  "exitCode": 1,
  "terminateMs": 0.82
}
```

**Result: the main thread kept beating (91/100) through a hard sync CPU hang,
and `terminate()` killed the worker in 0.8ms.** Identical across all 3 runs.
This is exactly the property the in-process eval watchdog cannot provide — a
sync loop blocks the event loop including its own timer. Off-thread +
terminate-on-timeout is CPU-runaway-proof.

### 4. Warm round-trip + structured-clone

| metric | value |
|---|---|
| small payload RTT (warm) | **0.02 ms** mean (0.01 min) |
| realistic eval-payload RTT (warm, +1k-iter compute) | 0.05 ms mean |
| structured-clone of realistic payload (~20KB: 20 source forms + meta) | included above (~0.03–0.05ms) |

Round-trip is **negligible** (tens of microseconds). Structured-clone of a
realistic eval task (source forms in, result map back) is sub-0.1ms. The
serialization boundary is a non-issue for eval-shaped payloads.

## SCI-in-worker / compile-state verdict

**Feasible, but each worker must build its own compile-state — the state is not
structured-cloneable.** Two non-cloneable artifacts:

1. **`cljs.js` compile-state** is a CLJS atom holding analyzer namespaces and
   **JS closures**; structured-clone rejects functions. It cannot be passed to
   a worker — each worker runs `init-bootstrap!` itself (the ~30ms + ~8–50MB
   measured above).
2. **Compiled agent vars live on the worker's own `globalThis`.** The
   bootstrap-CLJS eval defines vars by side-effecting `globalThis`; that world
   is per-thread. A worker evaluating an agent form needs the core
   namespaces/fns reconstituted in *its* globalThis (the pod already knows how
   to do this — `replay-program-graph!` / `seed-core!` topo-sort + eval the
   program graph from the DB). The DB itself stays in the main thread; the
   worker gets **read-only db values by structured-clone, or queries proxied
   back** over postMessage.

What **ships per worker** (not cloneable, must be rebuilt): the cljs.js
compiler runtime + bootstrap ana caches (load via `init-bootstrap!`), plus the
core program-graph fns the eval needs (replay from DB, or a frozen snapshot
shipped as source strings). What **clones fine**: the agent's source forms in,
the `{:ok/:value/:error}` result out, plain db value snapshots.

For the **SCI live-tile** Layer-2 case the per-worker cost is much lower — SCI
needs no 15MB bootstrap, just the reconstructed env (already assembled from the
DB index per invocation in `seon.render.sci`). An SCI worker is closer to the
bare floor (~8MB) than the cljs.js figure.

**Verdict: feasible. Needs a per-worker bootstrap step (~30ms cold, run once at
pool warm-up) + a db-access protocol (snapshot-clone or query-proxy). No
blocker.**

## What this means for the architecture

- **Is a warm pool enough?** Yes. Cold per-worker bootstrap is ~30ms (cache
  ingest) and at most pod-scale (~50MB, ~tens of ms for full cljs.js). Paid
  **once at pool warm-up**, amortized to a ~0.02ms warm round-trip per eval. A
  warm pool of pre-bootstrapped workers turns the per-eval cost into a
  postMessage. The only sharp cost is **rebuilding a worker after a
  terminate()** (a runaway killed mid-eval) — but that's ~30ms to respawn +
  re-bootstrap, fine for the rare runaway, and a pool keeps a spare warm.
- **How many can we afford?** At ~8–16MB/worker (SCI / cache-only) you can run
  **dozens** in the pod's memory envelope without strain; at the heavier
  ~30–50MB full-cljs.js figure, a pool of **4–8** is the sweet spot and still
  cheap next to the 173MB wire-server. Spawn cost (~14–34ms) is irrelevant for
  a fixed warm pool.
- **Does Step-1 eval-offload pencil out?** **Yes.** The isolation proof is
  decisive: terminate() kills a sync hang in <1ms with zero main-thread
  disruption, which the in-process watchdog provably cannot do. Round-trip and
  clone costs are negligible. The only real engineering is the per-worker
  bootstrap + db-access protocol, both of which the pod already has the
  machinery for (`init-bootstrap!`, `replay-program-graph!`).
- **Is per-agent-worker (Step 2) affordable?** At our agent counts (a cluster =
  one orchestrator + N task agents, realistically **tens**), **yes** — a
  per-agent SCI/eval worker at ~8–16MB is a few hundred MB worst-case, in
  budget. Per-**concern** worker scaling to **hundreds** would be memory-bound
  on the heavy cljs.js path → use a **shared bounded pool** (work-stealing),
  not 1:1, past ~16–32 live evaluators.

## Recommended Step-1 implementation shape

**Pool.** A fixed **warm pool of N=4 workers** (configurable via
`SEON_EVAL_WORKERS`), each pre-bootstrapped at pod start (run `init-bootstrap!`
+ replay the core program graph in the worker). Keep **1 spare** warm so a
terminate() doesn't stall the queue. Workers are interchangeable; tasks dispatch
to any idle worker.

**Terminate-on-timeout protocol.**
1. Main dispatches an eval task to an idle worker, starts a **main-thread**
   `setTimeout(budgetMs)` (the watchdog lives on the MAIN thread — that's the
   fix; it can't be blocked by the worker's loop).
2. Worker posts back `{::result …}` on success → main clears the timer, worker
   returns to the pool.
3. On timeout, main calls `worker.terminate()` (proven ~0.8ms), records a
   `:seon.eval/timeout` result for the turn, and **respawns + re-bootstraps** a
   replacement worker into the pool (async; the spare covers the gap).
4. Budget reuses the existing `seon.eval` knobs (`!timeout-ms` default 10s,
   one-shot `budget`); the watchdog just moves off-thread.

**Eval task message schema** (structured-clone-safe, namespaced per Data
Rules):

```clojure
;; main → worker
{:seon.eval.task/id          "<uuid>"          ; correlation id
 :seon.eval.task/forms       ["<form-src>" …]  ; parsed agent forms, as strings
 :seon.eval.task/home-ns     'my.kb.foo         ; ctx/home-ns for the agent
 :seon.eval.task/agent-id    "agent-xyz"
 :seon.eval.task/turn-id     123
 :seon.eval.task/db-snapshot {…}                ; cloned read-only db value, OR
 :seon.eval.task/budget-ms   10000}             ; omit → query-proxy protocol

;; worker → main
{:seon.eval.task/id   "<uuid>"
 :seon.eval/n-ok      n
 :seon.eval/n-fail    m
 :seon.eval/results   [{:seon.eval/ok? true  :seon.eval/value <printable>}
                       {:seon.eval/ok? false :seon.eval/error {…}}]
 ;; writes the agent made go back as a tx-data request for the MAIN thread to
 ;; forward to wire-server (the worker never holds the UDS writer):
 :seon.db/tx-data     [ … ]}
```

**DB access.** The worker is **wire-only**: it never opens the UDS to
wire-server (matches the existing `wasm guest is wire-only` rule). Reads = a
cloned db value snapshot shipped with the task (or a query-proxy back to main
for large working sets); writes = returned as `:seon.db/tx-data` for the main
thread to forward through the existing `seon.db/transact!` UDS path. This keeps
the **single-writer** invariant and avoids cloning the compile-state.

## Raw measurement output

See `results.json` and the run transcript in the scratchpad. Key invariants
held identical across all 3 runs: bare spawn ~14ms / ~8MB, bootstrap-worker
~30ms / +8MB cache heap, **terminate() killed the sync hang in ~0.8ms with
91/100 main-thread heartbeats preserved**, warm RTT ~0.02ms.
