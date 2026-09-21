---
type: plan
status: implementation specification; proof gates explicit
created: 2026-09-21
lane: C1
depends-on: [A1, B1, B3, B4]
tags: [agent-platform, profiling, instrumentation, program-graph, tasks]
---

# Lane C1 — profiling on the armed wrapper

Implementation ownership: `src/seon/profile.clj`,
`resources/seon/schemas/seon.profile.edn`, the observation seams in
`src/seon/instrument.clj`, settlement consumers in `src/seon/turn.clj`,
`src/my/program.clj`, and `test/seon/profile_test.clj`. Coordinate B4's execution
observation and B3's task writer; do not create either again here. A1 owns the
wrapper refactor, B1 the definition digest, and B2 context installation.
This specification is design plus dated evidence, not a live implementation proof.

## 0. For the owner: repeated measurements become ordinary observations

The running system already wraps contracted calls, yet performance evidence
lives mostly in one-off benchmarks and research notes. A second profiler or
per-call database write would repeat work and put measurement on the serial
writer's critical path. The existing wrapper can capture which definition ran
and measure its inclusive elapsed time, then hand immutable observations to the
existing execution-completion and settlement writers.

Each installed callable captures its symbol and B1's
`:seon.program/definition-digest`. Existing execution/custody identifies whose
work ran. Two `System/nanoTime` reads bracket the call; direct primitive JDK
operations accumulate count, total and, if retained, lifetime maximum. There is
no database read, hashing, map construction or recursively armed recorder on
this path. Completed calls are recorded on return or throw without changing the
result. B4 uses the same observation for executed reach.

Keep cumulative observations; never reset actively updated counters. A refused
write then leaves the observations available for the next completion event.
Static callers and callees give useful navigation, but their aggregate timings
cannot be subtracted to obtain self time. The first feature exposes inclusive
cost and honest freshness; additional automatic profiling criteria need an
owner decision supported by measurements.

## 1. Goals and measured limits

Historical observations on 2026-09-21 used Java 26.0.1 and the then-running
`default`. They are exploratory microprobes, not a fresh baseline or delivered
wrapper measurements. §4 carries one complete reproducible form and result.

| Observation | Historical evidence | Implementation acceptance |
|---|---|---|
| Inline clocks and three JDK updates | Original report 40.09 ns overhead; complete later form gives 21.23 / 16.19 / 34.84 ns | Measure actual A1 wrapper plus custody and B4 reach; proposed ≤60 ns absolute added cost and ≤5% on representative operations, both reported separately; inability to meet either is a design finding |
| Boxed map/closure sample | Original +623.96 ns; later +658.41 / 684.78 / 777.71 ns | No recursively armed recording call; verify primitive dispatch and allocation on the delivered path |
| Custody overhead | Original +12.43 ns; later order/JIT variation includes negative differences | No isolated attribution-overhead claim from these samples; benchmark final combined path under contention |
| 300-row speculative write | Original 12.95 / 6.03 ms; exact original form unavailable | Measure real writer queue residence and completion latency with and without evidence; speculative `with` is no contention proof |
| Graph navigation | Original 300-subject callee query 7.87 ms, caller query 12.26 ms, full 40,812-edge query 71.60 ms; exact forms unavailable | One-hop read only; record returned and examined work, never whole graph per settlement |
| Evidence durability | No implementation | Repeated/older submissions cannot replace newer evidence; refusal loses no in-memory cumulative observation; unavailable evidence is visible |

Measure call allocations, concurrent execution, wrapper arming and actual writer
latency separately. Explain suspicious costs by visited work; do not turn noisy
nanosecond measurements into `deftest` assertions or approve operations over the
owner's time bound implicitly.

## 2. Data flow and contracts

### Definition and execution ownership

| Value | Construction and carrier | Update event and cost |
|---|---|---|
| Definition identity | B1's common file/agent row constructor supplies symbol plus `:seon.program/definition-digest` to A1/B2 callable installation | Changed declarations only; no full-program digest query on every arm |
| Definition observation | Owned by the existing execution/custody lifetime; the wrapper holds a typed cell/reference for the callable it actually invokes | First observation within that owner; a same-definition re-arm may reuse it; released owners release their cells |
| Per-call sample | Primitive elapsed duration in the invocation seam; direct `LongAdder` count/total and optional `LongAccumulator` max | Every completed call, including throws; inclusive wall time includes waits |
| B4 member observation | Distinct observed `(symbol, digest)` values and timing from the same wrapper, scoped to the admitted test execution | Propagated through existing requests and owned child work; completion only after child work and cleanup terminate |
| Durable observation | Immutable cumulative count/total/max with process/branch origin, observation ordering and freshness | Existing settlement/completion event; writer decides against current facts, never reads a live counter |
| Read result | Version-qualified inclusive totals, origin, freshness, uncertainty and one-hop static graph | One supplied database value; historic graph matching that digest or typed unavailable |

