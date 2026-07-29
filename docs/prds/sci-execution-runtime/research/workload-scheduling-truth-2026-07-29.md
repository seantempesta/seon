---
type: research
status: active
tags: [research, flow, scheduling]
---

# Workload scheduling truth: blocking, parking, and the compute door

Owner question, verbatim intent:

> `:compute` is dangerous because any blocking uses up a limited pool. Ideally I
> want a scheduler that sends the io portions via an io channel and parks the
> compute side until they come back — or is that what `:mixed` is? Look into
> what's actually happening; we shouldn't be guessing.

## Verdict

`:mixed` does **not** split a synchronous call into compute and I/O portions.
In the pinned core.async, it runs the proc's whole blocking loop and transform
inline on one cached-pool **platform thread**. `:compute` is the only Flow mode
that performs an executor hop, and that hop moves the **whole transform** to a
compute executor while the proc's I/O loop waits for its `Future`; it does not
identify or move I/O inside the transform
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-263,270-323`).

Core.async itself does **not** provide the bounded-approximately-cores compute
pool assumed in some Seon prose. At the vendored revision, both `:compute` and
`:mixed` default to `Executors/newCachedThreadPool`; only `:io` starts a virtual
thread per task when the JVM supports it
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96`).
The bounded compute pool is Seon's override, and production agent evals bypass
it today (`src/seon/flow.clj:129-133,366-407`;
`src/seon/cluster/agent.clj:243-267,337-341`;
`src/seon/cluster/loop.cljc:718-756`).

The recommendation is **design B, corrected**: run each eval on a virtual
thread, retain a bounded lifetime-admission/backpressure bound, and separately
hold at most `C` CPU permits only while the eval is computing. A plain
platform-to-virtual executor swap is insufficient: the current launcher's
`active-count < parallelism` condition holds a slot for the whole synchronous
eval, including any blocking host call, so it preserves logical starvation
even though the carrier thread is free (`src/seon/flow.clj:296-314`). When real
I/O capabilities enter SCI, the one `seon.effect` boundary must release the CPU
permit around the synchronous I/O leaf and reacquire it before returning to
interpreted work. There is no need for an I/O channel merely to make the JVM
park; a synchronous blocking call on a virtual thread already does that on the
JDK Seon runs.

## Dependency ledger

| dependency or mechanism | selected source | relevant owners |
|---|---|---|
| core.async Flow | `1.10.874-alpha3`, vendored commit `dc35f3e0d7bc2eef502e77982f48641f025c8051`; selected in `deps.edn:17-20` | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj`, `flow/impl.clj`, `flow.clj` |
| SCI | vendored commit `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; local root selected in `deps.edn:41-44` | `reference-code/sci/src/sci/interrupt.cljc`, `sci/impl/evaluator.cljc`, `sci/lang.cljc`, `sci/impl/load.cljc` |
| Datahike | vendored commit `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`; local root selected in `deps.edn:21-25` | `reference-code/datahike/src/datahike/writer.cljc`, `tools.cljc`, `connector.cljc`, `committed_report.cljc` |
| Clojure | `1.12.5`, selected in `deps.edn:13-20` | promise implementation at `reference-code/clojure/src/clj/clojure/core.clj:7326-7362` |
| JVM | live toolchain OpenJDK `26.0.1`; comparison install Temurin `21.0.11` | local `lib/src.zip!/java.base/java/lang/VirtualThread.java`, `jdk/internal/vm/Continuation.java`, and `java/util/concurrent/locks/LockSupport.java` |
| Seon scheduling | tree at audit start `770834a60be3c4a59686231f5e4343350c9bb9fb` | `src/seon/flow.clj`, `src/seon/cluster.clj`, `src/seon/cluster/agent.clj`, `src/seon/cluster/loop.cljc` |
| Seon eval and classification | same tree | `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `src/seon/schema.cljc` |

The dependency SHAs above are the checked-out submodule heads, not version
guesses. The project selects core.async by its published alpha coordinate and
SCI/Datahike by the vendored roots (`deps.edn:17-25,41-44`).

## 1. What core.async actually schedules

### 1.1 Executor construction

`dispatch/executor-for` memoizes one executor per workload. A system-property
factory may override it; otherwise `create-default-executor` supplies the
following (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:91-116`):

