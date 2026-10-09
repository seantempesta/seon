---
title: One execution system — independent Astra research
date: 2026-10-10
type: research
status: recommendation
---

# Recommendation

**Keep Datahike's writer; unify Seon's execution ownership under Flow, and extend the upstream durable-commit observation seam in our Datahike fork.** Use virtual threads for parked Flow proc loops and platform threads for admitted, measured active work. Carry request identity through submissions, measure at the work's owner, and publish replies after their required work and accounting complete. Do not add a Seon transaction queue or batching policy.

This is option **(a), improved by (d)**. “One system” should mean one Seon lifecycle, admission, completion and observation contract, with library resources attached to its existing roster. It does **not** mean that every dependency's internal loop becomes a Flow proc: this recommendation retains Datahike's go state machines, core.async's control machinery and transport/I/O threads. If the requirement literally means that Datahike's processing and commit loops must implement Flow's proc protocol, choose (b), with the larger cost below. Neither interpretation can remove JVM/transport threads.

The existing data flow is an agent Flow transform → Seon evaluation/recording/tests → Datahike transaction processing → Datahike group commit → reply. Compose `clojure.core.async.flow/futurize`, the existing `seon.sci.kernel/on-platform-worker` exit discipline, Datahike's writer protocol and upstream commit listeners. Move the measured active extent outward from each form to the request's work, reuse it for nested calls, and return completion data through Flow outputs. This deletes per-form handoffs when already inside that extent and the reply-before-exit shortcut; it adds no evaluator, scheduler, store or transaction batching layer. Counter snapshots are O(1) per synchronous extent, transaction observation O(1) per transaction, and commit membership/accounting O(m) for the m reports Datahike already groups. No whole-program scan belongs in this mechanism.

**Not implemented or proven here:** complete causal CPU/allocation accounting, an observation-hook patch, a Flow writer backend, and production executor admission. This is research plus finite live prototypes and one documentation commit. The prototypes reproduce the missing ~2.1 GB request cost; they do not claim to have recovered it.

## Evidence and installation

