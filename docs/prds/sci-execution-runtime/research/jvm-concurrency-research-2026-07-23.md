---
type: research
status: active
tags: [research, runtime, architecture]
---

# JVM concurrency foundation for hundreds–thousands of agent drivers — 2026-07-23

Owner-directed research unit. Question: what concurrency foundation runs
hundreds-to-thousands of concurrent claim-native agent drivers on the JVM —
mostly-IO work (10–60 s LLM HTTP calls, UDS writer round-trips, blob IO)
punctuated by CPU bursts (sci eval, hiccup rendering) — under the binding
ruling that driver code stays PLAIN SYNCHRONOUS CLOJURE (no async ceremony in
the portable core). Composes with `research/loop-cljc-sci-design-2026-07-23.md`
(claim/fence/lease design, sci context lifecycle §7) and
`research/llm-http-io-design-2026-07-23.md` (attempt lifecycle §1c, the
proposed `java.net.http` leaf §1a).

## Recommendation (3 lines)

1. **Virtual threads are the foundation: one virtual thread per claimed
   run-driver**, spawned via `Thread/ofVirtual` (or
   `Executors/newVirtualThreadPerTaskExecutor`), blocking-style step code
   unchanged — render → LLM `HttpClient.send` → eval dispatch → UDS write all
   park the vthread and release the carrier.
2. **Keep one bounded PLATFORM pool as the CPU bulkhead for sci eval steps**
   (the existing `::eval-pool`, host.clj:276) — vthreads have no preemption,
   so a spinning eval would occupy a carrier until the interrupt lands; the
   driver vthread submits the eval and blocks on the Future (parks cleanly).
3. This is safe **because the environment is already past every historical
   vthread hazard**: JDK 26.0.1 (JEP 491 unpinned monitors since JDK 24),
   Clojure 1.12.0 (LazySeq/Delay on ReentrantLock since CLJ-2804), and the
   vendored driver-path code contains no native frames and no core.async.

## 0. Environment ground truth (pinned)

- **JDK 26.0.1** (Homebrew OpenJDK, `java -version` on this machine;
  `bin/_java-home-resolver` pins the writer/host family to JDK 26 — deps.edn
  `:writer-test` comment "Usage (JDK 26, pinned via bin/_java-home-resolver)").
- **Clojure 1.12.0** (deps.edn:6 and the `:writer` alias :20).
- Writer/host JVM options today: `-XX:+UseG1GC -Xmx512m` (deps.edn `:writer`
  jvm-opts) — a **limits-section item**: 512 MB is sized for the writer alone,
  not for 1k drivers + retained sci contexts in the host process.
- No executor/concurrency library on the JVM driver path today: the
  `:writer` and `:host` aliases carry no core.async, no manifold — plain
  `java.util.concurrent` (host.clj, db/host.clj). core.async enters the JVM
  writer process only transitively through Datahike/Konserve/superv.async.
- Current host concurrency (the thing vthreads replace/extend):
  - `::eval-pool` = `Executors/newFixedThreadPool` of `default-eval-threads`
    10 (src/seon/host.clj:44, 276-277); watchdog =
    `newScheduledThreadPool 2` (host.clj:278); per-invocation deadline →
    `Thread.interrupt` on the pool worker (src/seon/host/invoke.clj:30-34,
    100-103).
  - `seon.db.host` leaf = retained UDS connection pool sized
    `availableProcessors - 1`, `ReentrantLock` + `Condition` waiting with
    `::pool-wait-timeout-ms 1000`, `::call-deadline-ms 120000`, and a
    `newFixedThreadPool` call-executor of the same size
    (src/seon/db/host.clj:14-19, 38-46).
  - UDS transport = blocking `SocketChannel` streams
    (src/seon/db/transport/uds.cljc:296-325, 361-362).

## 1. Virtual threads — state of the art on JDK 26