| workload | pinned alpha3 default | bound |
|---|---|---|
| `:io` | an `Executor` whose `execute` reflectively calls `Thread/startVirtualThread`; cached platform pool only when virtual threads are unavailable (`dispatch.clj:75-89`) | no task-count bound in this executor |
| `:compute` | `Executors/newCachedThreadPool` with named daemon platform threads (`dispatch.clj:71-73,91-96`) | unbounded cached pool |
| `:mixed` | the same cached-platform-pool constructor, separately memoized (`dispatch.clj:71-73,91-96`) | unbounded cached pool |

There is a source/doc discrepancy worth not inheriting: the `executor-for`
docstring says `:core-async-dispatch` defaults to `:io`, but the implementation
routes it to `:mixed` (`dispatch.clj:98-111`). This report follows executable
source.

A live probe on the selected classpath confirmed the constructors and threads:

```clojure
(require '[clojure.core.async.impl.dispatch :as d])
(defn probe [k]
  (let [p (promise)
        e (d/executor-for k)]
    (.execute ^java.util.concurrent.Executor e
              #(deliver p {:workload k
                           :executor-class (.getName (class e))
                           :thread (.getName (Thread/currentThread))
                           :virtual? (.isVirtual (Thread/currentThread))}))
    @p))
(mapv probe [:io :compute :mixed])
```

```clojure
[{:workload :io,
  :executor-class clojure.core.async.impl.dispatch$make_io_executor$reify__175,
  :thread "",
  :virtual? true}
 {:workload :compute,
  :executor-class java.util.concurrent.ThreadPoolExecutor,
  :thread "async-compute-1",
  :virtual? false}
 {:workload :mixed,
  :executor-class java.util.concurrent.ThreadPoolExecutor,
  :thread "async-mixed-1",
  :virtual? false}]
```

### 1.2 Flow proc behavior

