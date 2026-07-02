---
type: research
status: active
tags: [research, agent, flow]
---

# Worker-pool patterns (piscina + tinypool) → Seon Tier-1 isolation

Code dive into the two vendored worker pools to extract the concrete patterns
for Seon's Tier-1 isolation layer: a `worker_threads` pool that runs the SCI
`eval-batch!` (and later whole agent turns) off the main thread. Read-only
extraction; this is Phase-2 implementation input.

Sources:

- `reference-code/piscina/src/**` — piscina **5.1.1** (full-featured, ~2.3k LOC TS)
- `reference-code/tinypool/src/**` — tinypool **2.1.0** (a trimmed fork of
  piscina's core + a few additions vitest needs)

## TL;DR

- Both pools are the **same skeleton**: a min/max set of `worker_threads`, a
  task queue, a least-busy balancer, an Atomics fast-wakeup path, and an
  `error`/`exit` → respawn supervisor. Tinypool is that skeleton minus the
  observability, plus worker-recycling.
- **Correction to the brief:** `maxMemoryLimitBeforeRecycle` is a **tinypool**
  option, NOT piscina. Piscina 5.1.1 has no memory-recycle — it relies on Node
  `resourceLimits` (heap cap → worker crashes → the `exit` handler respawns).
  The recycle-after-task-done machinery we want to copy lives in
  `reference-code/tinypool/src/index.ts:1046` (`shouldRecycleWorker`) +
  `:948` (recycle inside the task done-callback).
- The single most load-bearing pattern for us: **`terminate()` is the only
  CPU-proof kill, and it is invoked the same way for an aborted task and for a
  crashed worker** — remove the worker, fail its in-flight task, respawn to
  `minThreads`. That is exactly our deadline-watchdog + restart-from-DB story.
- Because our workers are **DB-stateless CLJS** (a worker is a pure fn of the
  datahike basis), the "swap a worker without dropping in-flight work" dance is
  trivial for us: recycle = terminate + respawn + the new worker re-reads the
  DB. No transferList, no state handoff, no SharedArrayBuffer-for-data.
- **Do NOT copy the Atomics wait-loop** — `worker.ts:126 atomicsWaitLoop`
  blocks async operations in the worker while it waits for the next task. Our
  eval is `^:async`/`await`; the event-driven `port.on('message')` path is the
  correct one. (Piscina even gates atomics off for async work; tinypool exposes
  `useAtomics:false`.)

## Patterns → our model

| # | Pattern | piscina / tinypool source | Maps to our worker tier | Adopt / Adapt / Skip |
|---|---------|---------------------------|--------------------------|----------------------|
| 1 | **Warm pool** — spawn `minThreads` at construction; during `startingUp` mark them ready immediately instead of awaiting the ready handshake | `index.ts:217-219` (`_ensureMinimumWorkers` in ctor), `:266-275` (startingUp marks ready + `queueMicrotask`); tinypool `:779-783` | Warm 4-8 SCI-ready workers pre-bootstrapped so the ~30ms SCI init is paid once, off the critical path | **Adopt** |
| 2 | **Bootstrap-preload** — worker preloads/caches the task handler on the startup message before posting `ready` | `worker.ts:95-110` (`getHandler` on `StartupMessage`, then post `{[READY]:true}`) | Worker boots → load CLJS bundle → build the SCI cage/ctx → post ready. The "handler" is our fixed `eval-batch!`, bundled, not file-loaded | **Adapt** (handler is fixed, drop the `import(filename)` machinery) |
| 3 | **Acquire / dispatch / release** — balancer picks a ready worker; on done, `taskDone` re-marks it available and drains the queue | `index.ts:397-447` (`_onWorkerAvailable` + drain loop), `:449-474` (`_distributeTask` via balancer), `worker_pool/base.ts:93` (`maybeAvailable`) | Same loop. With single-concurrency the balancer is "any idle worker" | **Adopt** (drop the pluggable `loadBalancer`) |
| 4 | **Worker recycle on memory bound** — each response carries `usedMemory: heapUsed`; after the task's done-callback, `shouldRecycleWorker` checks the bound and, if over, removes+respawns | tinypool `entry/worker.ts:118` + `:138` (report heapUsed), `index.ts:561-562` (`_handleResponse` stores `usedMemory`), `:1046-1068` (`shouldRecycleWorker`), `:948-953` (recycle in done-callback then `_ensureMinimumWorkers`) | Our "terminate + restart-from-DB". Recycle runs **after** the result is captured in the callback, so nothing in-flight is lost. New worker reads known-good state from datahike — no state to carry | **Adopt** (this is the key copy; even simpler for us) |
| 5 | **Isolate-every-task recycle** — `isolateWorkers:true` recycles the worker after *every* task | tinypool `index.ts:1052-1055` | Optional "fresh cage per turn" mode if SCI eval proves leaky; our default is recycle-on-memory-bound or recycle-every-N-turns | **Adapt** (keep as a flag, default off) |
| 6 | **Task cancellation via AbortSignal** — `onAbort`: if running, `_removeWorker` (which calls `worker.terminate()`) + `_ensureMinimumWorkers`; if still queued, remove from queue; reject with `AbortError` | `index.ts:528-554` (onAbort branch), `worker_pool/index.ts:102-117` (`destroy()` = `terminate()` + `port.close()` + fail taskInfos); tinypool `index.ts:963-985` | **The deadline watchdog.** Per-run `AbortController`; watchdog `setTimeout(deadline)` → `abort()` → pool terminates the worker → run closes `:deadline-exceeded`. `terminate()` is the CPU-proof kill (our measure: 0.8ms on a sync hang) | **Adopt** |
| 7 | **Backpressure / bounded queue** — `maxQueue` cap; reject when queue ≥ capacity; `needsDrain`/`drain` events; `concurrentTasksPerWorker` | `index.ts:556-597` (queue+capacity+reject), `:604-631` (`_maybeDrain`/needsDrain); tinypool `:1076-1080` (simpler `_maybeDrain`) | Many agents/components contend for the pool. Bounded queue + reject-at-limit gives backpressure to the agent loop. `concurrentTasksPerWorker = 1` (SCI eval is CPU-bound) | **Adopt queue+cap; Skip the drain *events*** (derive contention in the inspector instead) |
| 8 | **Crash supervisor** — worker `error`/`exit` → snapshot+clear its taskInfos, `_removeWorker`, respawn to `minThreads`, fail the in-flight tasks; `workerFailsDuringBootstrap` flag stops respawn storms | `index.ts:326-366` (`onWorkerError`/`onWorkerExit` → `_onError`); tinypool `:815-833` | restart-from-DB supervisor: fail the in-flight turn, respawn, the new worker re-derives from the DB. Keep the bootstrap-failure circuit breaker so a broken bundle doesn't spin | **Adopt** |
| 9 | **Terminate-timeout** — if `worker.terminate()` itself hangs, reject after `terminateTimeout` | tinypool `index.ts:517-522` | Defense for a wedged thread; pairs with our watchdog | **Adopt** (cheap, tinypool-only) |
| 10 | **Graceful destroy/close** — drain queues with errors, terminate all, await `exit`; `close({force})` races a `closeTimeout` | `index.ts:633-726`; tinypool `:1082-1102` | Pool shutdown on cluster stop. We want the simple `destroy()` (terminate-all + await exit); skip the `force`/timeout-race close | **Adapt** (keep `destroy`, skip `close({force})`) |
| 11 | **Atomics fast-wakeup** — SharedArrayBuffer + `Atomics.wait/notify` to skip the C++→JS event boundary on dispatch | `worker.ts:126-175`, `worker_pool/index.ts:177-178` | An optimization for sub-ms task turnover; **conflicts with async eval** (blocks the worker's async ops). Our tasks are 30ms+ | **Skip** (use `port.on('message')`) |

## The minimal essential core (what tinypool's fork tells us)

Tinypool is the controlled experiment: it forked piscina's core and shipped at
38KB. **What it KEPT** = the irreducible pool:

- min/max worker set + `_ensureMinimumWorkers` (`index.ts:706-710`)
- task queue (ArrayTaskQueue/FixedQueue) + bounded `maxQueue`
- least-busy distribution + `AsynchronouslyCreatedResourcePool` (pending/ready sets)
- AbortSignal cancellation (`:963-985`)
- `error`/`exit` → respawn supervisor + `workerFailsDuringBootstrap` breaker
- `destroy()` terminate-all

**What it ADDED** (the things vitest needed that piscina core lacked):
`maxMemoryLimitBeforeRecycle`, `isolateWorkers`, `runtime: child_process`,
`terminateTimeout`, an explicit `recycleWorkers()` API, `teardown` hook.

**What it CUT from piscina**: the histogram/timing/`utilization` telemetry
(`histogram.ts`), the pluggable `loadBalancer` abstraction, the
`needsDrain`/`drain` backpressure *events*, `niceIncrement`, and most of the
elaborate `close({force})` timeout race.

Takeaway: the smallest correct pool for us is **tinypool's KEPT set + its
recycle**, and we can cut even more than tinypool did (below).

## Recommended shape for `seon.agent`'s worker pool

### Sizing

- **Warm, mostly-fixed set: `minThreads` 4, `maxThreads` 8.** Each worker is
  ~8MB / ~30ms bootstrap; a warm 4 amortizes the SCI init, burst to 8 under
  contention. `idleTimeout` to shed back to 4. `concurrentTasksPerWorker = 1`
  (CPU-bound SCI eval — one turn per thread).

### Worker boot (the ~30ms, paid once)

```
worker start
  → load CLJS bundle
  → build the SCI cage (eval ctx + allowed var/ns surface)
  → postMessage {:seon.worker/ready true}
```

### Eval-task message schema (namespaced EDN, structured-clone — no transferList)

Request (main → worker):

```clojure
{:seon.worker/task-id   "…"            ; correlation id
 :seon.worker/kind      :eval-batch    ; later :agent-turn
 :seon.worker/forms     ["(…)" "(…)"]  ; or a turn descriptor
 :seon.worker/db-basis  <tx-id>        ; datahike basis the worker reads from
 :seon.worker/agent-id  :seon.agent/…  ; whose turn (for the supervisor/inspector)
 :seon.worker/deadline-ms 30000}        ; wall-clock budget for the watchdog
```

Response (worker → main):

```clojure
{:seon.worker/task-id   "…"
 :seon.worker/result    <edn>          ; absent on error
 :seon.worker/error     <serialized>   ; absent on success
 :seon.worker/heap-used  <bytes>       ; process.memoryUsage().heapUsed → recycle bound
 :seon.worker/elapsed-ms <n>}
```

(Mirrors tinypool's `usedMemory`-in-every-response so recycle is a pure
function of the last result.)

### Terminate-on-deadline protocol (the CPU-proof kill)

1. Each task gets an `AbortController`; a watchdog `setTimeout(deadline-ms)`.
2. Watchdog fires → `abort()`.
3. The abort handler (pattern from `index.ts:536-551`): task is running →
   `_removeWorker` → `worker.terminate()` (0.8ms even on a sync infinite loop)
   → `_ensureMinimumWorkers` respawns.
4. The run closes with `:seon.run/stop-reason :deadline-exceeded`.
5. Belt-and-suspenders: `terminateTimeout` (tinypool `:517`) rejects if
   `terminate()` itself wedges.

This is the **only** mechanism that stops a runaway eval — SCI cannot interrupt
its own infinite loop; the OS thread kill can.

### Restart-from-DB recycle

- Report `heap-used` per response; recycle when `> maxMemoryLimitBeforeRecycle`
  (tinypool `:1058-1065`), evaluated **in the task done-callback after the
  result is captured** (`:948`) so no in-flight loss.
- Recycle = `_removeWorker` (terminate) → `_ensureMinimumWorkers` (respawn).
  The fresh worker boots its SCI cage and **re-reads state from datahike** —
  there is no in-memory state to preserve, so our recycle is strictly simpler
  than tinypool's (no `teardown`, no channel handoff).
- Optionally `isolateWorkers`-style recycle-every-N-turns as a flag if SCI eval
  proves to leak.

### SCI-cage-inside-worker layering (two layers, two jobs)

- **Process boundary (`worker_thread`) = the real isolation.** It is the only
  thing that contains a CPU hang or a native crash; `terminate()` is its kill.
- **SCI cage (inside the worker) = hallucination guard.** It limits the
  var/ns/API surface the LLM-authored code can touch. Per the repo's settled
  rule, the SCI sandbox is *not* a security boundary — it catches LLM mistakes;
  isolation comes from the process boundary + the wire capability surface.
- Net: a runaway turn is killed by `terminate()` (layer 1); a malformed/over-
  reaching turn is rejected by the cage (layer 2) and reported back as
  `:seon.worker/error`.

## What we DON'T need from these libs

- **transferList / `Piscina.move` / `Transferable` / SharedArrayBuffer-for-data**
  (`index.ts:144-164`, `:994-1006`). Our payload is small EDN/strings;
  structured-clone over `postMessage` is fine.
- **The Atomics wait-loop** (`worker.ts:126`). Optimization for sub-ms dispatch
  that *blocks async ops in the worker* — actively wrong for our `^:async`
  eval. Use the `message` event path.
- **Histogram / timing / `utilization` / `needsDrain` telemetry**
  (`histogram.ts`, `index.ts:900-968`). Derive pool state in the inspector by
  querying the DB (reactive-context principle) instead of storing counters.
- **Pluggable `loadBalancer`** (`worker_pool/balancer`). Single concurrency →
  "any idle worker" is the whole algorithm.
- **`runtime: child_process`** (tinypool). We want `worker_threads` only.
- **`concurrentTasksPerWorker > 1`.** One CPU-bound turn per worker.
- **`maxQueue: 'auto'` + `close({force})` timeout race** (`index.ts:655-726`).
  A plain bounded queue + a simple terminate-all `destroy()` is enough.
- **File-based handler loading / `getHandler` import machinery + handler cache**
  (`worker.ts:48-89`). Our handler is the fixed, bundled `eval-batch!`.
- **`niceIncrement` / `@napi-rs/nice`** (`worker.ts:98-100`), `trackUnmanagedFds`.
- **`teardown` hook** (tinypool `:498-515`). Nothing to tear down — state lives
  in the DB, not the worker.

## Citations index

- Warm/min-workers: `piscina/src/index.ts:217`, `:223-230`, `:266-281`;
  `tinypool/src/index.ts:706-710`, `:779-783`
- Dispatch/queue/balancer: `piscina/src/index.ts:397-474`, `:556-597`;
  `worker_pool/base.ts:53-125`
- AbortSignal cancel: `piscina/src/index.ts:528-554`; `tinypool/src/index.ts:963-985`
- terminate/destroy: `piscina/src/worker_pool/index.ts:102-117`;
  `tinypool/src/index.ts:489-540`, `:517-522` (terminateTimeout)
- Crash supervisor: `piscina/src/index.ts:326-366`; `tinypool/src/index.ts:815-833`
- Memory recycle: `tinypool/src/index.ts:1046-1068`, `:948-953`, `:561-562`;
  `tinypool/src/entry/worker.ts:118`, `:138`
- Atomics (skip): `piscina/src/worker.ts:126-175`