Measured 2026-10-09 UTC (the owner's 2026-10-10 assignment), default PID 20363, start instant 2026-10-09T01:41:06.636Z, OpenJDK 26.0.1. Checkout HEAD was `633e25beba261f6f77072a7449fabcc562f4f4f3`; default still loaded archive `ffe62e1c37f60566e68cb32943e870a1b4b47922`. No restart/reset/stop or provider call occurred.

Resolved live resources, checked at the JVM REPL:

| Owner | Installed source |
|---|---|
| Datahike | Archived pin `e65599de5eb393ca842304b6487453616214ea26` |
| core.async/Flow | Maven `1.10.874-alpha3`; reference checkout/gitlink `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| Konserve | gitlibs `e4334756413917305968333bf305e658c177765d` |
| Seon | The default archive above, not the working tree |

Default was already faulted: 7 open faults, 32 failed tests, 61 unknown tests and 7 over-bound warnings. Status named maintenance collection and filesystem work among the overruns; heap used 1.16 GB, last live heap 0.96 GB, heap cap 4.29 GB, cache weight 510.8 MB of 512 MB. A later process observation showed RSS 5,148,832 KiB; this is whole-process memory, not executor overhead. Counts fluctuate with other users of this JVM. The existing task `seon.batch/a-request-cost-counts-only-the-evaluation-arm` owns the reproduced attribution defect. No task or issue index was edited.

Read authorities: AGENTS.md; REPL, Flow, Datahike and data-oriented Clojure skills; docs/seon/specs/agent-loop.md; docs/seon/architecture/reference-code.md; the previous [measurement](measurement.md) and its probes; source and relevant Git history of the first-party/dependency owners below. I did not read the independent Opus output.

### A useful upstream difference

Fetched and pinned upstream Datahike main at `23692d7730f324d35fbebe687641ae40e431f9f5` (commit date 2026-09-30). Its [core](https://github.com/replikativ/datahike/blob/23692d7730f324d35fbebe687641ae40e431f9f5/src/datahike/core.cljc), [writer](https://github.com/replikativ/datahike/blob/23692d7730f324d35fbebe687641ae40e431f9f5/src/datahike/writer.cljc) and [metrics](https://github.com/replikativ/datahike/blob/23692d7730f324d35fbebe687641ae40e431f9f5/src/datahike/metrics.cljc) already expose a better starting point:

- `datahike.core/listen-commits!` observes successful local durable batches with ordered reports and commit identity, before callers receive reports.
- `datahike.metrics/commit!` records batch duration, transaction count and datom count.

The live fork resolves neither API. A namespace-safe REPL check of `datahike.core/listen-commits!` returned false; `datahike.metrics` was absent. These are upstream APIs to port/extend, **not installed capabilities**. Prefer a small compatible port over copying the much larger upstream writer, whose other semantics have diverged. Do not retain the event's databases/reports in long-lived cost history; extract identities and numbers.

## Options: additions, deletions and price

Engineering estimates below include targeted proofs, not a measured delivery schedule.

| Option | Guarantee and sacrifice | Deletes | Adds | Estimated scope |
|---|---|---|---|---|
| **(a)+(d), recommended** | Datahike ordering/durability/group commit unchanged; uniform ownership and observations at boundaries. Retains dependency-internal execution mechanisms. Exact available counters require the synchronous extents described below. | Per-form handoffs inside an already measured request; premature reply publication; duplicate observer/event design; unowned Seon detached work as each caller converts. | Compatible upstream commit-listener port; optional transaction/commit measurement input; explicit request correlation; measured mixed-work executor and Flow-owned resource/task lifecycle. | 2–4 engineer-days for the request/writer vertical slice; other surfaces each need bounded conversion/proof. |
| **(b), Flow-hosted Datahike writer backend** | Literal writer processing/commit procs share Flow controls. Durability/order still Datahike. Flow pause/stop semantics must be reconciled with accepted writes; does not solve nested storage threads by itself. | `datahike.writer/create-thread`'s two loop orchestration bodies in the selected backend; its bespoke supervision after native equivalence is proven. | Datahike `datahike.writer/create-writer` method implementing existing `datahike.writer/PWriter`; processing/commit steps; queue-drain membership logic extracted from current owner; shutdown barrier, async-op joins and observation. Keep the existing backend for other consumers/CLJS. | 5–10 engineer-days including failure and contention proofs; higher boot/storage risk. |
| **(c), Seon Flow submission proc** | Can own admission/connection lifetime. Measures enqueue/wait, not writer CPU. Serial waiting can reduce group size to one for Seon traffic. | At most scattered Seon submission plumbing. **Deletes none of Datahike's loops, queues or batching.** | Another queue, hop, pending-request ownership and error route in front of existing queues. | 1–2 days, but insufficient outcome. Reject as an accounting fix. |
| **(d), upstream mechanisms alone** | Commit membership/duration and Flow executor selection without Seon replacements. No transaction CPU/allocation, request identity propagation or final reply ordering. | Proposed home-grown batch-listener registry and event shape; unnecessary executor abstraction. | Compatible use/port of existing seams. | Less than a day for API integration research/port; not the complete fix. |

For (b), do not reimplement transaction transformation or commit persistence. Call `datahike.writing/transact!` and `datahike.writing/commit!`; preserve the commit loop's drain of already queued reports, uncommitted processing basis, merge-parent union and branch-head lineage. A generic Flow process reads one input at a time: group commit still needs Datahike's existing drain algorithm. Moving that algorithm is not deleting batching. An optional backend left beside `:self` increases library code; replacing `:self` outright expands compatibility risk. Arbitrary Flow pause must not strand accepted durable writes, and `clojure.core.async.flow/stop` alone is not a writer drain/join.

## Why batching exists, and who owns it

At the installed pin, `datahike.writer/create-thread` has two independent go loops. The processing loop invokes the operation against its held uncommitted database in order. The commit loop takes one report, drains currently available reports, commits the newest database once, advances the connection, and answers each caller with that committed database and its own transaction data. Default commit wait is zero; this is opportunistic group commit, not a Seon delay.

Live fork configuration showed transaction and commit queues each of size 120000. These are dependency defaults, not recommended Seon admission budgets. They do not bound active platform threads.

Prototype: on this lane's disposable branch, 16 concurrently released calls to `seon.db/transact!` with empty transaction vectors all returned committed reports in a 46 ms enclosing form. They shared **two** commit ids: one report alone and 15 reports together. Before-t values ran from 536871735 through 536871750. Commit ids were `6ac91422-73cb-5e14-ad0e-30d461aaa2a1` (one) and `6ac91422-c476-5a6e-b642-c2b3d5df15b1` (15). No batching was implemented by the probe. Returned reports establish group membership for these successful operations; they are not a complete observer for failed attempts.

Seon's one recording transaction per eval request is a different boundary: related facts are one logical transaction. Datahike may group that transaction with others for durability. Splitting recording facts into multiple transactions would change atomicity; removing Datahike group commit would discard a library optimization.

### Attribution rule

Keep three distinct observations:

1. **Direct work** belongs to its request: synchronous transformation, validation, indexing and owner-local overhead, including failed attempts. Use an invocation identity carried outside persisted transaction metadata; an unsuccessful transaction may have no tx id.
2. **Shared commit work** is recorded once, keyed by store + branch + commit (or attempted-batch identity on failure), with the ordered invocation/transaction members. Record measured worker CPU/allocation and elapsed commit time separately.
3. **Request charge**, when a scalar is wanted, is direct cost plus an explicitly labeled equal share of each batch among its m transaction members. Sum per member first, then per request: a request contributing k members receives k/m. Equal share is an accounting convention, **not measured marginal cost**. Keep exact rational shares or assign the integer remainder deterministically so the sum equals the batch. Do not duplicate full batch CPU/allocation on every request.

Queue wait and durability wait are latency experienced by each caller, not additive CPU. Report request elapsed time and worker CPU separately; overlapping spans cannot be summed into elapsed time. A failed processing invocation receives its direct cost and no unrelated successful batch share. An attempted shared commit that fails retains its members and cost as failed work. Duplicate delivery is deduplicated by observation identity. Open/detached work stays pending, never zero.

Listeners/wakes and reused cache work require the same rule: charge initiating work once, share where appropriate, and keep background maintenance/GC/JIT separate. A JVM-wide window includes unrelated requests, GC and compilation; it is a reconciliation aid, never causal truth. The top-level identity must cross Flow messages and Datahike invocation data explicitly; copying every caller binding into the writer would reintroduce custody leakage.

## Observation hook: the actual gap

Upstream `datahike.core/listen-commits!` solves membership and the durable boundary. Its metrics solve elapsed duration. Extend those mechanisms rather than add a second observer roster:

- Optional process-local invocation context and measurement callbacks; absent configuration should take the existing path with negligible overhead. No Seon keywords or MXBean requirement in Datahike's generic API.
- Per-invocation processing observation around synchronous `apply op-fn old args`, including refusal/throw. Include invocation id, operation, begin/end and available counters; do not copy a database or transaction payload.
- Per-commit observation includes all member ids and the one committed/failed batch result. Observation failure must not turn an already committed transaction into a failure. Report an observer error independently; do not perform synchronous transactions from the writer callback.
- Complete measurements before caller delivery when final cost is promised. The upstream commit callback is before delivery; installed ordinary transaction listeners are after promise delivery and cannot implement that guarantee.

**Two snapshots surrounding an asynchronous wait are wrong.** Installed `datahike.writing/commit!` with `sync? false` starts another go computation; continuations may resume on another thread. The waiter does not own its CPU/allocation, even if it is a Flow proc. Konserve also uses asynchronous file I/O. A hook at the outer wait gets latency only.

A smaller library seam exists: `datahike.writing/commit!` accepts `sync? true`, and `konserve.utils/async+sync` selects synchronous implementations. `konserve.filestore/BackingFilestore` opens a FileChannel in that mode rather than AsynchronousFileChannel. A measured platform task executing that existing synchronous path is a candidate for contiguous commit accounting. Keep it inside Datahike's commit owner and preserve last-cid, merge parents, GC permits and callback order. Do not call commit directly from Seon.

This synchronous execution choice has **source evidence, not live fork-change proof**. Verify the complete chosen storage backend remains synchronous and count any child work separately. Compare serialization/flush/fsync behavior and latency against the asynchronous path before choosing it. If async storage must stay, extend its own task/continuation observation instead; accounting must follow those continuations. Replacing the writer with Flow does not bypass this requirement.

## Executor policy and measured overhead

[ThreadMXBean](https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/ThreadMXBean.html) exposes approximate allocated-byte counters; CPU counters have finite accuracy. “Exact counters” here means unsampled, correctly scoped platform-thread deltas, not perfect hardware truth. The live virtual-thread probe returned **[-1 -1]** for allocation and CPU. Never clamp that to measured zero.

Use virtual threads for long-lived control/input loops. Default's six armed agents alone accounted for 18 such loops. Putting idle proc loops on a small fixed platform pool can exhaust every worker before another proc starts. Switching `seon.flow/graph-executor` globally to platform threads spends three resident platform threads per armed agent plus host/cluster/work graphs.

Measure active mixed work on admitted platform tasks, entered through the existing Flow owner and `clojure.core.async.flow/futurize`. Start with a platform thread per admitted blocking request/task under a configured active-work bound; reuse the current thread for nested work. Pure nonblocking work can use a bounded platform pool. Keep control/timer delivery and writer progress independent of callers blocked waiting for them. In particular, never fill one fixed pool with requests waiting for writers/tests queued on the same pool. This policy requires admission sized for a 16 GB machine and joins on actual exit; a timeout must not release a slot while the task continues.

The installed `seon.sci.kernel/on-platform-worker` already uses `clojure.core.async.flow/futurize`, conveys bindings and awaits actual exit. Expand/consolidate that owner rather than copy its interruption loop into another module. Request-level execution must also carry the projection and sink. Count nested same-thread spans once; sum disjoint child-thread extents once. Test-body counters remain useful stage evidence without adding them twice to a parent inclusive total.

Flow's built-in `:compute` workload offloads transforms while its input loop stays on IO. It is correct for nonblocking transforms. It is **not** a drop-in tag for the whole request, which waits for I/O and tests. Its timed Future.get throws without proving task exit; the proc catches and can handle more input. Retain Seon's actual-exit rule instead of treating this timeout as cancellation. The public executor factory can choose pools at boot, but a pool-wide wrapper lacks request/member identity and cannot attribute interleaved go continuations.

Finite live Flow prototype: eight real library procs, 500 serial round-robin requests per graph, each allocating 100 arrays (104950 payload bytes). A reply was emitted as Flow output after the transform returned. Each graph was stopped and its owned executors awaited before the next.

| Execution | First 500 steps, ms | Second 500, ms | Last step allocation | Last step CPU |
|---|---:|---:|---:|---:|
| Virtual proc loops, body inline | 21.458 | 15.058 | unavailable | unavailable |
| Platform proc loops, body inline | 17.923 | 17.587 | 114952 B | 11 µs |
| Virtual loops, Flow futurize handoff to two platform workers | 28.937 | 23.161 | 114952 B | 11 µs |

All six runs completed 500 replies. Warm handoff versus virtual inline: **8.103 ms/500 = 16.21 µs per step**; versus platform inline: 11.15 µs. These small noisy runs establish availability, completion and approximate overhead, not throughput under contention or a universal performance win. The fixed two-worker prototype used finite nonblocking bodies; it is not the proposed pool for blocking whole requests. Native stack memory was not isolated.

Warm reads over 100000 iterations after 1000 warmups: one allocation+CPU pair **0.443 µs**; one JVM allocation+CPU pair **3.464 µs**. Two snapshots cost approximately 0.886 µs per synchronous measured extent, excluding map construction. Per-call CPU reads at 78817 calls would cost roughly 70 ms for entry/exit pairs; use stage/worker counters rather than automatically instrument every tiny call.

## Every execution surface outside Flow

Source inventory covers src/ and script/; third-party internals are grouped by their owning execution mechanism. A go loop parked in a channel is **not a resident thread**. Do not infer loop count from ThreadMXBean or assign a pooled thread permanently to a writer.

Snapshot at **2026-10-09T16:17:08.496352Z**: JDK JSON dump contained **105 threads: 75 platform, 30 virtual**. Of the virtual threads, **28 had Flow proc stacks**, 27 inside Seon's graph executor and one work-launcher proc. Two were JDK HTTP client selector managers. A subsequent roster probe had eight execution rosters, six armed agents and ten live LocalWriters (20 writer logical loops); all ten async-writer in-flight sets were empty. These closely spaced observations are not an atomic system census. The research probes themselves and other lanes can change counts.

| Surface outside a Flow transform | Owner/thread policy now | Observed count and unification action |
|---|---|---|
| Datahike processing + commit | `datahike.writer/create-thread`; core.async dispatch continuations on shared platform mixed pool | 10 writers / 20 logical loops. Retain native shutdown/drain, attach connection lifetime to Flow-owned roster; add observations at source. |
| Writer transaction completion/listeners, async GC/backfill; Flow mult/tap/inject machinery | Library go tasks, same dispatch pool | 32 async-mixed threads total, shared with other work; async writer ops 0. Parked one-shot/control go count not exposed. Do not invent an exact per-library thread split. |
| Konserve async file operations | AsynchronousFileChannel callbacks and shared JDK pool | 11 unnamed “InnocuousThread” workers in one pool plus one separate innocuous thread; stack alone cannot uniquely attribute all 12. Measure at storage owner or use verified sync seam. |
| Request/form execution offload | `seon.sci.kernel/on-platform-worker`, Flow futurize on mixed platform pool | No active body in dump; pool shared above. Move measured scope outward, reuse nested thread. |
| SCI future/pmap | `seon.sci.kernel/future-call`, Clojure future executor; existing owned-work counter | No named Clojure agent-pool worker observed. Preserve ownership through actual exit and add child measurements. |
| Test body and timeout watcher | `seon.test/bounded-result`: named platform body; virtual exit watcher | 0 observed bodies/watchers. Flow-owned admitted tasks should reuse this completion guarantee. |
| Detached Seon work launcher tasks | `seon.flow/execute-io-work!`, shared virtual IO executor | No active detached task in dump; launcher itself is the extra Flow proc. Same measured-work boundary and completion callback, no second runner. |
| Turn completion backstop | `seon.turn/arm-turn-completion-backstop!`, separate task on graph executor | 0 active in idle dump. Fold control/lifetime into owning graph; never count parked wait as CPU. |
| SCI deadlines | `seon.sci.kernel/deadline-timer`, ScheduledThreadPoolExecutor | 1 platform “seon-sci-time-limit”. Keep a library timer service owned by process/graph lifecycle; do not occupy active-work slots waiting. |
| Cache derivation | `seon.cache/lookup-within`, detached virtual thread | 0 active observed. Attach work identity, completion and counters; multiple waiters share one computation. Pending is not completed cost. |
| HTTP handlers and SSE feed | `seon.render.web/start!` virtual worker executor; feed starts a virtual loop | 1 http-kit server-loop platform thread; 0 handler/feed virtual stacks observed. Make Seon feed/work lifetime a Flow responsibility; retain http-kit transport. |
| Outbound HTTP/provider transport | JDK HttpClient selectors/callbacks used by `seon.web.jvm` and `seon.ai` | 2 virtual selector managers present; no provider enabled by this lane. Network callbacks are dependency resources; measured payload processing belongs to its request. |
| Shell stdin/stdout/stderr and process supervision | `seon.shell.jvm/virtual-task`; `seon.cluster.process/run-process!` futures plus babashka.process/ProcessHandle | 0 active shell/pump/reaper stacks observed. Make admission/join/cost one owned task; keep subprocess CPU separate from JVM CPU. |
| Bootstrap and external JVM entrance | Clojure server/prepl and operator runtime; request evaluation begins on connection thread | 1 prepl acceptor, 0 connection threads at dump; each MCP JVM probe adds one. Wrap/route active work through same entrance ownership, retain socket transport and boot-before-store semantics. |
| Graph stop off-thread; binding-frame initialization | `seon.flow/stop-off-thread!`, `seon.db/root-frame` initialization | 0 observed live. Keep stop independent of the graph being stopped; own/join it through parent lifecycle. Root-frame init is a one-time joined task. |
| Operator checks/builds | script/seon/operator.clj candidate-check future; dependency digest subprocess reader | External operator process, **not default JVM**; default count 0 by placement. Use same observation format, without pretending these are agent graph threads. |

The remaining platform census was: 9 virtual-thread carrier workers, 4 common-pool workers, 1 each of main, Reference Handler, Finalizer, Signal Dispatcher, Notification Thread, Common-Cleaner, core.async timeout daemon, virtual unblocker, read poller, write poller, tap loop, JFR recorder, JFR periodic tasks, attach listener, virtual scheduler delay thread, plus the prepl/http-kit/SCI timer already listed. These total 75 with the 32 mixed and 12 innocuous threads. JVM GC/compiler threads are not all represented by this Java-thread dump; process CPU includes their work. A later probe counted 99 mixed workers, 41 innocuous threads and 16 carriers: counts are snapshots, not configured bounds.

## Real defn request, end to end

The prototype called the **real MCP entrance** from JVM mode, targeting a separate SCI branch, with JVM allocation and process CPU snapshots immediately around each call. It did not invoke a provider or reload uncommitted source. Setup defined a contracted tiny function and a reaching deftest. The measured request redefined that function. Tests answered green.

| Case | Entrance window ms | JVM allocation MB | JVM CPU ms | Reply allocation MB / CPU ms |
|---|---:|---:|---:|---:|
| Define function + test, including acquisition | 1836.959 | 2913.557 | 6799.265 | 33.6 / 18.5 |
| One real defn redefinition | **1275.651** | **2158.773** | **3234.320** | **27.9 / 17.1** |
| Following trivial expression | 74.539 | 113.163 | 170.645 | 0.0 / 0.1 |

The redefinition reply reported 1274.4 ms, 78817 calls, 326 reads and 4 transactions. Stages: fork 13.1 ms, evaluate 30.6, derive 23.2, settle 224.0, test step **956.9**, read 0.8, facts 0.4, print 1.7, custody 0.2. The reply misses approximately 98.7% of the window allocation. The previous independent measurement of the same shape was 2118.305 MB / 2386.601 CPU-ms. Allocation scale reproduced; CPU varied and cannot be interpreted as a causal regression in this shared JVM.

The combined probe's form took 3189 ms and returned a slow finding; all individual cases stayed below ten seconds. The over-second request remains a defect, not excused by a “cold” label. The test step dominates elapsed time here; this does not identify the functions responsible for all allocation. Earlier [JFR evidence](measurement.md) placed most sampled allocation on virtual threads and some on async-mixed workers. No all-thread exact causal total was available in this run.

`seon.turn/step` currently offers the reply before its finally block publishes readiness/cancels its backstop and before the outer transform returns. A wrapper around the transform therefore cannot add final counters to that already delivered reply. Return the response as data, finish required work and accounting, then publish it through Flow's output path. Delivery/serialization itself may be measured at the MCP edge; no message can include the cost of transmitting its own final byte. Declare that endpoint instead of manufacturing a zero residual.

For acceptance, compare one owned request's direct extents plus each unique shared-batch charge against a quiet-JVM window, retaining explicit unknown/background residuals. Require batch conservation, no same-thread double counting, no early success on pending children, unchanged transaction order/durability/failure behavior, and no overlap after timeouts. Include multirequest group commits and refused transactions; a single idle request cannot prove the shared rule.

## Replay forms

Use MCP eval_clj with agent astra-one-system, cluster default, mode jvm. These are disposable expressions, not production definitions. Use a fresh case agent name and retire its branch afterward. The following records the actual successful forms; read returned errors and timings before interpreting a value.

### End-to-end request

```clojure
(let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      os ^com.sun.management.OperatingSystemMXBean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
      call (fn [source] (#'seon.mcp/eval-clj {:seon.mcp/cluster "default" :seon.mcp/agent "astra-one-system-case" :seon.mcp/code source}))
      measure (fn [[label source]]
                (let [a (.getTotalThreadAllocatedBytes mx) c (.getProcessCpuTime os) t (System/nanoTime)
                      [texts failed?] (call source)]
                  {:case label :window {:ms (/ (- (System/nanoTime) t) 1e6)
                                       :alloc-mb (/ (- (.getTotalThreadAllocatedBytes mx) a) 1e6)
                                       :cpu-ms (/ (- (.getProcessCpuTime os) c) 1e6)}
                   :failed? failed? :reply texts}))]
  (mapv measure [[:setup "(in-ns 'my.agents.astra-one-system-case) (defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (inc x)) (clojure.test/deftest tiny-test (clojure.test/is (= 2 (tiny 1))))"]
                 [:defn "(defn tiny {:malli/schema [:=> [:cat :int] :int]} [x] (+ 1 x))"]
                 [:trivial "(+ 1 1)"]]))
```

### Flow executor/counter comparison

```clojure
(let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      run (fn [mode]
            (let [platform? (= mode :platform-loops)
                  io (java.util.concurrent.Executors/newThreadPerTaskExecutor
                      (if platform? (.factory (.daemon (Thread/ofPlatform) true)) (.factory (Thread/ofVirtual))))
                  work (java.util.concurrent.Executors/newFixedThreadPool 2 (.factory (.daemon (Thread/ofPlatform) true)))
                  reply (clojure.core.async/chan 16)
                  body (fn [n]
                         (let [a (.getCurrentThreadAllocatedBytes mx) c (.getCurrentThreadCpuTime mx)
                               v (reduce + (map #(alength (byte-array (+ 1000 %))) (range n)))]
                           {:value v :alloc (if (neg? a) :unknown (- (.getCurrentThreadAllocatedBytes mx) a))
                            :cpu (if (neg? c) :unknown (- (.getCurrentThreadCpuTime mx) c))}))
                  step (fn
                         ([] {:ins {:in ""} :outs {:out ""}})
                         ([args] {:clojure.core.async.flow/out-ports {:out reply}})
                         ([s _] s)
                         ([s _ n]
                          [s {:out [(if (= mode :handoff)
                                      (.get ^java.util.concurrent.Future ((clojure.core.async.flow/futurize body :exec work) n))
                                      (body n))]}]))
                  graph (clojure.core.async.flow/create-flow
                         {:procs (into {} (for [p (range 8)] [p {:proc (clojure.core.async.flow/process step {:workload :io})}]))
                          :conns [] :io-exec io})]
              (try
                (clojure.core.async.flow/start graph)
                (clojure.core.async.flow/resume graph)
                (let [t (System/nanoTime)
                      rows (mapv (fn [x]
                                   (clojure.core.async.flow/inject graph [(mod x 8) :in] [100])
                                   (first (clojure.core.async/alts!! [reply (clojure.core.async/timeout 5000)] :priority true)))
                                 (range 500))]
                  {:mode mode :steps (count (filter some? rows)) :ms (/ (- (System/nanoTime) t) 1e6)
                   :first (first rows) :last (last rows)})
                (finally (clojure.core.async.flow/stop graph)
                         (.shutdown io) (.shutdown work)
                         (.awaitTermination io 5 java.util.concurrent.TimeUnit/SECONDS)
                         (.awaitTermination work 5 java.util.concurrent.TimeUnit/SECONDS)
                         (clojure.core.async/close! reply)))))]
  (mapv run [:virtual-loops :platform-loops :handoff :virtual-loops :platform-loops :handoff]))
```

### Datahike grouping

```clojure
(let [execution (#'seon.mcp/execution (#'seon.mcp/instance @seon.operator.runtime/running-instances "default") "astra-one-system-case" nil)
      conn (get-in execution [:seon.turn.loop/cluster :seon.db/connection])
      gate (java.util.concurrent.CountDownLatch. 1)
      executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
      submitted (mapv (fn [id] (.submit executor ^java.util.concurrent.Callable
                                (fn [] (.await gate)
                                  (let [report (seon.db/call-with-root-bindings #(seon.db/transact! conn []))]
                                    {:id id :committed? (boolean (:db-after report))
                                     :commit (get-in report [:db-after :meta :datahike/commit-id])
                                     :before-t (get-in report [:db-before :max-tx])
                                     :error (select-keys report [:seon.error/message])}))))
                      (range 16))]
  (try (.countDown gate)
       (let [rows (mapv #(.get ^java.util.concurrent.Future % 5 java.util.concurrent.TimeUnit/SECONDS) submitted)]
         {:branch (get-in @conn [:config :branch]) :rows rows :batches (mapv #(mapv :id %) (vals (group-by :commit rows)))})
       (finally (.shutdown executor) (.awaitTermination executor 5 java.util.concurrent.TimeUnit/SECONDS))))
```

Thread census was obtained with `jcmd 20363 Thread.dump_to_file -format=json <owned-path>`, then counted by the dump's virtual flag and Flow stack frames. Roster/connection counts came from the existing routing tree and `datahike.connections/*connections*`, without opening more connections for inventory. The successful observation handles were `result/eb0289e2f15ca` (rosters/writers), `result/e833f95584d8c` (real requests), `result/edda3e051cbbe` (Flow comparison), `result/e8d9b33d15ccd` (grouping), `result/e7cdcc70be46f` (counter overhead), and `result/e6bf63329bd61` (installed resources). This document preserves their relevant values because live handles are disposable.

## Landing and limits

Only this document changes. Net production/schema/test lines: **0/0/0**. Full required lint: `clj-kondo --lint src script test --cache false --parallel`, **4830 ms, 0 errors, 1462 warnings**. Documentation has no reaching test; installed `bin/lane` explicitly excludes documentation-only commits from program publication and the throwaway JVM gate. No source/test suite pass or installed implementation is claimed.

Foreign boundary: default is the older committed archive and remains faulted. Unrelated dirty paths observed included reference-code/http-kit, tmp/test-data-assertions/proofs.edn, docs/prds/next-turn/, a web handover note, a pre-existing malformed path under docs/research/agent-platform/landing/, and src-clm/. None was changed or staged by this lane. The independent research output was not consulted.

Three exploratory calls failed locally (a namespace lookup before checking namespace existence, a delimiter error, and an invalid auto-resolved keyword); corrected forms returned the values above. A zero-argument retirement call correctly refused its arity. Explicit own-branch retirement of `:agent-45ed31392a15` returned retiring true but left its connection held. Retiring from the research agent then refused cluster-connected: the existing tasks `my.agent/a-refused-retire-leaves-the-agent-admission-closed` and `seon.cluster.agent/finished-agents-and-their-branches-accumulate` cover this lifecycle gap. I read the actual retained handles, released only this case's two contexts through `seon.cluster.agent/release-context!` (12 ms), then retired its branch through `seon.cluster.registry/retire-branch!` (14 ms); the final check showed no case connection. All six prototype graphs/executors were stopped/joined. The research agent's branch `:agent-392a2460d509` remains as evidence for this unfinished lifecycle diagnosis; do not mistake it for a running prototype. No source was loaded into default.
