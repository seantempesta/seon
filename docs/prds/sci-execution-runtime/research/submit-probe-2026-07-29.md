---
type: research
status: active
tags: [research, flow, scheduling]
---

# `submit!!` and tagged-I/O scheduling probe

Owner questions, verbatim intent:

> Can we test submit!! in a REPL and see if it works the way we think? Can it
> turn forms into code and send them to be computed and then get the results
> back?

> Is my dream possible: all io functions TAGGED, all untagged assumed COMPUTE,
> and a dynamically built execution graph that efficiently schedules everything
> and parks when waiting? If we can nail this mechanism the system works
> efficiently. If not, we do :mixed-style simplicity for now and optimize later.

## Verdict

**`submit!!` works for the ordinary round trip.** After installing its launcher
from the two required config facts, it accepted a closure, ran it on the bounded
compute executor, and returned its value. A submitted work function also read
two source forms through `seon.sci.reader`, evaluated them through
`seon.sci.eval/evaluate` on one shared SCI ctx, and returned the second form's
value. Form 1 defined `offset`; form 2 evaluated `(+ offset 2)` to `42`.

**The scheduling dream is mechanically possible at one intercepting I/O
boundary, but it cannot be nailed now by leaf tags plus a raw form walk.** A
direct list head can resolve to a Var and expose its `:seon.workload` metadata.
Locals, higher-order arguments, `apply`, aliases introduced through `require`,
transitive calls, unknown macros, and an untagged function that blocks defeat
that inference. “Untagged means compute” is therefore not a safe negative
classification.

The corrected-B mechanism from
[[workload-scheduling-truth-2026-07-29]] did work in a toy: each whole SCI eval
ran on a virtual thread; a tagged I/O leaf released the compute permit,
dispatched work to core.async's `:io` executor, awaited it, and reacquired the
permit before returning to SCI. With eight evals, compute concurrency two, and
one 100 ms wait between two real interpreted compute segments, the median was
**431.54 ms** for whole-eval lifetime admission and **109.88 ms** for the split,
a **3.93×** difference. I/O overlap rose from 2 to 8. The split retained the
same eight values.

The same probe completed with the virtual-thread scheduler forced to one carrier
and one maximum carrier. The split still overlapped all eight waits and completed
in 107–115 ms. The eval thread's blocking `Future.get` therefore parked and
released the only carrier; otherwise the dispatched I/O virtual thread could
not have run.

**Recommendation: boring-for-now.** Run each whole eval on one virtual thread
behind a bounded lifetime-admission gate. Do not use core.async `:mixed`, which
is a cached platform-thread loop, and do not add per-segment permit machinery
yet. The fresh SCI binding table exposes no real I/O capability today, so the
toy's four-wave cost is not a current production eval cost. Before the first
blocking capability enters SCI, put permit release/reacquisition at the one
`seon.effect` leaf boundary. That explicit boundary, not form rewriting or a
per-form execution graph, is the smallest mechanism that produced the desired
parking and overlap.

`submit!!` is not ready to become that production path until its unbounded
pre-start wait is fixed. Both filed startup defects reproduced for real and the
probe has an `--expect-fixed` mode that exits nonzero today.

## Dependency ledger

| dependency or mechanism | selected revision | source read or exercised |
|---|---|---|
| Seon tree | `eccffa01db7eb67b9e7d0c895f264b8b26b1ba83`; scheduling sources unchanged from probe-start `05db9d8e839dc18223ce383176f9ebe642a7d43c` | `src/seon/flow.clj`, `src/seon/sci/reader.cljc`, `src/seon/sci/eval.clj` |
| core.async Flow | `1.10.874-alpha3`; submodule `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `flow/impl.clj`, `impl/dispatch.clj` |
| SCI | local root; submodule `8fac6e88f32d` | `reference-code/sci/src/sci/core.cljc`, `sci/interrupt.cljc`; exercised through Seon's reader/evaluator |
| Datahike | local root; submodule `9a7a9ef10a95` | present on the selected `:dev` classpath; this probe performs no database work |
| Clojure/JVM | Clojure `1.12.5`; OpenJDK `26.0.1` | `deps.edn`; live `clojure -M:dev` processes |
| Existing acceptance idiom | current tree | `test/seon/flow_test.clj` production-launcher wedge and fixed-buffer cases; `test/seon/sci/eval_test.clj` guarded-eval cases |

The probe scripts are:

- `tmp/submit-probe/submit_roundtrip.clj`
- `tmp/submit-probe/startup_wait.clj`
- `tmp/submit-probe/workload_split.clj`
- `tmp/submit-probe/run.sh`
- `tmp/submit-probe/run_single_carrier.sh`

All are source-classpath scripts. No `src/` file was edited.

## 1. Ordinary `submit!!` round trip

Command:

```bash
clojure -M:dev tmp/submit-probe/submit_roundtrip.clj
```

The script installs the exact setup `submit!!` requires:

```clojure
{:seon.config.flow.compute/queue-depth 2
 :seon.config.flow.compute/concurrency 2}