`create-flow` accepts optional `mixed-exec`, `io-exec`, and `compute-exec`
overrides and otherwise resolves each through `dispatch/executor-for`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-58,145-148`).
`flow/process` resolves one workload once, defaulting to `:mixed`
(`flow/impl.clj:243-247`).

The proc launcher then has exactly two shapes:

- For `:compute`, `transform` futurizes the entire step call onto the compute
  executor and synchronously waits up to `compute-timeout-ms`, default 5000 ms
  (`flow/impl.clj:245-261`). The proc's outer run loop uses `:io`
  (`flow/impl.clj:262,270-323`).
- For `:io`, `transform` is the step itself and the outer run loop uses `:io`
  (`flow/impl.clj:258-263,270-323`).
- For `:mixed`, `transform` is also the step itself, but the outer run loop uses
  `:mixed` (`flow/impl.clj:258-263,270-323`).

Every outer loop blocks in `<!!`/`alts!!` while paused or waiting for input
(`flow/impl.clj:270-305`). Consequently, a `:mixed` proc occupies one cached
pool platform thread for its lifetime. That is “one platform thread per proc,”
not Loom's technical pinning condition. An `:io` proc instead leaves one parked
virtual thread; a `:compute` proc leaves a parked virtual outer loop and uses a
compute executor only during transforms.

Core.async's own contract matches those mechanics: `:io` may block but must not
do extended computation; `:compute` must not block; `:mixed` is anything else
(`reference-code/core.async/src/main/clojure/clojure/core/async.clj:509-529`;
`flow.clj:194-203`). No source in this path walks a call stack, detects a host
call, or migrates a running synchronous frame between executors.

### 1.3 Where Seon's bound comes from

Seon builds a fixed platform pool with
`Executors/newFixedThreadPool(parallelism)` (`src/seon/flow.clj:129-133`).
The work launcher sets `parallelism` from
`:seon.config.flow.compute/concurrency`, whose computed default is
`Runtime.availableProcessors` (`src/seon/flow.clj:384-400`;
`src/seon/config.cljc:136-147`). On this machine the runtime reports `18`; the
fixed submission channel's configured queue depth is `10`
(`config/default.edn:1-12`).

The work launcher consumes a compute submission only while
`active-count < parallelism`, then increments that count until the task's
completion channel fires (`src/seon/flow.clj:287-314`). Its graph injects the
fixed compute executor as Flow's `:compute-exec`
(`src/seon/flow.clj:366-400`). That is the real bounded `C = 18` mechanism.

There is also a delayed process-root pair with the same fixed compute shape,
but its `:io` member is a cached **platform** pool, not the core.async virtual
executor (`src/seon/cluster.clj:206-233`). The started instance merely stores
that pair (`src/seon/cluster.clj:1027-1031`). No production `create-flow` call
passes it: exhaustive references to `:seon.boot/executors`, `compute-exec`, and
`io-exec` under `src/` end at those definitions and the orphaned work launcher.

Production agent graphs instead call `flow/create-flow` with only `:procs` and
`:conns` (`src/seon/cluster/agent.clj:243-267,337-341`). Their turn proc is
explicitly `:io` (`src/seon/cluster/agent.clj:164-224,261-265`), and the run
loop calls `evaluate` inline (`src/seon/cluster/loop.cljc:718-756`). An
exhaustive `rg 'submit!!' src` finds only its definition and a docstring claim;
the source call path confirms the open blocker
`docs/seon/issues/agent-turns-bypass-the-bounded-compute-door.md`.

## 2. Parking and pinning on Seon's JVM

### 2.1 A core.async blocking take parks a virtual thread

`core.async/<!!` registers a take handler and, when no value is ready, derefs a
Clojure promise (`reference-code/core.async/src/main/clojure/clojure/core/async.clj:150-172`).
Clojure's promise is a `CountDownLatch`; deref calls `.await`, and delivery
counts the latch down
(`reference-code/clojure/src/clj/clojure/core.clj:7326-7362`). On JDK 26,
`LockSupport.park` explicitly dispatches a virtual caller to
`parkVirtualThread`, rather than parking the carrier
(`$JAVA_HOME/lib/src.zip!/java.base/java/util/concurrent/locks/LockSupport.java:167-228`).
`VirtualThread.park` yields its continuation and only falls back to
`parkOnCarrierThread` when the continuation cannot yield
(`$JAVA_HOME/lib/src.zip!/java.base/java/lang/VirtualThread.java:743-849`).

The shortest falsifier used one carrier and one maximum carrier. Two hundred
virtual threads all reached `<!!` on separate empty channels, and a sentinel
virtual thread still ran:

```clojure
{:jdk "26.0.1",
 :parallelism "1",
 :max-pool-size "1",
 :waiters 200,
 :all-started? true,
 :sentinel-ran? true,
 :all-virtual? true}
