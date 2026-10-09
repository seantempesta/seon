---
title: One execution system and one accounting point — lane one-system
date: 2026-10-10
type: research
status: recommendation; prototype measured, nothing landed
---

# One execution system and one accounting point

## Recommendation

**Keep Datahike's writer as it is: it is library execution, not a parallel Seon system. The real blind spot is Seon's own Flow procs, which run on virtual threads.** Move the procs to platform threads. Observe the writer through the seams it already has. Close the account at one point, `seon.flow/var-process`, and deliver the reply only after that.

1. **Run the procs on platform threads.** `seon.flow/graph-executor` builds threads with `Thread/ofPlatform` (daemon, named by graph and proc) instead of `Thread/ofVirtual`. It stays a thread-per-task executor, so it has no pool and cannot starve a graph. The cost is 3 parked threads per agent at 32–90 KB RSS each.
2. **Measure each transaction in the writer.** Writer configuration gains `:write-fn-map {'transact! … 'merge! …}`, wrapping `datahike.writing/transact!` and `datahike.writing/merge-writer!` with current-thread counters. The op runs synchronously, without parking, on a platform go-dispatch thread. The measured cost rides back to the caller on the tx-report. This uses an existing Datahike seam with no fork change; it is proven below on a disposable store.
3. **Measure each commit (one fork change, upstreamable).** The commit loop runs `datahike.writing/commit!` in sync mode on one `clojure.core.async/thread`, measures it, and attaches the same `:datahike/commit` cost map to every finalized report in the batch: ms, allocated bytes, CPU ns, tx count and datoms. That is about 30 lines. Its event shape follows upstream's `listen-commits`.
4. **One accounting point.** The 4-arity of `seon.flow/var-process` measures the step and folds in the step's world sink: test bodies, which are already counted; writer processing per transaction; commit share; futures. It then delivers replies. `seon.turn/step` stops calling `async/offer!` on the reply and returns it as data.
5. **Shared costs are labelled, not apportioned.** GC CPU (JDK 26 `MemoryMXBean.getTotalGcCpuTime`) and JIT time are reported as the JVM-wide shared residual of the window.

Rejected:
- **(b) Flow-hosted writer backend.** It would be a second writer, it cannot express group commit, and Flow is CLJ-only while the writer is cljc.
- **(c) A Flow proc in front of the writer.** It would be a second queue with no attribution gain.

Owner's two questions, answered with evidence:

- **"Why are we batching?"** Seon does not batch writes. The commit loop in `datahike.writer/create-thread` is Datahike's group commit. On an agent branch it commits one transaction at a time: 3 of 3 measured transactions were 3 separate commits. The name `seon.batch` belongs to the eval-request fold, not the writer, and "batch" is a retired word in AGENTS' vocabulary table, which is where the confusion comes from.
- **"Why go-loops?"** That is how Datahike's writer is built. Upstream (2026-09-30) still uses go-loops. They already run on **platform** threads, where counters are exact.

**The cost is not in the writer.** A warm redefinition spends 61–76% of its allocation inside the agent's own turn step. On a cold run, Datahike index-node restoration under `:test-step` accounts for about 2.1 GB.

## Measured inventory (default `ffe62e1c3`, pid 20363, JDK 26.0.1, 2026-10-10)

Sources:
- `jcmd 20363 Thread.dump_to_file -format=json`, which includes virtual threads (`Thread/getAllStackTraces` omits them).
- `ThreadMXBean` from JVM-mode `eval_clj`.

Counts are given as idle / after traffic.