- **Pinning is essentially solved.** JEP 491 (JDK 24) re-implemented object
  monitors so a vthread blocking inside `synchronized` / `Object.wait`
  unmounts and frees its carrier ([JEP 491](https://openjdk.org/jeps/491),
  [Inside Java Newscast #80](https://inside.java/2024/11/21/newscast-80/),
  [mikemybytes JDK 24 revisit](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/)).
  What still pins on JDK 24+: **native frames only** — JNI / FFM downcalls on
  the stack, and blocking during class loading / inside a class initializer
  (both are native-frame cases). None of these occur on the Seon driver path
  (§3 audit); class-load pinning is a warmup-only transient.
- **Interrupt semantics are identical to platform threads.** `Thread.interrupt`
  on a vthread sets the flag, wakes parked blocking operations
  (`InterruptedException` from sleeps/queues/locks,
  `ClosedByInterruptException` from interruptible channels) — the sci kill
  lane keeps working unmodified ([JEP 444](https://openjdk.org/jeps/444)).
  The invocation watchdog's `.interrupt ^Thread worker`
  (invoke.clj:30-34) is thread-kind agnostic.
- **No time-sharing.** Vthreads are not preempted; a CPU-bound vthread holds
  its carrier until it blocks or exits ([JEP 444](https://openjdk.org/jeps/444),
  scheduling section). Default carrier pool = `availableProcessors`
  (tunable via `jdk.virtualThreadScheduler.parallelism` / `maxPoolSize`).
  There is still **no standard custom-scheduler or preemption API in
  JDK 26** (drafts exist; nothing shipped). This drives the hybrid answer
  (§5).
- **Memory:** vthread stacks are heap-allocated stack chunks that grow/shrink;
  measured footprints are ~few-hundred bytes to a few KB per parked thread —
  1 M parked vthreads under ~500 MB in Loom's tests
  ([memory model writeups](https://medium.com/@mugeshkrish007/from-native-stacks-to-heap-stack-the-memory-model-of-java-virtual-threads-9d9fcbb14b08),
  [JEP 444](https://openjdk.org/jeps/444)). 1k–10k drivers cost single-digit
  MB of stack — noise next to sci contexts and database values.
- **Clojure specifics:**
  - Clojure 1.12 replaced `synchronized` with `ReentrantLock` in
    LazySeq/Delay (CLJ-2804,
    [ask.clojure #13318](https://ask.clojure.org/index.php/13318/clojure-lang-lazyseq-synchronized-methods-virtual-thread),
    [1.12 release notes](https://clojure.org/news/2024/09/05/clojure-1-12-0)) —
    and on JDK 24+ even monitors don't pin, so Clojure's remaining
    `locking`/`synchronized` uses are moot on JDK 26.
  - **Dynamic vars work on vthreads** (they are ThreadLocals; vthreads
    support ThreadLocal fully) but cost per-thread: pushing bindings on
    50k vthreads measured ~5× slower than ScopedValue equivalents
    ([Anders Murphy](https://andersmurphy.com/2024/05/30/clojure-virtual-thread-dynamic-var-performance.html)).
    Bindings do NOT auto-convey onto a raw `Thread/ofVirtual` thread —
    conveyance is a Clojure `future`/`send`/`bound-fn` feature, not a JVM
    one. Rule: the driver captures what it needs as ordinary arguments
    (Seon's ambient-database-value discipline already points this way);
    `bound-fn*` where a dynamic frame genuinely must cross.
  - `future`/`agent` defaults remain platform pools in 1.12 — the driver
    spawner uses explicit `Thread/ofVirtual`, not `future`.
  - core.async 1.9.829-alpha2+ reimplemented go blocks ON vthreads and
    added `io-thread`
    ([official announcement](https://clojure.org/news/2025/10/01/async_virtual_threads)) —
    relevant only as evidence that the ecosystem converged on vthreads,
    not as a driver-path candidate (§4).
  - ScopedValues are final in JDK 25 (JEP 506
    [openjdk.org/jeps/506](https://openjdk.org/jeps/506));
    StructuredTaskScope is still PREVIEW in JDK 26 (JEP 525, sixth preview
    [openjdk.org/jeps/525](https://openjdk.org/jeps/525)) — do not build on
    preview APIs; the claim/lease design already provides the structured
    lifecycle (a stolen claim interrupts its driver), so
    StructuredTaskScope adds nothing Seon needs.
- **Observability:** `jcmd <pid> Thread.dump_to_file -format=json` includes
  vthreads; JFR events `jdk.VirtualThreadStart/End/Pinned/SubmitFailed`
  exist, and post-JEP-491 `jdk.VirtualThreadPinned` fires only for the
  remaining (native-frame) pins — recording it in production is cheap and
  is the standing regression alarm.

## 2. Pinning-risk audit — vendored libraries on the driver path

Verdict column is for **JDK 26** (post-JEP-491).

| Vendored lib | Risky constructs found (file:line) | On driver path? | Verdict |
|---|---|---|---|
| sci (fork, branch seon) | `locking` on Var meta (sci/lang.cljc:41-44,177-180,321-324), `alter-var-root` locking (impl/vars.cljc:324-329), load-lock `(locking load-lock …)` (impl/load.cljc:264-266) | Yes (eval steps) | **Clean.** Monitors don't pin on JDK 24+; all are micro-critical sections (no IO under lock). No JNI/native frames. `:interrupt-fn` check sites are plain closure calls (impl/fns.cljc:52,77,166). |
| datahike (fork) | core.async go-loops in the writer (writer.cljc:10); `ArrayBlockingQueue` + `locking source` in committed-report (committed_report.cljc:2-14,282); blocking-deref promise (tools.cljc:94-103); `blocking-take` in connector (connector.cljc:33,293) | **No** — all inside the writer PROCESS; drivers reach it over UDS | **Not applicable to drivers.** Within the writer process, `ArrayBlockingQueue`/promise derefs are j.u.c parks (vthread-clean anyway); go-loops run on core.async's own dispatch. |
| konserve (fork) | `async+sync` dual paths; sync arm = blocking `FileChannel` IO, async arm = go blocks + `AsynchronousFileChannel` (filestore.clj:3-19,147-150); optimistic-locking retries (impl/defaults.cljc:296-322) | No (inside writer) | **Not applicable.** If drivers ever do direct blob IO through konserve's sync arm, blocking FileChannel ops park cleanly. |
| persistent-sorted-set | `grep -rl synchronized src-java` → **zero hits**; pure data structure | Yes (query CPU inside database values) | **Clean.** CPU-only; no blocking, no locks. |
| superv.async | Entirely core.async (`superv/async.cljc:3-8`: go/go-loop/thread/alts) | **No** — transitive dep of datahike/konserve, exercised only inside the writer | **Not applicable.** Never import it into driver code. |
| Seon host today | `locking` interrupt-lock (invoke.clj:31, host/eval.clj:127,156); `ReentrantLock`+`Condition` db pool (db/host.clj:39-45); fixed platform pools | Yes | **Clean.** Monitors fine on JDK 26; ReentrantLock/Condition are the ideal vthread constructs. |
| UDS transport | Blocking `SocketChannel` streams (transport/uds.cljc:296-325,361-362) | Yes (every db call) | **Parks cleanly** — NIO channel blocking is vthread-aware. **Gotcha G1 below**: interruptible channels CLOSE on interrupt. |

**Gotcha G1 — interrupt during a UDS round-trip kills the channel.**
`SocketChannel` is an `InterruptibleChannel`: `Thread.interrupt` landing while
a driver blocks in a channel read/write closes the channel
(`ClosedByInterruptException`). So a deadline/steal interrupt that catches a
driver inside a db call costs that pooled member its connection. The pool
already disposes failed members (`close-member-session!`,
db/host.clj:118-124 + release/dispose paths), so this is correct-but-lossy:
name it, don't fight it — the claim design already treats interrupt as a
respawn-boundary event, and `recover-committed` (writer.clj:1433-1444) makes
ambiguous transact delivery safe to replay.

**Gotcha G2 — carrier-pool monopolization has no OS-level fix** (§5).
**Gotcha G3 — ThreadLocal/binding cost at 10k threads** (§1, Clojure bullet).
**Gotcha G4 — class-load pinning at warmup**: first-touch class initialization
can pin briefly; irrelevant at steady state; keep JFR `VirtualThreadPinned`
recording on to confirm.

## 3. Alternatives, honestly weighed

| Alternative | One-line verdict |
|---|---|
| Platform-thread pool (status quo, sized ~10) | Fine at dozens, dead at 1k: 1k platform threads ≈ 1–2 GB of native stacks + scheduler load; sizing a pool for 10–60 s LLM waits means either thousands of threads or queued (= serialized) agents. The current 10-thread eval-pool would cap concurrent drivers at 10. |
| Platform pool at "hundreds" | Memory math: 500 threads × ~1 MB reserved stack ≈ 500 MB native (outside heap); workable but every thread exists only to sleep on IO — vthreads give the same plain-sync code without the cost cliff or the pool-sizing knob. No reason to prefer it on JDK 26. |
| core.async (JVM side) | Rejected pod-side already; go blocks impose async ceremony (`<!`/`>!` shapes) on the portable core — violates the binding ruling. Its own 2025 direction concedes the point: go blocks are now vthreads underneath ([announcement](https://clojure.org/news/2025/10/01/async_virtual_threads)). Using vthreads directly deletes the middleman. No JVM-side case argues for it outside the writer where Datahike already carries it. |
| manifold | Deferred/chain ceremony in every signature; a second stream/executor vocabulary; unmaintained-adjacent. Disqualified by the plain-sync ruling. |
| missionary | A full FRP/continuation calculus — maximal async ceremony, steepest possible mismatch with "plain synchronous Clojure". Disqualified. |
| promesa (JVM executors + CompletableFuture) | Its vthread helpers are a thin sugar over the same JDK APIs; its promise combinators are ceremony. Use the JDK directly; no dependency earned. |
| `Executors/newVirtualThreadPerTaskExecutor` + plain fns | This IS the recommendation's null-alternative form — same foundation, executor-shaped entry. Use `Thread/ofVirtual` per claim for a nameable, interruptible thread handle; the executor form suits fan-out helpers. |

## 4. The hybrid question — CPU-bound sci evals on vthreads?

**Answer: hybrid. Driver spine + all IO on virtual threads; sci eval steps
stay on a bounded platform pool (the existing `::eval-pool`). Interrupt +
deadline do NOT make all-vthread safe.**

Grounding:

- A hostile/spinning eval on a vthread occupies a carrier until it blocks or
  exits — vthreads are not time-shared ([JEP 444](https://openjdk.org/jeps/444)).
  sci's interrupt bounds WALL time (watchdog fires at deadline,
  invoke.clj:100-103; `:interrupt-fn` polls at fn entry/loop recur,
  fns.cljc:52,77,166 + the interrupt-aware core overrides,
  sci/interrupt.cljc:205-315), but the deadline is seconds-to-minutes. With
  default carrier parallelism = availableProcessors, **N-cores concurrently
  hostile (or merely hot) evals stall every driver in the process** — parked
  drivers whose IO completed cannot get a carrier to run their next step.
  That is a whole-process lockup lasting up to the eval deadline; the
  no-lockup contract (llm-http-io design §3) forbids it.
- The bounded platform eval-pool converts that worst case into "eval-pool
  saturated, drivers queue for eval capacity while IO-phase drivers keep
  flowing" — exactly the bulkhead the host already has
  (`newFixedThreadPool 10`, host.clj:276). The driver's code stays plain
  sync: it submits the eval and blocks on `Future.get`, which parks the
  vthread cleanly. This is also zero migration for the interrupt lane: the
  watchdog keeps interrupting a PLATFORM worker, the exact code that is
  proven today (invoke.clj:81-156, including the FutureTask start-race
  handling at :84 and pool-thread interrupt-flag hygiene at :154-156).
- Same argument, smaller stakes, for hiccup context rendering: renders are
  bounded CPU (caps exist) — they can run on the driver vthread initially;
  if render CPU ever measurably delays carriers, move renders onto the same
  eval bulkhead (they are sci invocations in the P4 world anyway,
  llm-http-io design §2a).
- Raising `jdk.virtualThreadScheduler.parallelism` is NOT a substitute: it
  trades lockup for oversubscription and still gives no fairness guarantee;
  there is no shipped custom-scheduler/preemption API on JDK 26 (§1).

Sizing rule: eval-pool = min(availableProcessors − headroom, measured eval
demand). It bounds CPU oversubscription; it does not bound driver count.

## 5. The guarded eval door — fuel counter alongside deadline→interrupt

Owner scope addition. Evaluate a per-invocation STEP BUDGET ("fuel") checked
at the same interpreter safepoints the interrupt uses, whose exhaustion
terminates the eval with an error value.

### Where the check sites are (fork, file:line)

- **Fn entry / loop recurrence:** the generated arity fns capture
  `interrupt-fn# (:interrupt-fn ctx)` at closure build and run
  `(when-not (nil? interrupt-fn#) (interrupt-fn#))` at the top of every
  invocation loop — `gen-fn` for arities 0–20
  (reference-code/sci/src/sci/impl/fns.cljc:40,52 and 64,77) and the
  variadic 20+ case (fns.cljc:152,166). The header comment (fns.cljc:24-31)
  documents the hot-path discipline: `nil?` compiles to a single reference
  compare; `some?` measured ~2× slower for the bare check.
- **Interrupt-aware core overrides:** `sci.interrupt/clojure-core` re-binds
  range/repeat/cycle/iterate/doall/dorun/count/into/reduce (+ JVM regex and
  string fns) to fire the ctx's `:interrupt-fn` **per element**
  (sci/interrupt.cljc:205-315, map at :316+) — this is what makes
  `(reduce + (range))` interruptible even though it never re-enters an
  interpreted fn.
- **Uncatchable propagation:** `interrupt!` throws an ex-info carrying the
  private marker (sci/interrupt.cljc:32-42, utils.cljc:42-51); sci's
  `eval-try` refuses to hand it to user catch clauses and the fork's
  guarded finally prevents finally-masking (impl/evaluator.cljc:77-81,
  129-172). The host currently installs
  `:interrupt-fn = (fn [] (when (.isInterrupted (Thread/currentThread)) (interrupt! …)))`
  (src/seon/host/context.clj:876-883).

### The decisive finding: fuel needs ZERO interpreter changes

`:interrupt-fn` is an arbitrary closure called at every safepoint. Fuel is
therefore an INSTALLATION property, not a fork patch: build the per-invocation
guard as a closure over a one-element long array (or unsynchronized mutable
box — single-threaded per invocation by construction):

```clojure
(let [fuel (long-array 1)]                       ; set per invocation
  {:interrupt-fn
   (fn []
     (let [f (unchecked-dec (aget fuel 0))]
       (aset fuel 0 f)
       (when (or (neg? f)
                 #?(:clj (.isInterrupted (Thread/currentThread))))
         (interrupt/interrupt! (if (neg? f) "step budget exhausted"
                                            "eval deadline exceeded")
                               {:seon.error/kind (if (neg? f) :budget :timeout)}))))})

```

One wrinkle: today the base context is built ONCE with a process-wide
`:interrupt-fn` (context.clj:876-883) and per-invocation state rides thread
identity. Fuel is per-invocation state, so the guard closure must read a
per-invocation cell — either (a) the fuel array lives in the session and is
RESET per invocation (sessions are single-eval-at-a-time, host/session
invariant — cheapest), or (b) the invocation assoc's a fresh `:interrupt-fn`
onto the forked ctx per call (sci contexts are maps; assoc is cheap but
touches the ctx-per-invocation discipline). Prefer (a).

### Cost per check site (honest estimate)

Today's site cost: nil-check (folded to ==) + closure invoke +
`Thread.currentThread().isInterrupted()` (intrinsified, ~1–2 ns). Adding
fuel: one `aget`/`unchecked-dec`/`aset` on a hot long-array + one branch ≈
**1–3 ns per site**, and it can go FIRST so `isInterrupted` runs only every
Nth step if we ever care — i.e., the guarded door can be made *cheaper* than
the current pure-interrupt check, not dearer. Against sci's interpretation
cost per fn call (~µs scale: measured 0.17 ms for a 1-def eval, crashproof
§2), the counter is ≪1% overhead. Verify with the same microbenchmark
discipline the fork used for `nil?` vs `some?` (fns.cljc:24-31).

### Prior art

Upstream sci HAD exactly this ambition and retired it: `:realize-max` +
`:preset :termination-safe`, REMOVED with the note "in the light of
[#348](https://github.com/babashka/sci/issues/348) it would be misleading to
claim that sci can guarantee termination within reasonable time"
(reference-code/sci/CHANGELOG.md:635-637). The escape hatch behind #348 was
host calls that iterate natively without re-entering the interpreter — which
is precisely what the fork's interrupt-aware core overrides now close
(per-element `ifn` firing, sci/interrupt.cljc). So the fork is already past
the reason upstream gave up; fuel at the same sites inherits that coverage.
No other step/fuel prior art in upstream sci or babashka source/issues found.

### Composition and the thread-free benefit

- **With vthread/platform interrupts:** orthogonal and additive — the same
  guard closure checks fuel (deterministic, thread-free) OR the interrupt
  flag (wall-clock lane); both raise the same uncatchable marker; the
  watchdog and claim-steal lanes are unchanged.
- **Thread-free preemption where no threads exist:** on the Bun pod there is
  no `Thread.interrupt`; today in-process renders/plan fns are unbounded —
  the llm-http-io design's one named gap row. sci CLJS compiles the
  interrupt check into the same sites (the CLJS analyzer even elides it
  when `:interrupt-fn` is nil, analyzer.cljc:1026-1028, so unguarded
  contexts pay nothing). Fuel exhaustion is DETERMINISTIC: same code + same
  budget ⇒ same termination point, replayable in tests, no timing flake.
- **What fuel does NOT bound:** wall time of a SINGLE host call (a blocking
  interop call consumes ~1 step). Steps are not time. The two lanes cover
  each other: fuel bounds interpreted work everywhere; deadline+interrupt
  bounds wall time where threads exist; on the pod, host calls are already
  bounded by their own leaf timeouts (LLM adapter timeout, fetch caps) —
  the residue (a pathological synchronous host fn) is the pod's standing
  WP-S2 supervisor physics, unchanged.

### The one portable guarded-eval entry (.cljc sketch)

`seon.host.guard/evaluate!` (name illustrative — strengthen the existing
invocation path in place, not a new family): the single door every sci
invocation passes through — agent eval, authored render fn, plan fn:

```clojure
{::guard/fuel        1500000        ; steps; per invocation-class config fact
 ::guard/deadline-ms 120000         ; JVM: watchdog→Thread.interrupt; pod: absent
 ::guard/output-cap  …}             ; existing render/print caps, same envelope

```

- entry resets the session fuel cell, arms the deadline where threads exist
  (reader conditional at the arming only), runs the sci call under
  `ctx-store/with-ctx`, and maps the uncatchable marker to ONE uniform
  steering error value: `:seon.error/kind :timeout | :budget`, with
  `::guard/steps-used` (initial − remaining) as evidence — the agent-facing
  message says what budget was exceeded and what to do (split the work /
  reduce the input), per the errors-drive-correct-usage standing rule.
- Output caps join the same door so there is exactly one place where "an
  eval was stopped by policy" is decided and reported.

### Calibration honesty

Steps are not milliseconds. Budgets must be MEASURED, per tier, per
invocation class: run the representative corpus (existing eval fixtures +
authored renders) with an instrumented guard that only counts, record
steps/ms distributions (JVM interpreted throughput will be O(10⁵–10⁶)
guarded steps/s; Bun different), then set fuel ≈ p99-steps ×
safety-factor — and keep the deadline as the time authority on the JVM.
Budgets are config facts (the `:seon.config` singleton pattern), not
constants. Re-calibrate when the interpreter or override set changes; the
counting-only guard mode IS the measurement tool.

**Verdict: adopt.** Zero fork surface beyond what exists, ~ns-scale site
cost, deterministic and thread-free, closes the pod-side unbounded-render
gap, and gives the JVM a second independent bound. It complements — never
replaces — deadline→interrupt (G2 still stands: until EITHER lane fires, a
hot eval occupies its thread; the platform eval-pool bulkhead of §4 remains
the reason a hostile eval cannot stall drivers).

## 6. Limits — what actually bounds a driver

- **Memory per driver is NOT bounded by the JVM.** Say it plainly: no
  per-thread heap quota exists; a driver's transient allocations (context
  render, query results, sci values) compete in one heap. Compensating
  controls: (a) the heap ceiling itself — raise the host's `-Xmx` from the
  writer-sized 512 MB (deps.edn `:writer` jvm-opts) with an explicit
  1k-driver budget; (b) claim restart — the q18 OOME drill / supervisor
  containment thesis: the process dies, claims lease-expire, stealers
  resume from durable phase cursors; (c) monitoring — JFR allocation
  profiling (`jdk.ObjectAllocationSample` is cheap and always-on-capable)
  attributes allocation to threads, and vthread-aware thread dumps name the
  claim (rule R2 below); (d) sci-side bounds — retained live-values caps
  (invoke.clj:251) and the guarded-door output caps (§5).
- **The writer is a single serialized point — and that is the design.**
  `LocalWriter` runs ONE processing thread draining a bounded queue and one
  commit thread that batches queued reports per persist
  (reference-code/datahike/src/datahike/writer.cljc:42-76, 205-240). 1k
  drivers blocking on transacts do not overload it; they QUEUE, and commit
  batching amortizes fsyncs as concurrency rises. The contention point on
  the driver side is the db-host leaf pool: `availableProcessors − 1`
  members with `pool-wait-timeout-ms 1000` (db/host.clj:16-17) — at 1k
  concurrent callers a 1 s wait produces spurious pool errors. Adoption
  change: waiting drivers are FREE on vthreads, so raise the wait timeout
  toward the call deadline (or make it the deadline minus margin) and keep
  the member count small; do not widen the pool to 1k — the writer
  serializes anyway, and per-driver sessions would multiply UDS sockets for
  nothing. The `newFixedThreadPool` call-executor hop (db/host.clj:46)
  becomes unnecessary ceremony once callers are vthreads — calls can run on
  the caller's own thread; fold that in when touching the leaf, not before.
- **LLM concurrency is bounded by provider limits, not threads:** 1k
  simultaneous 60 s HTTP waits are ~free JVM-side (parked vthreads +
  HttpClient NIO); rate limits arrive as 429/`retry-after-ms` database
  facts (llm-http-io §1f) and the retry budget bounds attempts. No
  client-side semaphore until a measured provider limit demands one — then
  it is a `java.util.concurrent.Semaphore` acquired in plain sync code
  (parks cleanly), configured as a database fact.

## 7. Adoption recipe

**Executor choice.** One process-lifetime `Thread/ofVirtual` builder with a
name template (`.name "seon-driver-" 0`) — a per-claim
`(.start builder driver-fn)` returns the `Thread` handle the claimant
retains. `newVirtualThreadPerTaskExecutor` is equivalent; the explicit
builder keeps the interruptible handle first-class, which the kill lane
wants. The sci eval-pool stays `newFixedThreadPool` (platform, §4).

**Claim → thread lifecycle.**

1. Claimant wins the epoch CAS (loop design §2) and starts one vthread
   running the plain-sync step loop (render → LLM → eval-dispatch → write),
   heartbeating between steps.
2. Blocking anywhere — `HttpClient.send` (interruptible,
   llm-http-io §1a), UDS round-trip, `Future.get` on the eval-pool —
   parks the vthread. No code acknowledges this.
3. Kill-anytime = `Thread.interrupt` on the retained handle (steal,
   shutdown, run-deadline) + the durable fence: a displaced driver's
   late writes lose the epoch CAS regardless of when the interrupt
   lands. Interrupt during a UDS call costs a pooled connection (G1) —
   accepted, already-handled member disposal.
4. Deadlines compose as: run deadline (database fact) → step deadline
   (derived, loop design §7) → armed per eval on the WATCHDOG against the
   eval-pool worker (existing mechanism, invoke.clj:100-103) and per
   HTTP call via request `.timeout` + the same watchdog against the
   driver vthread; fuel (§5) bounds interpreted steps independently.
   All three converge on the same error-value vocabulary.

**Rules for keeping the portable core vthread-clean (R1–R5):**

- R1 — No async ceremony, no thread awareness: core fns take values,
  block, return values; only leaves may name Thread/executor types.
- R2 — Name every spawned thread with its claim
  (`seon-driver-<agent>-<run>`): thread dumps and JFR become claim
  forensics for free.
- R3 — No dynamic-var frames across the driver spine: pass the database
  value and config as arguments (existing discipline); `bound-fn*` only at
  a leaf that must bridge into binding-dependent library code.
- R4 — CPU bursts go through the one guarded eval door (§5) onto the
  bounded platform pool; never spin on the driver vthread (no busy-wait
  polling — waiting is parking: `Condition`, queue, or sleep).
- R5 — Never take a lock around IO in core code (locks are
  vthread-clean but serialize; the database CAS fence is the
  coordination primitive, not mutexes).

**Smallest probe (runnable plan, not run here):** one `:writer:host`-basis
script proving on THIS machine's JDK 26:

1. Park scale: start 1,000 named vthreads each blocking on a
   `CountDownLatch` for 60 s; assert RSS/heap delta < 50 MB and
   `jcmd Thread.dump_to_file -format=json` lists all 1,000.
2. LLM-shaped waits: a local Jetty-free stub server (plain
   `com.sun.net.httpserver`) delaying responses 10–30 s; 1,000 vthreads
   each doing a synchronous `HttpClient.send`; assert all complete, carrier
   count stays ~nCPU, and JFR shows zero `jdk.VirtualThreadPinned` events
   over 20 ms.
3. Bulkhead: while (2) is in flight, saturate the 10-thread eval-pool with
   spinning tasks; assert HTTP completions continue (drivers unaffected),
   then interrupt the spinners via the watchdog path and assert pool
   recovery. Control run: the same spinners as raw vthreads with
   parallelism=nCPU, demonstrating the starvation the bulkhead prevents.
4. Interrupt semantics: interrupt a vthread parked in `HttpClient.send` and
   one blocked in a UDS call; assert `InterruptedException` /
   `ClosedByInterruptException` map to the existing error values and the
   db pool disposes the member (G1 evidence).
5. Fuel calibration dry run (§5): counting-only guard over the eval fixture
   corpus on both tiers; record steps/ms distributions into this PRD.

## Sources

- [JEP 491 — Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [Inside Java Newscast #80 — Java 24 stops pinning (almost)](https://inside.java/2024/11/21/newscast-80/)
- [Mike my bytes — Java 24 thread pinning revisited](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/)
- [clojure.org — core.async and Virtual Threads (2025-10-01)](https://clojure.org/news/2025/10/01/async_virtual_threads)
- [ask.clojure — LazySeq synchronized methods pin virtual thread (CLJ-2804)](https://ask.clojure.org/index.php/13318/clojure-lang-lazyseq-synchronized-methods-virtual-thread)
- [Clojure 1.12.0 release notes](https://clojure.org/news/2024/09/05/clojure-1-12-0)
- [Anders Murphy — Clojure: virtual thread and dynamic var performance](https://andersmurphy.com/2024/05/30/clojure-virtual-thread-dynamic-var-performance.html)
- [JEP 506 — Scoped Values (final, JDK 25)](https://openjdk.org/jeps/506)
- [JEP 525 — Structured Concurrency (Sixth Preview, JDK 26)](https://openjdk.org/jeps/525)
- [Virtual thread memory model (stack chunks)](https://medium.com/@mugeshkrish007/from-native-stacks-to-heap-stack-the-memory-model-of-java-virtual-threads-9d9fcbb14b08)
- [sci issue #348 — termination guarantees (via CHANGELOG.md:635-637)](https://github.com/babashka/sci/issues/348)