```

The probe ran with
`-Djdk.virtualThreadScheduler.parallelism=1` and
`-Djdk.virtualThreadScheduler.maxPoolSize=1`; therefore a carrier-consuming
take would have prevented the sentinel from running. It did not.

### 2.2 JDK 21 and JDK 24+ differ materially

Seon is running OpenJDK `26.0.1`. In the installed Temurin 21 source,
`Continuation.Pinned` includes `MONITOR`, and the VM maps reason 4 to that
value
(`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/lib/src.zip!/java.base/jdk/internal/vm/Continuation.java:57-87`).
In the running JDK 26 source, `MONITOR` is gone; the remaining reasons are
`NATIVE`, `CRITICAL_SECTION`, and `EXCEPTION`
(`/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home/lib/src.zip!/java.base/jdk/internal/vm/Continuation.java:57-88`).
This is the delivered JDK 24 change, [JEP 491: Synchronize Virtual Threads
without Pinning](https://openjdk.org/jeps/491).

A direct comparison held a Clojure `locking` monitor while blocking in `<!!`,
again with one carrier and a sentinel:

```clojure
{:jdk "26.0.1", :synchronized-blocking-take-freed-carrier? true}
{:jdk "21.0.11", :synchronized-blocking-take-freed-carrier? false}
```

Thus “`synchronized` pins” is true for JDK 21/22 and false for the JDK Seon
runs. Remaining JDK 26 pinning risks are a native frame or an explicit VM
critical section; the continuation source names both
(`JDK-26-src.zip!/java.base/jdk/internal/vm/Continuation.java:57-88,428-455`).
Arbitrary JNI/foreign host functions therefore still require measurement.

### 2.3 SCI and Datahike source scan

SCI has real monitor use:

- metadata mutation on SCI types, vars, and namespaces uses `locking`
  (`reference-code/sci/src/sci/lang.cljc:34-45,174-180,318-324`);
- namespace loading holds one `load-lock`
  (`reference-code/sci/src/sci/impl/load.cljc:249-270`);
- `alter-var-root` locks the SCI var
  (`reference-code/sci/src/sci/impl/vars.cljc:320-330`).

The ordinary `def` path resets var metadata
(`reference-code/sci/src/sci/impl/evaluator.cljc:25-47`), so at least one of
those short monitor sections is on an agent-authored-def path. On JDK 26 the
monitor does not pin. The load lock could have enclosed a blocking `load-fn` on
JDK 21; that historical risk is also removed by JEP 491. An exhaustive
`rg 'synchronized|locking|monitor-enter|monitor-exit' reference-code/sci/src`
found no other runtime monitor sites, and no SCI runtime source declares a JNI
native method. This does not prove an arbitrary host function exposed to SCI
will not enter native code.

Datahike's runtime source has one explicit `locking` owner,
`datahike.committed-report`, around bounded source-state transitions
(`reference-code/datahike/src/datahike/committed_report.cljc:1-15,99-138,147-220`).
The blocking readiness take occurs outside that source monitor
(`committed_report.cljc:259-284`). Therefore the explicit Datahike monitor is
not a long blocking critical section even on JDK 21, and is not a pin at all on
JDK 26.

Synchronous `d/transact` **does** block its caller: it derefs `transact!`
(`reference-code/datahike/src/datahike/api/impl.cljc:30-48`).
`transact!` returns a `throwable-promise` whose JVM implementation is a
`CompletableFuture` and whose deref calls `.get`
(`reference-code/datahike/src/datahike/tools.cljc:91-124`;
`writer.cljc:363-387`). On a virtual eval thread that wait parks rather than
spending a compute platform thread. The transaction itself still runs through
Datahike's own serial processing and commit loops
(`reference-code/datahike/src/datahike/writer.cljc:85-105,201-210,286-306`);
virtualizing the caller does not make transaction computation or storage I/O
free.

### 2.4 What changes for SCI on a virtual thread

The `:interrupt-fn` is an ordinary zero-argument call on every interpreted
function/loop body entrance
(`reference-code/sci/doc/interrupt.md:6-18,50-65`). Seon's implementation reads
its deadline flag and throws SCI's uncatchable marker
(`src/seon/sci/eval.clj:226-262`;
`reference-code/sci/src/sci/interrupt.cljc:25-42`). It has no platform-thread
dependency.

A real `seon.sci.eval/evaluate` of `(loop [] (recur))` on a JDK 26 virtual
thread returned normally as a time-limited error:

```clojure
{:jdk "26.0.1",
 :virtual? true,
 :eval-outcome :time,
 :eval-duration-ms 57,
 :interrupted-at? true,
 :mxbean-before -1,
 :mxbean-after -1,
 :eval-allocated-bytes -1}