| Surface | Owner (seam) | Thread kind | Count | Exact thread counters? | Per-request attribution today |
|---|---|---|---|---|---|
| Flow proc loops: per agent `:seon.agent/mailbox`, `:seon.agent/turn`, `:seon.agent/schedule`; cluster graph `:seon.agent/armer` + `:seon.render.web/render`; host and work-launcher graphs | `seon.flow/start-graph!` → `seon.flow/graph-executor` (`:io-exec`) | **virtual**, thread per task | 28 loops in 15 graphs → 39 | **no**: -1 on virtual | arms only (`:evaluation-arms-only`) |
| Datahike writer: processing + commit go-loops | `datahike.writer/create-thread`; `go` → `clojure.core.async.impl.dispatch/executor-for` `:core-async-dispatch` → `:mixed` | platform cached pool `async-mixed-N` (go blocks hold no thread while parked) | 2 loops × 10 connections (branches); pool 30–32 idle, **82–115 during one request**; name counter reached 7395 | yes, for spans that do not park | none |
| `transact!`/`merge-db!` completion go blocks and **all `d/listen` callbacks** (`seon.cluster.wake` route, `seon.schedule` listener, `seon.sci.eval` program identity, `seon.eval.drive`) | `datahike.writer/transact!` → `notify-listeners!` | `async-mixed` | one per transaction | yes | none |
| konserve file I/O: `update_blob`/`read_blob` go blocks, NIO completions | `konserve.filestore` `AsynchronousFileChannel` | `async-mixed` + JDK default async-channel group `InnocuousThread-N` | 27–28 | yes, but interleaved across many threads | none |
| Flow's own control mult/tap go-loops; joins | `clojure.core.async.flow.impl/create-flow` | `async-mixed` | parked | yes | none |
| Test bodies | `seon.test` (`Thread/ofPlatform` "seon-test-body"), plus a virtual watcher on timeout | platform | one per executed test | **yes** | merged into the parent sink (`seon.test/measured-body`) |
| Futures/pmap from agent code | `seon.sci.kernel/future-call` → `clojure.core/future-call` (Agent `soloExecutor`) | platform cached `clojure-agent-send-off-pool-N` | per future | yes | in-flight count only |
| Cache derivation on a deadline lookup | `seon.cache/lookup-within` → `Thread/startVirtualThread` settle | virtual | one per miss | no | none (≤ 2 MB per redefinition seen) |
| Web: http-kit `server-loop`; request workers; SSE | `seon.render.web` (`newVirtualThreadPerTaskExecutor`, `Thread/ofVirtual`) | 1 platform + virtual | per request | no | separate entrance |
| Schedule handlers | `seon.schedule/schedule-step` (Flow proc) + `d/listen` | virtual + `async-mixed` | per agent | mixed | none |
| Graph stop request | `seon.flow/stop-off-thread!` | virtual | transient | — | — |
| Root-binding capture | `seon.db` root-frame | virtual | once at load | — | — |
| SCI deadline timer | `seon.sci.kernel` `ScheduledThreadPoolExecutor` "seon-sci-time-limit" | platform | 1 | — | — |
| Virtual-thread carriers; common pool | JDK `ForkJoinPool-1`; `ForkJoinPool.commonPool` (CompletableFuture continuations) | platform | 9–16; 4–8 | — | — |
| JVM: GC, JIT, socket REPL, tap-loop, async timeout daemon, JFR | JDK / Clojure | platform or native | — | GC via `getTotalGcCpuTime` | shared |

Platform Java threads: 73 idle, 152 after traffic. On this pin, `go` never runs on a virtual thread: `executor-for :core-async-dispatch` resolves to `:mixed`, a cached platform pool. Only `io-thread` and Flow `:io` default to virtual. Seon additionally supplies its own virtual `:io-exec`.

**Finding: blocking work inside `go`.** The `async-mixed` pool grows from 32 to about 100 threads during one redefinition. Its name counter has reached 7395, which means thread churn after the 60 s keepalive. Two causes were sampled. Writer processing runs synchronous index-node restores (`datahike.index.persistent_set/CachedStorage.restore`). Konserve's commit writes are fanned out as go blocks. Seon's checked-writer functions (`seon.db/with-declarations`, `seon.db/write-owned-values-error`) also run inside Datahike's processing loop. The pool is unbounded, so nothing deadlocks, but each blocked dispatch costs a new platform thread.

## Q2: can the writer live inside Flow?

What the writer owns (`datahike.writer/create-thread`, fork `e65599de5`):
- FIFO ordering of invocations, threading the uncommitted db value.
- Group commit: the commit loop drains `commit-queue` with `poll!` and commits once, unioning merge parents.
- Backpressure: a 120000 buffer, warnings at 90% and 50%, a 50 ms park.
- Ordered `:applied` ports.
- Async ops: `gc-storage!` and secondary-index builds, with an in-flight set joined at shutdown.
- Fatal-error closure of both queues with every pending caller answered.
- Shutdown joins.
- `merge!` via `merge-writer!`.
- Listener notification after commit, in the caller's go block.