```

`install-work-launcher!` creates the fixed executor, creates and starts the Flow
graph, resumes it, and publishes it through the process atom
(`src/seon/flow.clj:384-430`). `submit!!` injects a disposable work map into
the launcher's fixed input channel and awaits the start/result promises
(`src/seon/flow.clj:450-484`).

### 1.1 Closure in, value out

The submitted closure computed `(+ 20 22)` and returned:

```clojure
{:seon.flow/outcome :seon.flow/completed
 :seon.flow/value
 {:submit-probe/answer 42
  :submit-probe/thread "pool-1-thread-1"
  :submit-probe/virtual? false}
 :seon.flow/submission-wait-ms 0}
```

This confirms both halves of the present mechanism: the closure is scheduled
and its ordinary return value is delivered back to the submitting caller. It
also confirms that the current executor is a platform pool, matching
`bounded-platform-executor` (`src/seon/flow.clj:129-133`).

### 1.2 Source string to forms to submitted SCI results

Inside the submitted `work-fn`, the probe passed this string to
`seon.sci.reader/read`:

```clojure
(do (def offset 40) :defined)
(+ offset 2)
```

The reader returned two ordered events carrying both `::reader/form` and
`::reader/source`, as its production event builder promises
(`src/seon/sci/reader.cljc:298-355`). The work function created one fork of the
base SCI ctx and passed the same ctx to both `evaluate` calls. Results:

| event | evaluated value |
|---|---:|
| `(do (def offset 40) :defined)` | `:defined` |
| `(+ offset 2)` | `42` |

The outer `submit!!` result was `:seon.flow/completed` and carried those
evaluation maps back to the caller. This is the requested
forms → submit → compute → result round trip. It also demonstrates why the ctx
must span the form reduce: `evaluate` deliberately uses a supplied ctx as given
(`src/seon/sci/eval.clj:330-348`).

### 1.3 SCI time limit through the submitted work

A third work function evaluated `(loop [] (recur))` with a 40 ms SCI time
limit. The outer submission completed normally with the evaluator's flat error
value:

```clojure
{:seon.error/kind :seon.sci.eval/time-limit
 :seon.error/message "Ran out of time after 44ms."}
```

The record reported outcome `:time`, about 4.07 million function-body entries,
and an `interrupted-at` instant. Thus `submit!!` does not interfere with SCI's
own `:interrupt-fn` time limit.

## 2. Buffer, submission time limit, and wedge behavior

Command:

```bash
clojure -M:dev tmp/submit-probe/startup_wait.clj
```

### 2.1 Fixed-buffer backpressure

The launcher was paused with queue depth two and compute concurrency one. Three
callers invoked `submit!!`. The datafied input channel reported:

```clojure
{:count 2 :capacity 2}
```

All three caller futures were blocked. Two submissions occupied the fixed
buffer; one caller necessarily remained blocked in injection because the buffer
was full. After resume, all three returned exactly once with values
`:queued-0`, `:queued-1`, and `:backpressured-2`; the buffer returned to
`0/2`. This verifies bounded queueing and no loss. It does not claim which
concurrent caller won either buffered position.

The implementation matches the observation: the work-launcher loop selects the
submission channel only while running and below its active-count bound
(`src/seon/flow.clj:296-314`), and the graph gives that input the configured
fixed buffer (`src/seon/flow.clj:366-382`).

### 2.2 Submission time limit and wedged-work marking

A work function called `started!`, then awaited a latch. Its `submit!!`
time limit was 30 ms. The caller returned:

```clojure
{:seon.flow/outcome :seon.flow/time-limit
 :seon.flow/submission-wait-ms 0}