```

So the time-limit semantics survive. The allocation diagnostic does not:
JDK 26's management implementation deliberately returns `-1` for the current
virtual thread
(`$JAVA_HOME/lib/src.zip!/java.management/sun/management/ThreadImpl.java:360-368`).
Seon's evaluator already treats a negative start value as unmeasurable and
records `-1` (`src/seon/sci/eval.clj:203-209,232-262`), so this is loss of one
diagnostic, not a correctness failure.

The existing hard ceiling also remains: the interrupt function is not called
while execution is stuck inside a host function
(`reference-code/sci/doc/interrupt.md:63-93`;
`src/seon/sci/eval.clj:22-32`). Virtual threads make that wait cheap in carrier
threads; they do not make it cancellable. `shutdownNow` is likewise not a hard
kill: Seon's interrupt function polls the deadline flag, not
`Thread.isInterrupted` (`src/seon/sci/eval.clj:226-252`). Whether a blocked
leaf reacts to interruption is that leaf's contract.

No GC correctness problem is exposed by the source. A virtual thread mounts on
a platform carrier while running and unmounts when it yields
(`JDK-26-src.zip!/java.base/java/lang/VirtualThread.java:264-307`); an unmounted
continuation retains its stack in a `StackChunk`
(`JDK-26-src.zip!/java.base/jdk/internal/vm/Continuation.java:108-120,230-290`).
A CPU-bound virtual eval remains mounted and consumes a carrier just like
ordinary running work. Exact GC-pause and stack-chunk costs are performance
questions for a JFR load gate, not reasons found in source to retain platform
eval threads.

## 3. Candidate A versus candidate B

Let:

- `C` = compute concurrency, currently `availableProcessors = 18`;
- `M` = runnable evals;
- `B` = evals currently blocked inside a host I/O call;
- `L` = blocking-call latency.

### A. Fixed platform compute pool

This is the currently orphaned `submit!!` design:
`newFixedThreadPool(C)` (`src/seon/flow.clj:129-133,384-400`).

When an eval blocks inline, it occupies one of the `C` workers. Immediate
compute capacity is:

```text
max(0, C - B)
```

At `B = C`, no queued eval starts until one host call returns. The SCI time
limit does not rescue it because no interpreted body entrance occurs inside
the host call (`src/seon/sci/eval.clj:22-32`). Worse, `submit!!` waits
unboundedly for `started` before it applies `time-limit-ms` to the result
(`src/seon/flow.clj:450-484`), so a submission queued behind `C` stuck workers
has no active clock at all.

For `M` equal-duration blocking evals and no useful overlap, the lower bound is:

```text
ceil(M / C) * L
```

### B. Virtual eval threads with the current lifetime gate

`Executors/newVirtualThreadPerTaskExecutor` creates one virtual thread per task
and is itself unbounded
(`$JAVA_HOME/lib/src.zip!/java.base/java/util/concurrent/Executors.java:248-265`).
It therefore needs Seon's explicit bounds.

Swapping only the executor fixes the OS-thread failure: the `B` waiting evals
park and free their carriers. It does **not** fix admission starvation because
the current launcher refuses to consume another submission while
`active-count = C` and decrements only on task completion
(`src/seon/flow.clj:296-314`). If the lifetime gate remains, effective
admission capacity is still:

```text
max(0, C - B)
```

The virtual scheduler may have idle carriers while Seon's queue remains unable
to start another eval. This is cheaper than A but not the owner's desired
scheduler.

### B corrected. Separate lifetime admission from CPU permits

The necessary shape has two resource facts in the one submission owner:

1. A bounded **outstanding-submission** count, conventionally `C + Q`, preserves
   lifetime backpressure and caps virtual-thread/stack retention. `Q` is the
   configured queue depth (`config/default.edn:1-12`).
2. A `C`-sized **CPU permit** is held only while interpreted/host compute is
   running. The one effect boundary releases it before blocking I/O and
   reacquires it before returning to SCI.

Then `B` parked I/O evals consume outstanding slots but consume zero CPU
permits. Up to `C` other admitted evals can compute, bounded exactly where CPU
is scarce. Reacquiring the permit can itself park the virtual thread without
spending a carrier. This is the semantic behavior in the owner's question; it
is not core.async `:mixed`.

### Probe: the gate distinction is the whole result

The cheap probe submitted `M = 72` tasks, each blocking for `L = 100 ms`, with
`C = 18`:

```clojure
{:processors 18,
 :tasks 72,
 :blocking-ms 100,
 :fixed-platform-pool-ms 417.15075,
 :virtual-lifetime-gate-ms 425.937125,
 :virtual-cpu-segment-gate-ms 102.6715,
 :virtual-unbounded-ms 105.822375,
 :wave-lower-bound-ms 400.0}