| Option | What it adds | What it deletes | Keeps all the writer owns? | Verdict |
|---|---|---|---|---|
| **(a) Keep the writer; observe it** | `:write-fn-map` wrappers (Seon config, no fork change); a fork commit-cost field (~30 lines); Seon folds both into the world sink | `:seon.cost/unmeasured` / `:unavailable` labels for writer work; the JFR-only diagnosis of writer cost | yes, unchanged | **recommended** |
| (b) Flow-hosted writer backend in the fork (`create-writer` is a multimethod on `:backend`) | Two procs (processing, commit) + a conn; reimplemented closure, shutdown, async ops, applied ports | Nothing Seon has | **No.** A Flow transform receives one message per call, so "drain the queue" needs channel access Flow does not expose: group commit is lost or hacked. Flow is CLJ-only while `datahike/writer.cljc` is cljc, so it cannot be upstream-quality. | refuse: a second writer |
| (c) A Seon Flow proc owning each connection's writer | One proc per branch + one hop + a second queue | Nothing | Duplicates ordering and backpressure; still runs Datahike's go-loops behind it | refuse: no attribution gain |
| (d) Upstream (`upstream/main` 23692d77, 2026-09-30) | `datahike.core/listen-commits!`: one event per durable batch with ordered finalized tx reports, `:tx-count` and commit id. `datahike.metrics/commit!`: commit seconds, tx count, datoms per batch. `:bindings` conveyed from caller into the op (`with-bindings*`). | Our need for a custom batch event | yes | **Adopt by rebase later.** The fork has diverged by 165/171 commits. Model the commit-cost field on upstream's event now. |

Upstream also keeps the go-loops. `thread-try` there only starts them and runs blocking cleanup. No upstream Flow integration exists.

## Q3: attributing a shared group commit

**Rule.**
- Each transaction's processing is charged exactly to its submitter: platform counters inside the op.
- The batch's commit cost is measured once, synchronously on one platform thread, and attached identically to every member report.
- A member's share is `commit × its datoms / batch datoms`, shown with `:seon.cost/basis :datoms` and `:seon.cost/batch-size`.
- When the batch size is 1, the share is the whole commit and exact.
- When it is greater than 1, the share is labelled `:shared` with the batch's commit id, so it can be requeried.

**Why datoms, not bytes.** Bytes written are only knowable per batch, because members share rewritten leaves. Datoms are known per member, deterministic and cheap. Splitting by bytes would be invented precision.

**Measured on an agent branch: batch size is 1.** One redefinition produced 3 transaction reports with 3 distinct commit ids. Their datom counts were 48, 19 and 24. A `d/listen` probe on that agent's connection observed nothing on the root connection. Agent writes are serial and await their commits. Batching appears with concurrent writers on one connection: 50 concurrent submissions on a disposable memory store produced 12 commits.

## Q4: executor policy

Flow runs steps on the proc's own loop thread: `clojure.core.async.flow.impl/proc` runs the transform inline for `:io`/`:mixed`. `:compute` hands each transform to a `FutureTask` and waits with `.get` and `compute-timeout-ms` (default 5000). That timeout breaks "timeout is not termination" for multi-second turns, so `:compute` is unsuitable for the turn.

**Policy: thread-per-task platform executor per graph** (`seon.flow/graph-executor`). It is the same shape as today with a different thread factory.
- Every step's counters are exact.
- Threads are visible by name in dumps.
- There is no fixed pool, so no starvation.
- The thread count is bounded by what is already admitted: (armed agents × 3) + cluster procs. Admission is agent arming, which writer-checked facts already govern.
- No new dial is needed. If one is wanted, it is a thread-kind dial, not a pool size.

Alternative: only the turn proc on platform, as `:mixed` with a per-graph platform `:mixed-exec`. That needs `seon.flow/var-process` to lift its `:mixed` refusal, whose recorded reason was the shared cached pool. It saves 2 threads per agent, which is not worth a second rule.

Measured overhead in default's JVM (JVM mode, 300 parked threads, two rounds):

