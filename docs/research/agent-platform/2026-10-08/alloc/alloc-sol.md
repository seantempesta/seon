---
type: research
status: findings
created: 2026-10-08
tags: [agent-platform, allocation, flow]
---

# Allocation attribution inside Flow procs

Research lane `alloc-research-sol`, 2026-10-08. Documentation only; no source reload,
default write, restart, provider call, or implementation. Seon already evaluates each
SCI form inside `seon.sci.kernel/with-arm`, on the agent turn proc's virtual thread.
Use the existing Flow handoff to run the complete evaluation extent on a platform
thread if its own heap allocation must be measured; keep orchestration on virtual
threads. Delete the JVM-wide fallback before trusting current allocation judgments.

## Answer and scope

Root's hypothesis is **mechanically true, semantically unsuitable for the current
turn proc**. `:compute` moves the transform to a platform thread with the current
default executor. It neither discovers blocking work nor splits SCI from the rest
of the transform. The turn calls the provider, waits for effects and transactions,
and runs tests. A form itself can sleep, dereference a future, call host I/O, or
transact. Labeling that entire transform compute violates Flow's documented
contract and introduces a timeout/exit defect.

There is **no supported JDK 26 API providing an exact virtual-thread heap allocation
counter**. The counter available for platform threads measures allocation on that
thread, independently of concurrent threads. Even this API formally promises an
approximation, not byte-perfect accounting on every JVM. “Exact” below means exact
thread attribution at HotSpot's counter granularity, not retained memory, native
memory, or all allocations causally triggered on other executors. See the
[JDK 26 allocation API](https://docs.oracle.com/en/java/javase/26/docs/api/jdk.management/com/sun/management/ThreadMXBean.html#getThreadAllocatedBytes(long))
and [platform-only management contract](https://docs.oracle.com/en/java/javase/26/docs/api/java.management/java/lang/management/ThreadMXBean.html).

Moving the arm to a platform thread restores its own-thread attribution. It does
**not** make Datahike writer allocations, virtual-thread capability handlers,
unadopted futures, or work still running after the record magically measurable.
If “one evaluation” means every transitive effect's allocation, none of the cheap
options supplies that guarantee. Declare that gap instead of attaching a JVM total
to the evaluation.

## Revisions and dependency seams

Initial checkout HEAD: `984b3445ce3a1537cff1e842a762e996288eeb2d`.
Default was running committed archive `a5fc4cb493ac5f5f2ec11ed4175497cdc9fdd651`,
PID 40766, start instant 2026-10-08T10:30:32.694Z, JDK `26.0.1`.
Source observations below describe the checkout; the bounded live probes establish
the runtime claims separately.

| Dependency | Pinned revision / installed artifact | Seam and guarantee | Inputs, event, cost |
|---|---|---|---|
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051`, `1.10.874-alpha3` | `clojure.core.async.flow/process`, `clojure.core.async.flow.impl/proc`: proc workload determines transform executor | Description plus options at proc construction; one submission/Future wait per compute transform |
| core.async | same | `clojure.core.async.impl.dispatch/executor-for`: defaults are cached platform pools for compute/mixed, virtual-thread-per-task for I/O when supported | Workload plus optional executor-factory system property; executor resolved once per workload by the dependency |
| core.async | same | `clojure.core.async.flow/futurize`, `clojure.core.async.flow.impl/futurize`: submit one invocation and return its Future | Function, arguments, workload or explicit Executor; one FutureTask and dispatch per invocation; no binding conveyance, admission, or exit-after-timeout policy |
| Datahike | `86c3301a00de8c49b2690083b2b1e69677bf0c8d` | `datahike.api.impl/transact!` → `datahike.writer/transact!`: queue transaction, deliver report through promise after commit | Connection/request per write; caller waits, writer runs separately; cost follows validation, datoms and storage |
| konserve | `e4334756413917305968333bf305e658c177765d` | `konserve.core/bassoc`, `konserve.core/exists?`: sync option serves synchronous callers | Store/key/content/options per publication; work follows content and filesystem I/O |

The live core.async resource URLs resolve to the `1.10.874-alpha3` Maven jar,
not the reference checkout. Comparing its `flow.clj`, `flow/impl.clj`, and
`impl/dispatch.clj` bytes against the pin found all three identical. Thus these
particular library seams are verified both at the pin and in the installed artifact.
The public [Flow API at the pin](https://github.com/clojure/core.async/blob/dc35f3e0d7bc2eef502e77982f48641f025c8051/src/main/clojure/clojure/core/async/flow.clj)
contains `process` and `futurize`; a new scheduler is unnecessary.

## What Flow actually does

`clojure.core.async.flow.impl/proc` chooses options' workload before description's
workload, then defaults to mixed. The workload applies to the proc, not a changing
annotation on one message or arity.

| Workload | Proc loop and transition | Transform | Blocking consequence |
|---|---|---|---|
| I/O | graph's I/O executor | inline on that loop thread | Current Seon graph executor supplies virtual threads; supported blocking can unmount |
| Compute | graph's I/O executor | submitted to resolved compute executor; loop calls Future.get | Caller loop parks; worker remains occupied by every blocking call in the transform |
| Mixed | resolved mixed executor | inline on that loop thread | Default cached platform worker remains occupied for the long-lived proc, including idle channel waits |

Dated source evidence: reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243–323;
dispatch.clj:71–111; flow.clj:194–202,264–275.
The default I/O constructor detects `Thread$Builder$OfVirtual` and uses
`Thread.startVirtualThread`; compute and mixed use `Executors.newCachedThreadPool`.
An explicitly supplied executor or the system property can change those choices.
A workload keyword alone is therefore never a universal platform-thread guarantee.

Compute transforms default to a **5,000 ms Future.get timeout**. The Future is
created inside the transform expression and is not canceled or retained by that
timeout path. Flow catches the exception, keeps the previous state, and resumes
its loop. The old transform can still be running when another transform starts.
This conflicts with Seon's actual-exit rule; increasing the timeout is not an exit
proof. It can also abandon the thread reported by `seon.cluster.agent/turn-step`
while the proc loop continues. Any design using this mode must address lifecycle,
not merely allocation.

`seon.flow/var-process` accepts only I/O or compute; it deliberately refuses mixed
for proc loops. `seon.flow/start-graph!` overrides only `:io-exec` with
`seon.flow/graph-executor`, a graph-owned virtual-thread executor. The current
`seon.cluster.agent/graph-definition` constructs `:seon.agent/turn` from
`seon.cluster.agent/turn-step` with I/O workload. That calls `seon.turn/step`, and
the one evaluation path reaches `seon.sci.eval/evaluate` → `seon.sci.kernel/with-arm`.

**Stale authority found:** .agents/skills/seon-flow-architecture/references/workloads-and-scheduling.md
claims a root fixed compute pool built by `seon.flow/bounded-platform-executor`
and `seon.operator.runtime/root-executor-pair`. Neither symbol exists in current
source; resources/seon/operator/runtime.clj contains no executor pair. No
compute-exec override occurs in current production graph definitions. The live
default compute pool has core size 0 and maximum 2,147,483,647. Do not base a
starvation or capacity claim on the stale fixed-pool description. Correcting the
skill belongs to its owner; this lane edits only this report.

With today's cached compute pool, blocked work grows platform threads rather than
exhausting a fixed set of workers. With a future bounded compute override, enough
blocked transforms exhaust its workers and queue unrelated computation; nested work
awaiting the same exhausted pool can deadlock. Both cases are reasons to classify
blocking honestly.

## Blocking and allocation ownership

| Operation | Current execution and wait | Under a whole-turn compute declaration | Allocation scope |
|---|---|---|---|
| SCI interpretation and compiled calls | virtual turn thread; arbitrary host calls allowed | compute platform worker, including sleeps and user derefs | own thread counter becomes available; other threads excluded |
| `seon.db/transact!`, `seon.db/transact-call` | calling thread waits with bounded deref on Datahike promise | compute worker held until commit/refusal | writer allocations remain outside the caller's counter |
| `seon.blob/with-publication!`, `seon.blob/publish-staged!` | caller acquires reachability permit and uses synchronous konserve reads/writes/verifications before commit | compute worker held across permit and storage calls | synchronous allocations count on caller; delegated I/O allocations do not |
| provider and effect waits inside `seon.turn/step` | virtual turn thread waits; effect handler uses launcher I/O tasks | compute worker held across provider/effect completion | handler's virtual-thread allocation remains unmeasurable by MXBean |
| tests after evaluated forms | turn performs `seon.turn/test-step`; test execution can wait on other work | compute worker held across test execution | child worker allocations require their own attribution |
| idle mailbox/schedule/channel waits | graph's virtual proc loops | compute keeps loop virtual; mixed moves loop to platform worker | moving every loop wastes platform workers without improving the arm's scope |

`seon.sci.kernel/record` uses owner-thread delta plus completed platform-thread
adoption deltas when its owner is measurable. Otherwise its fallback subtracts
`getTotalThreadAllocatedBytes` readings. `seon.profile/measure` similarly uses the
JVM counter on virtual callers, but labels its scope JVM. Its platform branch
counts only the calling thread, not the arm's adoption sum. Fix both producers'
contracts and scope semantics together; changing executor placement alone does
not make these two metrics identical.

`seon.sci.kernel/adopt-arm` only adds an adoption delta when both readings are
nonnegative. A virtual adopter contributes no own-thread delta. The existing task
`seon.sci.kernel/sci-pmap-escapes-the-arm` also identifies ungoverned child work.
Late or unavailable contributions must make a promised inclusive total unknown;
they must not silently produce a complete-looking total or zero.

## JDK and JFR alternatives

The [JEP 444 management section](https://openjdk.org/jeps/444#Observing-virtual-threads)
limits ThreadMXBean to platform threads and says JFR can associate allocation
events with virtual threads. These are different capabilities. The OpenJDK fix
[8303242 review](https://github.com/openjdk/jdk/pull/12762) corrected filtering to
return platform-thread information only; it did not add virtual allocation counts.
The [JDK 26 regression test](https://github.com/openjdk/jdk/blob/jdk-26%2B35/test/jdk/com/sun/management/ThreadMXBean/VirtualThreads.java)
explicitly expects -1 from both allocation methods on virtual threads.
JBS direct retrieval returned HTTP 403; the primary OpenJDK review and release-tag
source were accessible. No claim here depends on an inaccessible bug description.

- **jdk.ObjectAllocationSample:** virtual-thread event attribution is useful for
  diagnosis. The [JDK 26 metadata](https://github.com/openjdk/jdk/blob/jdk-26%2B35/src/hotspot/share/jfr/metadata/metadata.xml)
  defines a throttled sample with a statistical weight. A short evaluation may
  have no sample; summing samples is an estimate, and no sample never means zero.
  An evaluation id/window is needed because a proc thread serves multiple forms.
  It is suitable for allocation hot spots, not a synchronous exact reply field.
- **jdk.ThreadAllocationStatistics:** [JDK 26 emission](https://github.com/openjdk/jdk/blob/jdk-26%2B35/src/hotspot/share/jfr/periodic/jfrPeriodic.cpp#L493)
  walks native JavaThreads, reads cooked allocation bytes, and uses JVM thread ids.
  Its [iterator](https://github.com/openjdk/jdk/blob/jdk-26%2B35/src/hotspot/share/jfr/utilities/jfrThreadIterator.hpp)
  holds a ThreadsListHandle; it does not enumerate logical virtual threads.
  Periodic cumulative carrier/platform totals cannot be subtracted into one
  virtual evaluation's exact total.
- **Allocation-in-TLAB/outside-TLAB events:** not a complete per-object ledger.
  Attributing a refill to its current virtual thread does not attribute later
  allocations by other virtual threads sharing that carrier's TLAB. Neither
  these nor sampling supplies the requested cheap exact counter.
- **Carrier reflection:** opening java.lang permits access to an internal carrier
  identity, not continuity of ownership. Blocking can unmount, carriers can change,
  and other virtual threads allocate on a carrier between readings. Reject it.
  [JEP 444 scheduling](https://openjdk.org/jeps/444#Scheduling-virtual-threads)
  explicitly specifies no carrier affinity.

JFR implementation inspection used OpenJDK release tag `jdk-26+35`, distinct from
the installed vendor's `26.0.1`; JFR was not enabled or benchmarked on default.
The live MXBean probes below independently confirm the relevant JDK 26.0.1 behavior.

## Ranked options

Simplest viable first. Costs are structural unless a measured number is stated.

| Rank / option | Guarantee | Cost per evaluation | Sacrifice and blocking location |
|---|---|---|---|
| 1. Remove fallback; omit allocation when unavailable — **recommended immediate correction** | No concurrent-JVM total presented as this evaluation's bytes; timing/value still return | O(1) capability check; removes two JVM-wide readings | No default virtual-thread allocation number; waits stay on virtual threads. Preserve an explicit unknown scope/reason, never nil/zero as a measurement |
| 2. Platform handoff of complete evaluation/arm extent — **recommended to restore thread attribution** | Platform body independent of unrelated threads; completed measurable adopted spans can be included under a declared scope | Two counter reads plus one Flow Future/dispatch/join; observed warm handoff ~175 µs, counter read ~108 ns; one occupied OS worker per active body | Blocking inside a form holds the mixed/platform worker; larger native-stack/thread footprint; off-thread virtual/writer work remains unknown. Requires admission and actual-exit ownership |
| 3. Change current turn proc to compute | Current default makes transform counter measurable, but no guarantee under arbitrary executor overrides | One dispatch/Future wait per transform; holds worker for provider + forms + record + tests | Wrong workload for blocking; cached thread growth today, starvation if bounded; five-second timeout can leave overlapping transforms. Reject as the present fix |
| 4. Declare whole proc mixed or replace all graph I/O threads with platform threads | Own-thread count available only if executor actually platform | One platform worker per long-lived proc, idle or active | Violates Seon's proc construction policy; mailbox/schedule idle waits also occupy OS workers. Reject scaling cost |
| 5. JFR sampling for allocation diagnosis | Statistical hot spots with virtual-thread identity | Work follows samples/events; stream aggregation and delivery latency; not measured here | No exact short-evaluation result; cannot drive allocation pass/fail gates |
| 6. Carrier delta through add-opens | No valid evaluation attribution guarantee | Two reads may be cheap, but cannot recover attribution across unmounts | Unsupported internals and contaminated carrier totals. Reject |

For option 2, compose `clojure.core.async.flow/futurize` with
`clojure.core/bound-fn*` and the existing execution owner. Use mixed semantics for
the finite evaluation body, not a mixed proc loop. Do not use the I/O executor for
that handoff: it is virtual here. Before calling the metric measurable, verify the
selected worker is platform; an executor-factory change can invalidate that choice.

Arm acquisition, SCI/Clojure bindings, evaluate/print observation extent, record
reading, and arm stop must all stay inside that worker extent. Merely hopping the
body after arming on the virtual caller leaves the owner counter unavailable.
Keep provider waits and final recording orchestration outside that extent where
the existing pipeline permits it; effects inside authored forms still block.
When entered under an existing arm, preserve the governing arm rather than invent
a second evaluation/deadline.

The proc remains serial and awaits the worker's actual exit. Cancellation or an
expired wait does not authorize another evaluation for that agent. Wire the
existing interrupt/thread holder and live-work ownership to the worker; preserve
custody/projection bindings explicitly because futurize does not convey them.
Bound admitted active platform evaluations with an owning configuration dial;
the cached mixed pool itself supplies no bound. The existing
`seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads` task
documents why silently consuming that pool without admission is insufficient.
This is a reviewed platform slice, not an implementation made by this research lane.

Future proofs should tighten `seon.eval-alloc-test/an-evaluation-reply-shows-what-it-allocated`
and `seon.eval-alloc-test/a-measured-call-reports-allocation-and-reads`: use bounded
concurrent allocator traffic, assert scope, and require the own allocation to stay
independent of that traffic. The existing virtual/JVM-scope assertion deliberately
encodes today's behavior and must change with its producer. Exercise worker
cleanup with `seon.sci.kernel-arm-carriage-test/every-evaluation-exit-releases-the-thread-arm`
and `seon.sci.kernel-arm-carriage-test/entrances-on-another-thread-reach-the-governing-arm`;
add a regression for timeout followed by a queued evaluation proving no overlap.
These are future implementation proofs, not evidence that this report installed a fix.

## Live read-only evidence and verification limits

Every MCP request explicitly used cluster default, agent alloc-research-sol. SCI
probes used its disposable branch; JVM probes used host evaluation with no defs,
reloads, transactions authored by the probes, or runtime setting changes. Ordinary
entrant bookkeeping belongs to that agent's branch. The source probe/code is
recorded at each result handle; the essential reproducible forms follow.

| Result handle | Mode / probe | Reply ms | Reply alloc-mb | Observation |
|---|---|---:|---:|---|
| result/e1476c7f092c4 | SCI, `(seon.context/current-state)` | 2 | 1.9 | 735 calls; minimal turns-left block, not full task assignment |
| result/eb03464152e57 | JVM, current thread + ManagementFactory MXBean | 2 | absent | JDK 26.0.1; platform connection thread; supported/enabled allocation; current 9,035,992, by-id 9,035,888 |
| result/e8c037b25476b | SCI, current Thread identity | 1 | 0.3 | virtual true, empty name, id 3601; allocation reply is today's JVM fallback |
| result/e63dc08e84174 | JVM, futurize on compute executor | 2 | absent | async-compute-1, virtual false, current 200, by-id 304 |
| result/e2bde4c986f4e | JVM, futurize I/O and mixed; executor and resource URLs | 2 | absent | I/O -1/-1; mixed async-mixed-1559 measurable; compute core 0/max 2,147,483,647 |
| result/eb83c9e02ed4d | JVM, 100 warm mixed handoffs; 10,000 current-counter reads | 21 | absent | 175.157 µs/handoff; 16,880 caller bytes for 100; 107.738 ns/read. Worker allocations not measured in handoff cost |
| result/ec2b8cad2d86a | JVM, concurrent mixed workers allocate 1 MB and 16 MB | 47 | absent | own-thread delta 1,000,288; JVM delta 17,000,600; own byte array length 1,000,000 |

Counter values read sequentially differ because probe plumbing itself allocates;
they are not simultaneous equality assertions. Warm handoff timing is one bounded
live sample, includes shared-load scheduling, and does not price SCI acquisition,
native worker creation, or a production handoff implementation. No benchmark here
claims retained heap or native-stack size.

Reproduce thread/executor behavior in JVM mode:

```clojure
(let [b (java.lang.management.ManagementFactory/getThreadMXBean)
      inspect (fn []
                (let [t (Thread/currentThread)]
                  {:virtual? (.isVirtual t)
                   :name (.getName t)
                   :own (.getCurrentThreadAllocatedBytes
                         ^com.sun.management.ThreadMXBean b)
                   :by-id (.getThreadAllocatedBytes
                           ^com.sun.management.ThreadMXBean b (.threadId t))}))]
  (into {} (map (fn [workload]
                  [workload (.get ^java.util.concurrent.Future
                                  ((clojure.core.async.flow/futurize inspect :exec workload))
                                  1 java.util.concurrent.TimeUnit/SECONDS)]))
        [:io :compute :mixed]))
```

Reproduce concurrent attribution without a Seon source change:

```clojure
(let [b ^com.sun.management.ThreadMXBean
        (java.lang.management.ManagementFactory/getThreadMXBean)
      start (java.util.concurrent.CountDownLatch. 1)
      done (java.util.concurrent.CountDownLatch. 1)
      noisy ((clojure.core.async.flow/futurize
              (fn []
                (.await start)
                (try
                  (dotimes [_ 16]
                    (java.util.Arrays/fill ^bytes (byte-array 1000000) (byte 1)))
                  (finally (.countDown done)))) :exec :mixed))
      measured ((clojure.core.async.flow/futurize
                 (fn []
                   (let [before (.getCurrentThreadAllocatedBytes b)
                         total-before (.getTotalThreadAllocatedBytes b)
                         own (byte-array 1000000)]
                     (.countDown start)
                     (.await done)
                     {:own-array-length (alength own)
                      :thread-delta (- (.getCurrentThreadAllocatedBytes b) before)
                      :jvm-delta (- (.getTotalThreadAllocatedBytes b) total-before)}))
                 :exec :mixed))]
  (.get ^java.util.concurrent.Future noisy 1 java.util.concurrent.TimeUnit/SECONDS)
  (.get ^java.util.concurrent.Future measured 1 java.util.concurrent.TimeUnit/SECONDS))
```

One failed exploratory JVM form tried nonexistent
`seon.cluster.agent/routing-entry`, so compilation returned an error at 0 ms,
handle result/e8f171855c524. No runtime roster was mutated. That attempted symbol
is **not an API**. Its missing error type is covered by the existing task
`seon.sci.kernel/an-agent-exception-has-no-error-type`; do not interpret that reply
as a successful probe. Later thread/executor probes use verified installed seams.

Initial MCP health was faulted: 5 open faults, 23 errored receipts, 17 over-bound
warnings, 2 cache-discipline warnings, 2 untested-hot warnings and one unowned
namespace. Operator health was unknown, despite reachable/ready layers. Existing
slow owners included `seon.schema/call-with-projection-state`,
`seon.sci.eval/call-detecting-program-change`, and `seon.await/await-port-operations`.
Their reported profile maxima were 134,722, 122,319 and 159,483 ms; these are
existing process-wide observations, not timings of this lane's bounded probes.
The target allocation defect remains
`seon.sci.kernel/alloc-mb-on-a-virtual-thread-measures-the-whole-jvm`.

Foreign working-tree boundary at entry: bootstrap, db, schema/datahike, task,
their task resources and db/help/task-ingestion tests already had other lanes'
changes; http-kit also had untracked submodule content. None was edited or adopted
here. Full requested `clj-kondo --lint src script test --cache false --parallel`
completed in 9,196 ms: **0 errors, 1,490 warnings**, exit 2 for warnings. No blanket
green/zero-findings claim; broad tests and existing test reds were not surveyed.

No executable declaration changes, so no tests reach the changed report. The
documentation gate in `bin/lane` intentionally skips publication/adoption/JVM boot
when no tests are named; `seon.test.cache/input-roots` excludes ordinary research
Markdown. Use the assignment's documentation-only commit command; do not widen a
program gate merely to commit this research. Net source/test lines: 0/0.
The attempted own-branch cleanup form was
`(let [branch (:my.agent/own-branch (my.agent/branches))]
   (my.agent/retire-branch! {:seon.store/branch branch}))`.
The entrant returned `{:seon.error/message "run transition refused: admission-closed"}`
with no handle or timing envelope. Its body and retirement are not proven to have
run; the research branch is preserved as an uncertain resource for root, with no
attempt to resume or bypass admission. This is a lifecycle/diagnostic verification
limit, not a successful cleanup. File claims are released at landing. This lane
did not change default's running archive or runtime settings.