B1's digest includes exact source, qualified identity, normalized resolver
context and effective acquisition/test metadata. It excludes authorship,
position and transitive reach. C1 consumes it unchanged. Installation must prove
that digest describes the captured callable, not the latest row. An old call
finishing after redefinition keeps its old identity; rearming never globally
retires a definition still executable in another SCI fork. Missing identity is
unknown. A file digest is not a temporary substitute.

Attribution comes from per-context installation, not from custody lookup
(README §3, A1-2, B2 §2a): an interpreted row's wrapper is installed in one
SCI context, so its cell lives in that wrapper's closure and belongs to that
context by construction — no per-connection map, no custody lookup on the
profiling path (the 12 ns figure and its non-reproducibility become moot).
**The JVM Var's own wrapper is the HOST cell, attributed to no cluster**: a
copied compiled function still calls its JVM callees (Codex's `copy-var*`
probe), so candidate work that reaches JVM paths lands in host cells, and the
read reports host work separately rather than charging it to the development
cluster. B4's per-member reach observation needs an execution scope, which
already exists: `seon.db/call-with-custody` binds `*conn*` for a test body
(`db.clj:365-387`); the wrapper inserts `(symbol, digest)` into the bound
scope's set when one is bound — the ruled custody elision, not a registry.
Profiling totals read no scope. A context generation that is released
releases its cells with it. Shared JVM roots keep the callable/contract
evidence A1/B2 guarantee. No process-global connection registry exists.

B4's observation must include the test identity/version, applicable fixture work
and owned asynchronous work while excluding recorder execution. A thread-local
transient does not cross Flow/executor hops safely. Branch-wide totals cannot
identify one test among background work. Missing arming, mismatched callable
versions or unobserved child work makes the observation incomplete. Observed
reach is diagnostic; static reach remains B4/D1's conservative selection floor.

### Cumulative persistence

`LongAdder.sum` is not an atomic snapshot; three independent counters do not form
an atomic tuple. Live reads are approximate. Exact per-test observations require
quiescence of that execution including its children, not one arbitrary turn
closing. Never use `sumThenReset`/`getThenReset` on active cells: independent
resets can split a sample and discard its duration when count appears zero.

The observation owner hands an immutable cumulative value to the existing
writer. Within one origin/lifetime, replacement is monotone and idempotent:
identical observations are no-ops, older submissions cannot overwrite newer
ones, and incompatible origins refuse. Choose the final ordering and tuple
contract after the concurrent-capture probe; independent field maxima are not
an invented exact tuple. Transaction functions perform only pure decisions and
return tx-data: Datahike may retry them. A write bound can leave outcome unknown;
retrying the identical observation must remain safe.

Forked branches inherit facts. Use existing process/branch identities and basis
to distinguish inherited evidence from new work and a restarted process from
its predecessor. Never subtract across these lifetimes. Differences between two
compatible cumulative observations give interval count and total; maximum, if
stored, remains lifetime maximum. There is no exact interval maximum or implicit
observation event when the stored value did not change. History stores fact
changes, not every submission.

Turn settlement and B4 completion append observations to their existing
transactions. Inventory every `close-tx` caller, including evaluation/refusal
settlement, rather than assuming three sites. Capture outside `close-call`.
An enclosing call may finish after settlement; its observation belongs to the
next owning completion. For open turns/background work, reuse the existing root
schedule or completion event and report its configured bound; close-only gives
no universal persistence bound. No timer thread or periodic proc is added.
Crashes lose uncommitted observations and an unfinished call has no completed
duration. Optional profiling failure cannot invalidate a turn, but must surface
as unavailable evidence rather than a false zero.

### Stored shape, reads and findings

Only `:seon.profile/id` is a profile identity. Its derivation uses `seon.id` and
the definition plus observation origin/lifetime. Declare the observed symbol
as an indexed value; aliasing `:seon.fn/sym` would inherit uniqueness and merge
versions incorrectly (`schema/datahike.clj:95-102,176-181`).