| Kind | Start (300) | RSS per parked thread |
|---|---:|---:|
| platform | 7–13 ms | 32–90 KB |
| virtual | 2–3 ms | ~0 |

At 100 agents that is 300 threads and 10–27 MB. Counter reads cost 0.42 µs per pair (measurement lane).

Real agent traffic: a redefinition alternated between the platform-thread turn step (prototype) and the ordinary virtual path (`seon.mcp/eval-clj`).

| Path | Wall ms (3 runs) |
|---|---|
| virtual (includes MCP print) | 633, 572, 535 |
| platform | 603, 622, 515 |

No measurable difference.

## Q5: the one accounting point, prototyped

Prototype (appendix; JVM mode, disposable agent `one-system-proto` on its own branch, nothing installed):
- It submits a real redefinition through `seon.cluster.agent/submit-source!`, onto a port of our own, so the armed graph is not fed.
- It runs `seon.cluster.agent/turn-step` once on a named **platform** thread, in the proc's own world (`seon.flow/in-world`, projection-state bindings).
- It brackets the step with current-thread counters, per-platform-thread deltas and the JVM-wide window, plus GC CPU and JIT.

Writer per-transaction processing (memory store; `:write-fn-map {'transact! measured}`): one transaction cost 86 KB and 0.74 ms CPU, on `async-mixed-N`, with `virtual? false`. The cost was carried on the returned tx-report.

Real redefinitions (MB / CPU ms):

| Run | Step thread (exact) | `async-mixed` (writer, listeners, konserve go) | Other platform | GC CPU | JIT ms | JVM window | Closure | Reply today |
|---|---:|---:|---:|---:|---:|---:|---|---:|
| cold, with JFR | 2440 / 1127 | 516 / 527 | 1 / 46 | — | — | 2965 / 3567 | alloc 99.7% | 30 / 17.6 |
| warm 1 | 692 / 362 | 220 / 383 | — / 46 | 3 | 235 | 923 / 1042 | alloc 98.8%; CPU 98.8% | 28 |
| warm 2 | 610 / 337 | 228 / 390 | — / 46 | 66 | 558 | 848 / 1367 | alloc 98.8%; CPU 102%* | 28 |
| warm 3 | 614 / 333 | 217 / 360 | — / 39 | 2 | 257 | 842 / 1015 | alloc 98.7%; CPU 97.6% | 28 |

*JIT time is compiler wall time, an approximation of its CPU.

For comparison, the measurement lane's redefinition window was 2118 MB / 2387 CPU-ms.

**What the numbers show.**
- Platform-thread accounting closes 98.7–99.7% of the allocation window. Adding GC and JIT closes CPU to about 98%.
- The reply reports 28–30 MB today: 22–81× below the step thread alone and 30–99× below the JVM window.
- On the cold run, the residual CPU of about 1.9 s outside Java threads is GC and JIT driven by 3 GB of allocation.
- In the prototype, `async-mixed` stands in for writer processing plus commit, and it over-counts concurrent go work. On the idle JVM that over-count is small. The design replaces it with the per-transaction and per-commit fields.

JFR, with allocation sampling unthrottled and execution sampling at a 2 ms period (execution samples do appear when the period is set):
- **Pool work.** On `async-mixed`, the largest items were konserve `update_blob` (53 MB), `datahike.writing/commit!` (17 MB), writer processing (12 MB), index restores and Seon's checked-writer functions.
- **Step work (cold run).** The step's allocation was dominated by Datahike index-node restoration: about 2.1 GB of `persistent_set` reads and `CachedStorage.restore`. 1.8 GB of that had no Seon frame within JFR's default 64-frame depth, so the calling query is unidentified. It fell during `:test-step` (1253 ms of a 1493 ms step).
- **Step work (warm runs).** Warm redefinitions drop to 610–720 MB, with `:test-step` at 377–466 ms. `seon.cache` was at 510/512 MB.