```

While the underlying worker remained blocked, the capacity observer returned:

```clojure
{:seon.flow/active-submissions #{:wedged-worker}
 :seon.flow/wedged-submissions #{:wedged-worker}
 :seon.flow/available-capacity 0
 :seon.flow/platform-threads? true}
```

This is the designed outer backstop: a timed-out caller marks an entry already
present in `active-work` as wedged (`src/seon/flow.clj:470-479`). It does not
stop the work. Releasing the latch allowed the worker to finish and capacity to
return.

## 3. Both filed startup-wait defects reproduce

The two issue notes name the same untimed boundary through different stimuli:

- [[../../../seon/issues/flow-submit-waits-forever-before-time-limit]]
- [[../../../seon/issues/submit-awaits-started-with-no-bound]]

The exact source shape remains:

```clojure
(.get injection)
(let [started-at @started
      settled (deref result time-limit-ms ::time-limit)]
  ...)
```

The declared time limit is applied only after the unbounded `@started`
(`src/seon/flow.clj:466-470`).

### 3.1 Paused launcher

With a declared 30 ms limit, a submission to a paused launcher was still
waiting at **151.15 ms**. The input queue was `1/2`. After resume, it returned
`:completed`, reporting **151 ms** of submission wait. The elapsed time before
start did not consume any of the declared limit.

### 3.2 Queued behind a fully wedged worker

With concurrency one, the first worker timed out to its caller and was visibly
marked wedged while still owning all capacity. A second submission declared a
30 ms limit. It was still waiting for `started` at **155.12 ms**. Only after
the first latch was released did the second start and return `:completed`,
reporting **155 ms** of submission wait.

The committed failing-probe command is:

```bash
clojure -M:dev tmp/submit-probe/startup_wait.clj --expect-fixed
```

It currently exits 1 with:

```text
submit!! still waits beyond its declared limit before work starts.
```

Once the startup boundary settles both cases within its contract, that mode
will stop throwing. The fix's recurring test should retain the issue notes'
latch/event-driven acceptance and move the invariant into
`test/seon/flow_test.clj`; this research script is the executable red
reproducer, not a substitute test runner.

## 4. What a recursive form walk can know

`tmp/submit-probe/workload_split.clj` defines three Vars carrying
`^{:seon.workload :io}` and walks forms returned by `seon.sci.reader`. For each
list it tries to resolve the head and reads the Var metadata. Results:

| form shape | direct-head result | honest conclusion |
|---|---|---|
| `(toy-io-sleep 10)` | Var resolves, workload `:io` | direct known leaf is visible |
| `(let [f toy-io-sleep] (f 10))` | `f` does not resolve | local target is runtime data |
| `(map toy-io-value [1 2])` | head is untagged `map`; tagged Var is only an argument | syntax does not say how/where the argument is called |
| `(apply toy-io-sleep [10])` | head is untagged `apply`; tagged Var is only an argument | dynamic target is not a direct call edge |
| `(untagged-wrapper 10)` | wrapper Var resolves with no workload | raw form lacks the wrapper's transitive edge to tagged I/O |
| `(untagged-blocker 10)` | Var resolves with no workload | convention violation is indistinguishable from safe compute |
| same-form `require` introducing alias `p`, then `(p/toy-io-sleep 10)` | `p/toy-io-sleep` does not resolve before executing the require | resolution needs namespace/alias state, not merely the form |

Scanning every symbol is not a solution. It sees `toy-io-value` as a value
passed to `map`, but cannot infer when, how often, or on which synchronous stack
it is invoked. It also creates false positives for a tagged Var merely stored
as data. Macro expansion can introduce a call absent from the input form.

The production reader already behaves conservatively: it tracks aliases and
refers for known namespace forms and drops attribution after an arbitrary
top-level invocation that could change the namespace
(`src/seon/sci/reader.cljc:268-296`). The N5 program graph must likewise retain
resolved calls plus uncertainty. Absence of a direct tagged leaf is not
evidence of compute safety.

Therefore:

- **Vars: yes**, when the exact current namespace/binding table resolves the
  direct symbol.
- **Locals, higher-order calls, `apply`, dynamic dispatch: no**, not from a raw
  recursive walk.
- **Transitive and required code: no**, not without acquired program facts and
  resolved/uncertain call edges.
- **Untagged blockers: fundamentally no**. A convention can forbid them, but a
  form walker cannot prove the convention held.

## 5. Corrected-B toy with real SCI in the middle

The evaluated source was:

```clojure
(do
  (reduce + (map inc (range 3000)))
  (submit-probe.workload-split/toy-io-sleep 100)
  (reduce + (map inc (range 3000))))