```clojure
:sym [:qualified-symbol {:seon.db/index true
                         :description "Observed symbol value; survives function retraction."}]
:digest :seon.program/definition-digest
```

Declare the final aggregate/origin/freshness contract after the concurrency
probe; no guessed exact-window schema is authorized. Two digests for the same
symbol must coexist through the actual bridge and writer. Evidence survives
function deletion as values; reads must not require a current function row.
New additive attributes alone do not require reset. Any incompatible stored
shape is RESET NEEDED with its exact affected writers/readers, batched by the
orchestrator with B1/B4 changes.

`my.program/profile` joins the existing `read-result` union, including typed
unavailable/error results. Read one supplied database value. History queries
bind the profile entity and assertion polarity, carry explicit work bounds,
and report examined datoms; filtering by `txInstant` alone does not prove
work proportional to the requested interval. Prefer two cumulative observations
for arithmetic, measuring their retrieval separately. No rolling-average cache.

Automatic findings, decided (2026-09-21, second-perspective review; the owner's
intent "surface automatic issue creation for agents to profile and fix" is met
by the criterion that is already a fact): a declared operation bound that fired
— an evaluation over its `time-limit`, a test over its declared bound, an effect
over its deadline, a publication phase over its bound — is definite evidence and
opens a task through B3's writer with the profile observation attached as
occurrence evidence. Ranking by inclusive total and mean is a READ
(`my.program/profile`) that root and the namespace agents consult to open tasks
by judgment. A large total across many calls or an IO wait does not prove a slow
function, so there is no 2000 ms dial, no self-time subtraction, no top-N task
threshold and no k-window rule. A further automatic criterion needs measured
evidence and an owner decision.

When a criterion is authorized, derive findings once outside the serial writer
where possible. B3 `trigger-call` rechecks evidence/current digest and resolves
its existing detector+subject identity. Digest/timing are occurrence evidence,
not new task identities; existing error identity is preserved. One call per
finding in one transaction is acceptable pending F=0/1/many measurements. Never
call private `issue/subject-row` as a temporary task writer.

## 3. Dependency reading and implementation seams

| Source | Guarantee to read before editing |
|---|---|
| JDK 26 `java.base/java/util/concurrent/atomic/LongAdder.java:85-93,112-167` in `lib/src.zip` | Primitive updates, non-atomic sum and reset semantics; separate resets do not compose |
| JDK `LongAccumulator.java:89-121,163-184`; `Striped64.java:60-75,128,150-161,193-196` | Contended cells and carrier-thread probe; a typed field holding an atomic object differs from an unsafe shared primitive field |
| JDK `System.java:343-385` | `nanoTime` measures elapsed time, not CPU or UTC |
| `src/seon/instrument.clj:462-522,844-908`; `src/seon/sci/eval.clj:694-711` | Existing wrappers/install; digest must be supplied here, it is not supplied today; A1's wrapper acquisition slice precedes C1 |
| `src/seon/db.clj:365-387,477-481` | Custody and mismatch check; the latter covers writes, not every read-semantic change |
| `src/seon/turn.clj:432-464,3563,3686,3726,4483,4834-4842,4888` | Close construction and its settlement callers; recovery/system settlement also need explicit accounting |
| `reference-code/datahike/src/datahike/db/transaction.cljc:585-615,1153-1154,1290-1316` | Duplicate suppression and retry; no counter mutation inside transaction functions |
| `reference-code/datahike/src/datahike/db/search.cljc:138-156,186-195` | Available seek order; time filtering is not a function/time index |
| `reference-code/datahike/src/datahike/versioning.cljc:224-273`; `connector.cljc:38-86` | Branch inheritance and connection lifetime |
| `src/my/program.clj:15-21,51-79`; `src/seon/issue.clj:554-627` | Read-result contract and current private task helper; B3 owns its replacement |
| `src/seon/test.clj:143-153`; `src/seon/test/runner.clj:690-692,2362-2370` | Existing execution/observation propagation seams; B4 owns result completion |

## 4. REPL evidence and implementation protocol

Use MCP `eval_clj`, explicit root/cluster, JVM mode, one evaluation at a time.
The following complete 2026-09-21 form changed only local counters and immutable
`datahike.api/with` values. The dependency calls here are probes, not a production
bypass of `seon.db`. No new measurement was run for this specification.