```

The fixed pool and virtual threads holding a lifetime semaphore both took four
waves, matching `ceil(72/18) * 100 = 400 ms` plus overhead. Releasing the CPU
permit for the blocking segment matched unbounded virtual-thread parking at
about one wave. The difference is not platform versus virtual alone; it is
whether the scarce compute permit remains held across I/O.

## 4. The effect boundary today

There is no fresh-tree `src/seon/effect.clj`. The only fresh `my.*` namespaces
are `my.run` and `my.message`, and both intentionally return pure values:

- `my.message/send` reads and commits nothing; the loop interprets and commits
  its returned map after eval (`src/my/message.cljc:12-29,100-123`);
- `my.run/wait` and `complete` likewise return dispositions
  (`src/my/run.cljc:6-26,40-90`).

`seon.sci.eval/base` binds only those pure functions plus interrupt-aware
Clojure core/string (`src/seon/sci/eval.clj:136-169`). It explicitly says the
computed capability binding table is N5 and absent
(`src/seon/sci/eval.clj:83-90`). Therefore **no real I/O call occurs inside an
agent eval today**.

The surrounding turn performs I/O synchronously:

- the model call invokes `ai/complete` inline
  (`src/seon/cluster/loop.cljc:600-623`);
- `ai/complete` reaches `HttpClient.send` synchronously on the calling thread
  (`src/seon/ai.cljc:475-518,584-615`);
- run writes call `store/transact!` inline throughout the turn, and its owner
  calls synchronous `d/transact` (`src/seon/cluster/store.clj:415-465`).

Those operations happen in the turn proc already tagged `:io`
(`src/seon/cluster/agent.clj:164-224,261-265`). The stream channel used during a
model response is a presentation `offer!`, not a request/response handoff:
the HTTP call still stays inline (`src/seon/cluster/loop.cljc:491-506,618-623`).

So there is currently no I/O channel round trip to discover, either in SCI or
through a missing `seon.effect`. When capability owners land, keeping their
blocking leaves synchronous on the eval's virtual thread is sufficient for JVM
parking. A channel request/reply is warranted only if it supplies another
needed property—ownership, addressing, observation, or a native-pin isolation
boundary—not merely to avoid blocking a platform compute worker.

## 5. What pre-N5 workload classification can know

### 5.1 What exists

The reader preserves each parsed `::form`, source, offsets, and reading
namespace context (`src/seon/sci/reader.cljc:298-355`). For a `defn`/`defn-`,
it merges metadata from the form, operation, name, and attribute map, and lifts
an explicit `^{:seon.workload :io|:compute}` to
`:seon.fn/workload` (`src/seon/sci/reader.cljc:196-241`). Its regression proves
the lift (`test/seon/sci/reader_test.clj:329-353`).

The durable schema does **not** register either `:seon.fn/workload` or
`:seon.fn/calls`: the complete current function-attribute map is
`src/seon/schema.cljc:506-536`, and the entity shape is
`src/seon/schema.cljc:538-557`. An exhaustive
`rg ':seon.fn/workload|:seon.fn/calls' src/seon/schema* src/seon/schema`
returns no match. The ancestor states the current population honestly:
schema facts today, program facts only when N5's indexer exists
(`src/seon/cluster/ancestor.clj:27-35`).

There is also no fresh `seon.fn/workload` query or `reader/edges` function at
this tree. The current N5 plan requires call/uncertainty edges to be a separate
pure function because they need aliases, refers, known macro heads, and a
resolution basis; the raw reader cannot supply those facts
(`docs/prds/sci-execution-runtime/plan/parse-primitives-plan-2026-07-29.md:487-516`).

### 5.2 What a pre-eval form walk needs

A recursive walk of `::form` can identify a **syntactically direct** list head.
To turn that head into a workload fact it still needs:

- current namespace, aliases, and refers; the reader tracks those and becomes
  deliberately unattributed after a top-level form that may be a
  namespace-changing macro (`src/seon/sci/reader.cljc:268-296`);
- the exact SCI binding table from resolved symbol to host leaf;
- leaf metadata such as `:seon.workload`;
- macro expansion or a conservative uncertainty for unknown macro heads;
- higher-order target information for calls such as `(map f xs)`; and
- transitive edges for calls through another function.

Leaf annotations plus a recursive syntax walk are enough only for “this form
contains a direct call to this known annotated leaf.” They are not a complete
call graph. A form `(helper)` whose already-defined `helper` calls an I/O leaf
needs the helper's transitive edges. A dynamic or higher-order call has no
sound target in raw syntax. A macro can introduce a call absent from the
unexpanded form. Those cases require N5's resolved `:seon.fn/calls` plus
uncertainty facts, or must remain unknown.

Pre-N5, the honest conclusions are therefore:

- a directly resolved `:io` leaf proves the whole synchronous eval is **not
  safe for a bounded platform compute worker**; because the eval also parses,
  interprets, and admits, its whole-call workload is `:mixed`, not pure `:io`;
- a directly resolved `:compute` leaf does not prove the whole form is compute
  unless every other reachable call is also closed and compute-safe;
- absence of a detected I/O call proves nothing; unresolved, macro,
  higher-order, and pre-existing function calls force `:mixed`;
- without program facts, general eval classification cannot soundly return
  `:compute`.

That is stricter than the current plan sentence “empty corpus returns
`:mixed`,” but it explains why: the source contains the form, yet lacks the
resolution and transitive facts needed to prove the dangerous negative
(`parse-primitives-plan-2026-07-29.md:507-537`). Walking forms can choose a
conservative whole-eval destination; it cannot split a synchronous SCI stack
into executor segments.

## 6. Recommendation for the bounded-compute fix wave

Choose **B: virtual eval threads**, with one correction: do not let the
existing lifetime `active-count` masquerade as the compute bound.

1. Route the production eval call through the one process-root submission
   owner and delete the current bypass. Make the eval task executor
   `newVirtualThreadPerTaskExecutor`; do not use core.async `:mixed`, whose
   actual implementation is a cached platform pool
   (`dispatch.clj:71-96`).
2. Preserve bounded lifetime admission/backpressure: at most `C + Q`
   outstanding evals, using acquired config facts (`C` concurrency, `Q` queue
   depth). This bounds retained virtual threads and values.
3. Separately enforce `C` CPU permits. Today no I/O capability is bound inside
   SCI, so a permit may span the whole eval without changing behavior. Before
   the first I/O capability is exposed, make the one `seon.effect` owner
   release the CPU permit around its blocking leaf and reacquire it on return.
   This is one execution seam, not per-frame migration.
4. Treat every pre-N5 eval as unknown/`mixed` and send it to the virtual path.
   Do not “prove compute” from the absence of direct I/O syntax. Once N5 owns
   resolved call and uncertainty edges, pure compute kernels may use the
   derived result; no correctness property should depend on that optimization.
5. Keep the SCI time limit unchanged. Accept and document that
   `:seon.eval/allocated-bytes` is `-1` on virtual threads, while
   `fn-entries`, duration, result caps, and the uncatchable time-limit outcome
   remain. A future JFR measurement may replace the lost allocation diagnostic;
   it does not block the scheduling fix.
6. Fix `submit!!`'s unbounded pre-start wait in the same wave. Backpressure may
   wait, but a fully occupied/wedged owner is observable and must settle queued
   callers through a published event rather than leave `@started` unbounded
   (`src/seon/flow.clj:450-484`).

The math is the decision:

```text
A, or naive B with a lifetime C-gate:
  B blocked evals leave max(0, C-B) capacity.
  M equal waits take at least ceil(M/C) * L.

Corrected B:
  B blocked evals consume 0 CPU permits and B bounded outstanding slots.
  Up to C other admitted evals compute.
  Equal waits approach L, not ceil(M/C) * L.
```

On the audited machine (`C=18`, `M=72`, `L=100 ms`), the direct probe measured
~417 ms for A, ~426 ms for naive B, and ~103 ms for corrected B. Virtual
threads solve carrier consumption; releasing the CPU permit at the one effect
boundary solves compute starvation. `:mixed` solves neither.