**Design of the point.** All of it lives in `seon.flow/var-process`'s 4-arity, because every proc passes through there.
1. **Bracket the step.** The proc thread is now platform, so `getCurrentThreadAllocatedBytes` and `getCurrentThreadCpuTime` are exact for the whole step: evaluation, settle, the test-step orchestration and the waits on `seon.db/transact!`. Nested same-thread spans are inside this one bracket, so there is no double count.
2. **Fold the world sink** (`seon.batch/*world*` `:seon.batch/sink`). Contributions come from disjoint threads, so the sum is exact:
   - test bodies, already merged by `seon.test/measured-body`;
   - writer processing, summed by `seon.db/transact!` from each report's `:datahike/processing`;
   - commit share, from each report's `:datahike/commit`, under the Q3 rule;
   - futures: `seon.sci.kernel/future-call` adds its body thread's delta on exit through the in-flight world it already carries.
3. **Deliver replies only after accounting.** The step returns `::replies [[port value] …]` in its outputs. The wrapper removes that key before Flow sees the outputs, because Flow resolves every output key as a port. It then assoc's `:seon.cost/request` into each value and offers it.
   - This deletes the three `async/offer! reply` sites in `seon.turn/step`: the main reply, the refusal or park reply, and the idle refusal.
   - The catch path's error reply moves to the wrapper's catch, which already exists for faults.
4. **Report the shared residual.** The JVM window minus the attributed parts, plus GC CPU and JIT, goes in `:seon.cost/shared` with its scope. It is never apportioned.
5. **Cost of the point.** Two counter pairs per step, about 1 µs. One map merge per transaction.

What disappears:
- `:seon.cost/unmeasured` and `:unavailable` for step and writer work;
- the `:evaluation-arms-only` scope label;
- JFR as the only way to see writer cost;
- the early-offer race in `seon.turn/step`;
- the virtual-thread opacity of proc dumps.

## Cost estimate (implementation, for root to schedule)

| Slice | Owner / model | Lines | Proof |
|---|---|---|---|
| `graph-executor` platform factory, named threads | seon.flow, Sonnet | ~5 | existing flow and agent-graph tests; a test that a proc thread `(not (.isVirtual …))` |
| `var-process` counters + `::replies` delivery; `turn/step` returns replies | Opus (one-path semantics) | ~40 net | `seon.request-cost-test`, MCP entrance tests named in `measurement.md`; a new assertion that reply cost ≥ step thread allocation |
| `:write-fn-map` in `seon.cluster.store` config; `seon.db/transact!` folds `:datahike/processing` | Opus (boot/store, host-bound) | ~20 | publish/adopt on a branch of the live store; a test reading processing cost from one transaction |
| Fork: sync commit on `thread` + `:datahike/commit` on finalized reports | fork (Opus), upstream PR candidate | ~30 + fork test | Datahike `writer_error_test` suite + batch-of-N attribution test; measure commit wall before and after |
| `future-call` delta; shared residual (GC/JIT) | Sonnet | ~15 | `seon.eval-alloc-test` |

Total: roughly 1–1.5 days including proofs, with one batched restart. That is consistent with option B in `measurement.md`.

## Open questions for the owner

1. **Writer stays as Datahike's go-loops, observed through its seams.** Do you accept this, or do you still want it Flow-hosted at the cost of group commit and cljc?
2. **Thread scope.** All procs on platform threads (3 per agent, recommended), or only the turn proc (lift the `:mixed` refusal)?
3. **Fork commit change.** Exact commit cost needs `commit!` run synchronously on a platform thread. Accept that, or keep commit as wall time only, labelled shared? Separately: schedule a fork rebase onto upstream to gain `listen-commits`, metrics and binding conveyance?
4. **GC and JIT CPU.** Show them as a labelled JVM residual (recommended), or apportion them by allocation share?
5. **The cost.** The dominant cost is Datahike index-node restoration under `:test-step`. Should it get its own performance lane? A suggested task is `seon.turn/a-redefinition-test-step-restores-gigabytes-of-index-nodes`, with this doc as evidence.

## Findings outside this assignment (task files left to root; this lane commits only this doc)