```clojure
(let [original (malli.instrument/-f->original seon.id/valid?)
      conn (seon.operator/connection "default") n 100000
      count-cell (java.util.concurrent.atomic.LongAdder.)
      total-cell (java.util.concurrent.atomic.LongAdder.)
      max-cell (java.util.concurrent.atomic.LongAccumulator.
                (reify java.util.function.LongBinaryOperator
                  (applyAsLong [_ a b] (max a b))) Long/MIN_VALUE)
      cells {:count count-cell :total total-cell :max max-cell}
      by-conn (doto (java.util.concurrent.ConcurrentHashMap.) (.put conn cells))
      record-map (fn [c t0]
                   (let [d (- (System/nanoTime) t0)]
                     (.increment ^java.util.concurrent.atomic.LongAdder (:count c))
                     (.add ^java.util.concurrent.atomic.LongAdder (:total c) d)
                     (.accumulate ^java.util.concurrent.atomic.LongAccumulator (:max c) d)))
      base #(original 12 "0123456789ab")
      inline #(let [t0 (System/nanoTime) v (original 12 "0123456789ab")
                    d (- (System/nanoTime) t0)]
                (.increment count-cell) (.add total-cell d) (.accumulate max-cell d) v)
      boxed #(let [t0 (System/nanoTime) v (original 12 "0123456789ab")]
               (record-map cells t0) v)
      custody #(let [c (if seon.db/*conn* (.get by-conn seon.db/*conn*) cells)
                     t0 (System/nanoTime) v (original 12 "0123456789ab")]
                 (record-map c t0) v)
      bench (fn [f] (dotimes [_ 30000] (f))
              (let [t (System/nanoTime)] (dotimes [_ n] (f))
                (/ (- (System/nanoTime) t) (double n))))
      rounds (mapv (fn [_]
                     {:base (bench base) :inline (bench inline) :boxed (bench boxed)
                      :custody (binding [seon.db/*conn* conn] (bench custody))
                      :armed (bench #(seon.id/valid? 12 "0123456789ab"))}) (range 3))
      database (seon.db/db conn)
      eid (:db/id (datahike.api/pull database [:db/id] [:seon.fn/sym 'seon.id/valid?]))
      tx [[:db/add eid :seon.fn/doc-order 123456789]]
      r1 (datahike.api/with database tx)
      r2 (datahike.api/with (:db-after r1) tx)
      changes (fn [r] (count (filter #(= :seon.fn/doc-order (:a %)) (:tx-data r))))]
  {:java (System/getProperty "java.version") :java-home (System/getProperty "java.home")
   :rounds rounds :identical-write-datoms [(changes r1) (changes r2)]
   :history (datahike.api/q '[:find ?v ?tx ?added :in $ ?e
                             :where [?e :seon.fn/doc-order ?v ?tx ?added]]
                           (datahike.api/history (:db-after r2)) eid)})
```

Returned value (MCP renders query relations as arrays):

```clojure
{:java "26.0.1"
 :java-home "/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home"
 :rounds [{:armed 1281.605 :base 182.92584 :boxed 841.33375 :custody 871.48083 :inline 204.15209}
          {:armed 1233.10834 :base 199.95166 :boxed 884.73334 :custody 865.61375 :inline 216.14375}
          {:armed 1187.7175 :base 186.48125 :boxed 964.18708 :custody 883.48833 :inline 221.31833}]
 :identical-write-datoms [1 0]
 :history [[123456789 536870950 true]]}
```

Envelope: one `ret`, namespace `user`, 1337 ms, cluster state `alive`, `windowed? false`; no error/out event. Times are ns/call. This probes the proposed low-level shapes, not a completed `deftype` wrapper, multi-thread contention or stable causal overhead. The historical raw forms for 40.09/623.96/12.43 ns were not supplied by the original notes, so exact reproduction is unavailable. JDK sources verify implementation semantics, not those timings. The history example proves duplicate suppression; it does not measure rolling-query performance.

For implementation, first repeat this baseline, then benchmark the actual
installed wrapper with profiling disabled/enabled on the same acquired program.
Record exact forms, warmup order, allocation, contention, absolute and relative
cost. Temporary counters are mutations: describe them honestly to the MCP tool.
Do not present a future API with a placeholder digest as an executable proof.

On a canonical disposable branch, exercise repeated and reversed-order immutable
submissions, write refusal, outcome-unknown retry, concurrent closes, a same-symbol
replacement during an active call, inherited rows and restarted ownership. Record
the exact request maps and returned facts after the APIs land. Run one owned
asynchronous test beside unrelated work and verify its observation is exact or
explicitly incomplete. Release every branch/connection through its existing owner.