```

Each task used a real `seon.sci.eval/evaluate`, not a stand-in compute closure.
The two reductions are interpreted compute on the eval virtual thread. The
middle function is a copied host Var tagged `:io`.

Two scheduling shapes were compared after warmup:

1. **Boring:** acquire one of two lifetime slots, hold it across the complete
   eval, and perform the 100 ms wait inline on that eval virtual thread.
2. **Split:** acquire one of two CPU permits; at the tagged leaf release it,
   execute a `FutureTask` on core.async's `executor-for :io`, await it on the
   eval virtual thread, reacquire the CPU permit, and resume SCI.

Ordinary-scheduler trials:

| trial | whole-eval lifetime gate | split at tagged leaf |
|---:|---:|---:|
| 1 | 433.67 ms | 112.23 ms |
| 2 | 426.45 ms | 109.88 ms |
| 3 | 431.54 ms | 108.86 ms |
| **median** | **431.54 ms** | **109.88 ms** |

For every trial:

- boring maximum overlapping I/O = 2;
- split maximum overlapping I/O = 8;
- all eval threads reported virtual;
- all dispatched I/O threads reported virtual; and
- all eight admitted values were `4501500`.

Single-carrier command:

```bash
tmp/submit-probe/run_single_carrier.sh
```

This adds:

```text
-Djdk.virtualThreadScheduler.parallelism=1
-Djdk.virtualThreadScheduler.maxPoolSize=1
```

Single-carrier trials measured 419.72–423.90 ms for boring and
107.28–115.48 ms for split, again with I/O overlap 2 versus 8 and identical
values. The only carrier necessarily moved from the waiting eval to the
dispatched I/O task, proving the await parked rather than occupying it.

### What this proves

- A virtual eval thread can synchronously await an I/O result without consuming
  its carrier.
- Releasing a separate compute permit at a known leaf prevents blocked I/O from
  consuming compute capacity.
- The SCI continuation resumes normally after reacquisition.
- No I/O channel is required merely for parking; an executor task plus a
  synchronous virtual-thread await suffices.

### What this does not prove

- It does not derive the leaf from arbitrary agent code. The toy explicitly
  copied and wrapped the known leaf.
- It does not split an arbitrary running SCI stack by rewriting forms. The
  split occurs at the host boundary where control is already intercepted.
- It does not make an untagged blocker safe.
- It does not resolve higher-order/dynamic targets.
- It does not eliminate the bounded-outstanding-submission requirement.
- It does not repair `submit!!`'s startup wait or convert its platform executor
  to virtual threads.

## 6. Decision

Do **not** nail the full dream now as “tag leaves, assume every untagged call is
compute, walk each form, build an execution graph.” The assumption is unsound,
and the graph cannot migrate or split a synchronous SCI frame merely because
syntax mentioned a tagged Var.

Use the boring shape now:

```text
bounded outstanding submissions
  → one virtual thread per admitted whole eval
  → synchronous evaluate
  → settle
```

That shape is simple, parks correctly if it blocks, keeps the SCI time limit,
and avoids core.async `:mixed`'s platform-thread-per-proc cost. Its measured
worst-case toy cost is honest: with `M=8`, `C=2`, and `L=100 ms`, it took four
wait waves, median 431.54 ms, versus one overlapped wave at 109.88 ms. The
penalty was about 321.65 ms and 3.93× in this intentionally I/O-heavy case.

When the first real blocking capability enters SCI, extend the one effect owner:

```text
SCI compute (CPU permit held)
  → tagged `seon.effect` leaf
  → release CPU permit
  → run/await blocking I/O on `:io`
  → reacquire CPU permit
  → return value to SCI
```

Tags remain useful for program-graph classification, audits, and eventually
specializing proven compute kernels. They are not the runtime split by
themselves. Unknown, higher-order, dynamic, macro-introduced, and unresolved
edges must stay conservative until N5 owns their facts.