- **`:test-step` allocates 0.4–2.1 GB per redefinition, mostly index-node restoration.** The callers are beyond JFR's 64-frame depth. Rerun with a JVM started with `-XX:FlightRecorderOptions:stackdepth=256` to name them.
- **`async-mixed` pool growth (32 → 115) and churn (thread name counter 7395).** Caused by blocking index reads in go blocks: writer processing plus Seon's checked-writer functions running inside it.
- **The `seon.batch` namespace and "batch" in cost prose collide with Datahike group commit.** The vocabulary table already retires "batch"; the rename belongs to the eval-request owner.
- **`measurement.md` says CPUTimeSample and ExecutionSample "did not appear".** `jdk.ExecutionSample` does appear on this macOS JDK 26 when given an explicit period (`.withPeriod`); CPUTimeSample remains Linux-only.
- **Default is `:faulted`, with 7 over-bound warnings and 7 open faults.** These predate this lane.

## Appendix: prototype (run through `eval_clj`, mode jvm, agent `one-system`)

The agent `one-system-proto` was created by one ordinary SCI request defining `tiny` and `tiny-test`. The comparison loop alternated this script (with `jfr?` false and a new body each time) with `#'seon.mcp/eval-clj` for the virtual path.

```clojure
(require 'seon.mcp 'seon.operator.runtime 'seon.env 'seon.cluster.agent 'seon.schema 'seon.flow 'clojure.core.async)
(let [agent-id "one-system-proto"
      code "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 51 -50))"
      mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      os ^com.sun.management.OperatingSystemMXBean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
      inst (#'seon.mcp/instance @seon.operator.runtime/running-instances "default")
      custody (#'seon.mcp/execution inst agent-id nil)
      entry (get-in @(:seon.agent/routing custody) [:seon.agent/armed agent-id])
      handle (:seon.turn.loop/cluster entry)
      mine (clojure.core.async/chan 1)
      ;; arm! answers the armed entry it finds, so the submission lands on OUR port
      routing (atom {:seon.agent/armed {agent-id (assoc entry :seon.agent/requests mine)}})
      _ (seon.cluster.agent/submit-source!
         {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id
          :seon.agent/segmenter 'seon.turn/code-sources :seon.cluster.reply/text code})
      submission (clojure.core.async/poll! mine)
      environment (seon.env/scope (seon.env/of handle) {:seon.agent/id agent-id})
      state (seon.cluster.agent/turn-step
             (seon.env/carry {:seon.turn.loop/cluster handle :seon.agent/id agent-id} environment))
      result (promise)
      a0 (.getTotalThreadAllocatedBytes mx) c0 (.getProcessCpuTime os)]
  (.start (-> (Thread/ofPlatform) (.daemon true) (.name "one-system-step")
              (.unstarted
               (fn []
                 (let [ta (.getCurrentThreadAllocatedBytes mx) tc (.getCurrentThreadCpuTime mx)]
                   (with-bindings (seon.schema/projection-state-bindings (:seon.sci.eval/projection-state handle))
                     (@#'seon.flow/in-world environment nil
                       #(seon.cluster.agent/turn-step state :seon.agent/request submission)))
                   (deliver result {:step-mb (/ (- (.getCurrentThreadAllocatedBytes mx) ta) 1e6)
                                    :step-cpu-ms (/ (- (.getCurrentThreadCpuTime mx) tc) 1e6)}))))))
  (assoc @result
         :jvm-mb (/ (- (.getTotalThreadAllocatedBytes mx) a0) 1e6)
         :jvm-cpu-ms (/ (- (.getProcessCpuTime os) c0) 1e6)
         :reply (select-keys (clojure.core.async/poll! (:seon.agent/reply submission))
                             [:seon.cost/counts :seon.turn.loop/stages :seon.turn.loop/outcome])))
```

The full version adds:
- per-platform-thread deltas from `getThreadAllocatedBytes`/`getThreadCpuTime` over `getAllThreadIds`, grouped by name: step, `async-mixed-`, `seon-test-body`, `clojure-agent`, other;
- a 1.5 s tail window;
- GC CPU and JIT deltas;
- optional JFR allocation and execution samples, grouped by thread kind and frame.

The writer per-transaction probe used a disposable memory store:

```clojure
(d/connect (assoc cfg :writer {:backend :self
                               :write-fn-map {'transact! (fn [db arg] ;; counters around datahike.writing/transact!
                                                           ...)}}))
```

Recordings went to `tmp/one-system/` and are disposable. The agent `one-system-proto` and its branch are disposable and left for root's sweep.