JFR is an optional statistical cross-check of inclusive wall-clock rankings,
not exact self time or coverage of every call. Verify the installed JDK command
syntax first; keep recordings under `tmp/`, preserve measured findings in the
landing note. Effect waits can rank differently from execution samples without
either measurement being wrong.

## 5. Ordered implementation slices

| Slice | Coherent change and proof |
|---|---|
| 1 | Agree A1/B1/B2 callable/digest installation and B4 observation contracts. Prove old/new branch definitions and async ownership before inserting the hook |
| 2 | Add owner-scoped typed cumulative cells and primitive wrapper operations, schema and contracts together. Same-definition reuse, throws and released ownership regressions land here |
| 3 | Add immutable snapshot admission and existing completion consumers together. Prove refused/duplicate/older writes, origins and approximation before persisting the shape |
| 4 | Add `my.program/profile`, its result/error union and bounded historic reads. Preserve deleted-definition readability and graph-version unknowns |
| 5 | Connect authorized evidence predicates through B3, or explicitly ship inclusive reads only while the additional detector decision is open. No temporary issue adapter |
| 6 | Measure final wrapper and writer under representative concurrent work; record size and live proof |

Every slice includes converted callers and contracts. Require touched namespaces
in the authorized implementation harness, reload/adopt through the owner, then
verify `runtime_status` and an ordinary turn. Keep the host REPL reachable; no
lane restarts or resets `default`. Reset recovery belongs to the orchestrator
and loses disposable database history/results/tasks and private objects unless
preserved elsewhere.

## 6. Smaller mechanisms to probe first

| Candidate | Decisive probe |
|---|---|
| Inline JDK operations vs a direct primitive method | Measure final arming, bytecode/primitive dispatch and allocations; reject recursive instrumentation |
| Execution-scoped touched definitions | Compare calls, distinct touched cells and visited snapshot cells. Claim O(touched) only when an owned set actually supplies it; otherwise price the scan |
| Count/total without max | Prove interval arithmetic and concurrent reads; retain max only when lifetime maximum serves a demonstrated read |
| Existing completion/schedule only | Keep a turn open with background work and inspect freshness; if the required persistence bound is unmet, settle the missing owner event before adding machinery |

## 7. Regression classes and size

Use canonical fixtures, real arming and the actual writer. Preserve one recurring
regression per class: callable/version/custody attribution; return/throw behavior;
async test completeness; concurrent/duplicate/refused persistence; restart/fork
origin; schema uniqueness and deleted-definition reads; bounded historic query;
B3 idempotent finding when an authorized criterion exists. No nanosecond assertion
or synthetic hand-rostered schema. Default five-second bounds apply; allowances
must be declared with a number and reason. Run affected tests through B4's one
`seon.test/run`; no suite or private runner.

Historical feature baseline: no `profile.clj` or profile schema, and the existing
wrappers/settlements remain shared owners. Target production plus schema growth is ≤180 lines, with ≤130 test lines and
no measured deletion floor. These are stretch targets, not proven estimates:
removing self-time queries, retirement state, a second reach recorder, whole-program
digest enumeration and threshold configuration should pay for explicit ownership,
ordering and read contracts. Count new source, schema, seams and tests separately
with `wc -l`/disjoint diff spans after A1/B4 land; moves count once. A1's obsolete
benchmark tests remain deleted, not recreated here.

## 8. Completion and decisions

Landing note: `docs/prds/agent-platform/landing/lane-c1.md`, with exact before/after
forms and values, callable digests and tested bases, allocation/latency tables,
write/refusal concurrency evidence, freshness limits, affected file counts and
whether live proof used adoption, hot reload or a new fork. A clean tally alone
does not prove the observation path; absent samples are unavailable, never health.

Stop the dependent slice at a concurrently held file, missing B1/A1/B4 seam,
unproven ownership/ordering or an unresolved design. Preserve unrelated edits.
Report the concrete boundary and three options for a genuine owner choice.

Additional automatic profiling criteria remain an owner decision: recommend
inclusive reads plus existing operation-bound findings (no new heuristic tasks);
alternatively specify a per-operation latency criterion with attributable inputs
(adds policy and proof work), or retain manual investigation (lowest mechanism
cost, no additional automatic routing). Host observations remain volatile unless
a durable destination is explicitly chosen. Exact snapshot ordering and bounded
background persistence are implementation proof gates, not invented guarantees.
