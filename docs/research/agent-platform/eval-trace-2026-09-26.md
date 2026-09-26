# Agent evaluation trace — 2026-09-26

Diagnosis only. No implementation, reload, configuration change, restart, reset,
explicit test request, or test gate was performed. The only program writes were
the requested forms on disposable branch `eval-trace-20260926`; the branch tool
confirmed retirement. This report is the sole committed file.

## Findings

1. **The trivial form is mainly waiting for durable settlement.** The clean JFR
   run took **113.804 ms**, including **98.807 ms in
   `seon.db/transact-call`**. SCI evaluation, including its shown result, took
   **2.328 ms**. JFR recorded **32 file/directory force operations**: their
   union occupied **71.784 ms**, spread across **79.772 ms**. Their summed
   thread time was 323.362 ms; that sum is not elapsed time.
2. **The first defn is mainly paying for a whole-program arity base.** It took
   **2,015.518 ms**. `seon.db/arity-base` took **1,261.457 ms across two calls**;
   the speculative settlement surrounding it took **1,444.567 ms**. JFR
   independently points to querying, deduplicating, and grouping all call edges.
   The branch contained **102,065 caller/call pairs**, not one call edge.
3. **A repeat is a different operation.** The first exact redeclaration took
   **891.928 ms**, including **277.650 ms of context acquisition** and
   **358.927 ms in the durable transaction**. A later exact redeclaration took
   **331.653 ms**, with no arity-base call. It is not evidence that a new
   declaration costs 332 ms. Cache state, whether the row changes, and the
   retained context materially change the path.
4. **The source-family scan is real but is not the leading millisecond cost.**
   The first defn made **28,384** `source-family?` calls:
   exactly **8 × 3,548 projection forms**. Those calls occupied **2.896 ms**;
   their enclosing `shapes-in` calls occupied **13.552 ms**. Count and cost
   should not be conflated.
5. **“The complete schema projection is rebuilt per defn” is too broad.**
   The installed replacement mechanism recompiles changed declarations and
   their reverse dependencies. The first defn made six
   `projection-with-function-contract` calls totaling 32.852 ms and two
   database projection loads totaling 70.437 ms. These still scan substantial
   unchanged input, and some derivations repeat, but the observed
   `projection-build` total was **13.840 ms**, not the missing second.
6. **The exact requested self-time/call-tree attribution cannot be recovered
   from the installed counters.** They retain symbol, scope, completions and
   inclusive time, without invocation parent, thread, start/end span, or run id.
   JFR provides sampled stacks and selected timed events, not every invocation.
   This report accounts for all measured wall time using sequential stages plus
   an explicit remainder, and includes every positive observed armed-cell delta.
   It does not invent exact self times or assign another thread's work to this form.

Default was already faulted: one open `seon.db/transact!` fault, timestamp
2026-09-26T20:46:47Z, plus existing failed-test/problem counts. No claim of runtime
health is made.

## Runtime and source identity

- Operator root: `/Users/sean/src/seon`; cluster: `default`.
- PID **89341**, start **2026-09-26T21:35:37.344Z**, Java **26.0.1**.
- `bin/seon status`, invoked through the MCP Node tool, identified the running
  archive as **`bb07fdf0890961c63ea5641ad1baf86a542faad8`** at
  `data/source/bb07fdf0890961c63ea5641ad1baf86a542faad8`.
- Checkout HEAD at the start was
  `dc215cb163762c4fca7ae1af5406632c4d700bce`, with other lanes' modified files.
  No checkout changes were loaded. Source citations below refer to the **live
  archive**, unless a later commit is explicitly described.
- Loaded Var metadata and JFR line numbers match that archive at the named
  seams: `seon.db/arity-base:3838`,
  `seon.schema/projection-with-declarations:2549`,
  `seon.program/shapes-in:257`, and `base-bindings:3132`.
  This is stronger evidence than reading the moving checkout, but it is not a
  byte-by-byte audit of every loaded Var.
- Archive dependency pins: Datahike
  `67b00071e020a7ccf310985d01b28128cdadec34`; Malli
  `43c6dca8d2d8df3162b13725939954807be4a4ed`; SCI
  `743d1c4317d95af9d1af74928392b913c0d1b91d`; konserve
  `5b39fddd6ae58bae7d08880daecd1ad7242ea273`.
- Observed population: default **5,385 functions**, **2,301 tests**; branch
  **3,548 schema projection forms**, **102,065 caller/call pairs**. A distinct
  count of call values alone was 5,723 and is not the edge count.
- Branch creation used commit
  `6ab83ea1-ab90-504b-a7a3-0ae9559e7ff4`, basis 536871344.
  The branch was hosted as `mcp/eval-trace-20260926`, namespace `user`.

CLAUDE.md was read first. Its “one REPL path” and sub-second requirement are the
standard for this diagnosis. The REPL and Datahike skills were consulted.
Their historical/target claims were not treated as installation evidence.

## Method and observations

All runtime interaction used the MCP tools with explicit root and cluster.
The branch was created by the branch tool and first entered through MCP SCI
with `(inc 1)`, which returned 2. For timed forms, JVM mode obtained the
already-hosted branch through `seon.cluster.host/hosted` and called exactly the
same `seon.cluster.agent/evaluate-source!` entrance with
`seon.cluster.reply/code-sources`. This excludes first hosting and the MCP
bridge's final receipt rendering/network delivery from the measured clock.

Forms:

```clojure
(inc 1)

(defn f "doc" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
```

Each submission was bounded by the installed completion backstop and a 10,000 ms
MCP timeout. Calls were sequential, never overlapping our own probes. No probe
timed out. JFR was capped at 32 MiB and eight seconds, started/stopped around one
form, dumped and closed in `finally`. A later query returned no active JFR
recordings. Runtime code, wrapper roots, entry policy and configuration were
not changed.

`seon.profile/summary` and `cells` were read before the measurement.
For accurate sub-millisecond deltas, the individual LongAdders were sampled;
cells were matched by **IdentityHashMap**, not Clojure numeric equality.
The measured sample reads the profiler's existing weak roster directly under
its lock. Calling the armed `cells` accessor inside the window had itself
added 6,654 `adder?` and 2,218 `accumulator?` calls in a preliminary run, so
that observer-contaminated run is not used for the all-call inventory.

| Label | Form / state | Agent entrance ms | Positive cell completions | Cells with positive delta | Run |
| --- | --- | ---: | ---: | ---: | --- |
| I | inc, warm, JFR | 113.804 | 3,865 | 222 | `c0e67bd4814b` |
| D1 | first f on fresh branch, JFR | 2015.518 | 117,747 | 425 | `84417fd138d3` |
| D2 | first exact redeclaration, no JFR | 891.928 | 137,655 | 433 | `f8f4211f2d9e` |
| D3 | later exact redeclaration, JFR | 331.653 | 56,923 | 431 | `f21939b04546` |

Chronology: the branch was freshly created before the initial SCI inc and the
115.079 ms preliminary inc; neither preceded a user declaration on this branch.
D1 was the first f, D2 its first redeclaration, I a later inc, and D3 the later
redeclaration. Thus I isolates the stable trivial-form path after f exists;
the 115.079 ms clock is the matching before-defn observation.

Additional checks: a preliminary inc took 115.079 ms; a clean non-JFR inc took
108.394 ms with 3,834 observed armed completions. An earlier inc JFR window took
179.097 ms but contained **two** `evaluate-sources` completions and work from
another operation; it is excluded from the primary tree. D3 includes concurrent
web debug rendering (`graph-debug-html` ×2, 56.728 ms inclusive), so its
JVM-wide counts and allocation are not exclusive to our form. No attempt was
made to stop, inspect, or modify another lane's session.

Receipts for D1 and D2 showed `#'user/f`, with no error or diagnostic in the
selected receipt fields. The stored branch row contained the exact source and
contract, digest
`38886f013043eb7e3fe059060e1b45a12e8b2cae59dc61b4ee2fd99f92dfd0a6`.
The inc receipt showed `2`.

The ordinary defn admission path itself ran its configured generated contract
check: **25 calls to user/f**, totaling 0.015624 ms in D1. No
`seon.test/run`, `bin/test-check`, or `bin/test` was requested. These
automatic calls are part of the behavior being diagnosed, not an independently
launched test suite.

### Allocation and JFR limits

ThreadMXBean deltas across surviving platform threads were:

| Window | Sum of observed allocated bytes | Largest carrier / worker delta |
| --- | ---: | ---: |
| I | 134,309,088 | 46,378,848 |
| D1 | 2,334,721,248 | 1,837,991,120 |
| D2 | 1,157,131,048 | 1,153,501,504 |
| D3 | 501,571,032 | 296,296,136 |

These are JVM-window observations, not retained heap, not an exact per-run
allocation measure. Threads that terminate between samples are absent; carrier
work may include other virtual tasks; diagnostic sampling adds allocation.
They substantiate the scale of the reported roughly 2 GB first-defn allocation,
not an exact 2 GB attributable solely to f.

D1 JFR had 988 execution samples and 3,212 allocation samples. The nearest Seon
ancestor was `arity-base`'s whole-edge query at db.clj:3845 in **362** execution
samples; grouping by callee at :3851 in **62**; grouping by caller at :3850 in
**45**. Allocation samples attributed **869,627,264 weighted bytes** to the
whole-edge query and approximately 77 MB to each of the two
`deleted-predicate-functions` scans. Allocation weights are estimates.

The deepest sampled frame is the only self-location observation available.
D1's top leaves were hashing, persistent-vector access, map lookup and Fressian
decoding; it was not executing `inc` for a second. “Malli” appearing above
`apply f` at core.cljc:2236 is an inclusive wrapper frame, not proof that
validation itself consumed that subtree. Samples must not be relabeled as
exact milliseconds.

## Stage accounting and call tree

The outer sequential ownership comes from turn.clj:5105 (fork), :5140
(evaluate), :5205 (settlement), :5226 (install). The following partition uses
those non-overlapping stages. “Other” is the explicit remainder: submission,
admission/planning, receipt preparation outside these owners, transitions,
scheduling and wrapper overhead. It is **not measured function self time**.

| Stage ms | I | D1 | D2 | D3 |
| --- | ---: | ---: | ---: | ---: |
| fork-for-turn | 4.145 | 5.008 | 3.693 | 5.765 |
| evaluate-sources | 3.442 | 1566.098 | 438.873 | 120.513 |
| settle-batch! | 100.760 | 413.074 | 410.714 | 115.749 |
| install-evaluated-rows! | 0.000 | 16.586 | 30.936 | 80.105 |
| Other / explicit remainder | 5.457 | 14.752 | 7.713 | 9.522 |
| **Total** | **113.804** | **2,015.518** | **891.928** | **331.653** |

### Trivial form I

Inclusive milliseconds and call counts follow. Indentation is source-supported
ownership, not a dynamically recorded invocation tree. A repeated function's
global total may also include callers outside the displayed edge.

```text
agent/evaluate-source!                            113.793  ×1
  submit-source!                                  ~1 ms   ×1
    reply/code-sources                              0.145  ×1
  await/await!                                    112.232  ×1 [overlaps worker]
  worker: turn-step → step → turn → resume-turn   ~110 ms
    sci.eval/fork-for-turn                          4.145  ×1
    turn/evaluate-sources                          3.442  ×1
      sci.eval/evaluate                            2.328  ×1
        sci.eval/shown-result                      1.430  ×1
      gate-function-install                        0.012  ×1 [no declaration]
    turn/settle-batch!                            100.760  ×1
      blob/with-publication!                       98.837  ×1
        db/transact-call                          98.807  ×1
          writer validate-write-report             3.836  ×1
          Datahike processing/serialization/
          durable I/O/queue/scheduling remainder  94.971  [not pure self]
other outer work                                   5.457
```

The 94.971 ms remainder includes all unarmed dependency execution and waiting;
there is no timer splitting each queue hop or each serialized node. JFR narrows
it materially: 32 FileForce events, 71.784 ms union, 79.772 ms first-start to
last-end, and samples in Datahike persistent-set serialization, Fressian and
Adler32. FileForce events overlap on several async threads and must not be
added to each other or to the caller's 98.807 ms. The completion wait on the
prepl thread was 112.169 ms.

Work proportionality and necessity:

| Owner | Scaling input | Needed for this form? |
| --- | --- | --- |
| code-sources / reader | source length and reader events | Yes, one source read; observed reader/read ×3 totals 0.366 ms across the enclosing path, not proof the user form was parsed three times |
| SCI evaluation of inc | one expression / one primitive operation | Yes; tiny fraction of the entrance |
| shown-result | scalar value and its render profile | Yes; result is 2 |
| fork-for-turn / base-bindings | all bound namespace entries and private layer | Context custody is needed; enumerating unchanged program bindings is not intrinsic to inc |
| q ×32, pull ×37 | run/agent/configuration/evidence queries, not inc's data dependencies | Admission/recording checks are needed; dozens of reads are orchestration cost |
| writer validation | affected receipt/turn/agent rows and their owned values | Yes, final transaction must be valid |
| commit! / konserve force | changed index nodes, serialization size and filesystem latency | Needed under the **installed durable-before-answer policy**; not needed to calculate 2 |
| schema rebuild / source-family | none observed | Not needed and not on this clean path |

### First defn D1 and first repeat D2

| Stage → function | D1 inclusive ms / calls | D2 inclusive ms / calls | Self attribution and proportionality | Needed for THIS declaration? |
| --- | ---: | ---: | --- | --- |
| evaluate-sources | 1566.098 / 1 | 438.873 / 1 | Includes SCI, gate and speculative settlement | One declaration pipeline is needed |
| → sci.eval/evaluate | 39.709 / 1 | 304.542 / 1 | Includes analysis/admission/printing; D2 also acquires context | Yes, once |
| → → analyzed-rows | 27.381 / 1 | 18.737 / 1 | One source plus namespace preparation | Yes; distinguish analyzer itself below |
| → → → analyzer/analyze | 6.328 / 1 | 5.397 / 1 | clj-kondo invocation and result processing | Analyze one form, not the program |
| → → acquire! | 0.181 / 1 | 277.650 / 2 | D2 acquire-program! 210.122 ms, scans namespace/source/function/digest populations | Acquire the changed definition and affected callers; full populations are not intrinsic |
| → gate-function-install | 80.616 / 1 | 53.554 / 1 | Gate reach query plus disposable candidate and generated check | Installed policy requires a gate; its isolation setup exceeds the tiny check |
| → → disposable-copy | 30.640 / 1 | 34.923 / 1 | Branch acquisition + context copy; registry/branch! 21.165 / 20.807 ms | Isolation has a correctness role for arbitrary candidates |
| → → auto-check | 4.606 / 1 | 7.636 / 1 | 25 generated calls; user/f itself 0.016 ms | Installed admission policy, not language evaluation |
| → with-settled → settled-value | 1444.567 / 1 | 79.808 / 1 | Builds a speculative database for the next form even at end of a one-form run | Not needed to make a nonexistent next form see f; final writer validation remains needed |
| → → db/with | 1437.157 / 1 | 77.172 / 1 | Executes the transaction and validator without committing | Duplicate application work for this one-form run |
| both validators → report-arity-comparison | 1299.957 / 2 | 61.751 / 2 | Not additive with db/with or durable transaction | Check changed call arities; whole-program base is not intrinsic |
| → arity-base | 1261.457 / 2 | 18.184 / 2 | 102,065 edges, all bounds, sets and two grouped indexes on miss; content-key scan on hits | No need to rediscover every unchanged edge to admit f |
| settlement → transact-call | 402.352 / 1 | 358.927 / 1 | Actual writer + validation + dependency I/O; exact self unavailable | One real transaction is needed |
| install-evaluated-rows! | 16.586 / 1 | 30.936 / 1 | Root transfer, projection/contract installation and coverage query | Install f once; repeated contract derivation is not fundamental |
| projection-with-function-contract (cross-stage) | 32.852 / 6 | 1.701 / 6 | Full input comparison, changed closure compilation | One contract needs compiling; not six independent decisions |
| schema/load-projection (cross-stage) | 70.437 / 2 | 0 / 0 | Stored declaration rows; includes deleted-predicate scan | Changed contract is needed; unrelated forms need not be reread |
| → deleted-predicate-functions | 35.269 / 2 | 0 / 0 | tree-seq over every schema and function contract | f contains no predicate; unrelated deleted predicates are unchanged |
| program/shapes-in | 13.552 / 8 | 13.227 / 9 | 3,548 form identities each call | Row shape is needed; repeated population scan is not |
| → source-family? | 2.896 / 28,384 | 2.795 / 31,932 | Constant work per form, repeated by enclosing scan | Not 28,384 independent needs |
| shown-result | 1.625 / 1 | 1.079 / 1 | Prints the SCI Var | Yes |

All numeric entries above are inclusive. No “self” column is filled by
subtracting global function totals: cross-thread callbacks, repeated callers
and overlapping wrappers make that invalid. The stage partition and explicit
remainders are the available millisecond accounting. Exact per-edge/self proof
would require invocation spans carrying run/thread/parent identity, which this
diagnosis did not implement.

D3 demonstrates further variance: 120.513 ms evaluation/gate, 115.749 ms
settlement, 80.105 ms post-commit installation. Its `with-settled` took only
6.554 ms, and neither `arity-base` nor `report-arity-comparison` ran.
The exact same authored bytes had become a no-change row. It still made
21,288 source-family calls. Concurrent web activity prevents interpreting its
aggregate read/validation counts as entirely this run.

## Why this work exists: source and history

These are causal code paths and introducing/changing commits, identified with
`git log -S` and inspected diffs. No before/after binaries were run; historic
performance numbers below are commit evidence, not reproduced benchmarks.

| Decision / seam | Source in live archive | History and consequence |
| --- | --- | --- |
| Await durable settlement before answering | cluster/agent.clj:757–796; turn.clj:3774, :3840 onward; db.clj:4724–4796; Datahike writer.cljc:242–264 | **de67fdd1a** (Sep 26 14:45): removed the applied-before-durable path because MCP read committed receipts and returned no value. Commit records applied-answer p50 40.0 ms versus durable p50 133.9 ms. This directly explains why “answer faster” and “answer the saved result” diverged. |
| Build a speculative settled database after each declaration | turn.clj:1391–1407, :5023 | **266b52353** (Sep 25): localizes settlement refusal and carries rows between forms. The reducer performs with-settled after the final declaration too, so a one-form run pays speculative application and final application. |
| Derive and cache a whole-program arity base | db.clj:3769–3969 | **431821bfa** (Sep 22) replaced whole comparison per write with incremental comparison over a cached base. **863221d3e** (Sep 24) seeds the after-value's base and adds commit reuse. A miss still queries every edge and bound, compares them, and groups by caller/callee. The four-content / sixteen-commit LRU is shared; the exact miss cause (first use versus eviction) is not recorded. |
| Advance projection incrementally | schema.clj:2549–2730, :3436 | **1625fb9bc** (Sep 22) already recompiles changed declarations and reverse closure. New function contract calls preserve the forms but compare population maps and reconstruct retained registry/index state. Thus “build” does not imply recompiling every schema. |
| Resolve deleted predicates in retained stored programs | schema.clj:2811–2843 | **ea048d617** (Sep 22) added a tree-seq across all forms and contracts on projection load. It protects resume of old programs. That scan is unrelated to this f's int→int contract but is repeated on its database projection loads. |
| Derive program row families from declarations | program.cljc:17–24, :257–273 | **9b4a7522f** (Sep 24) introduced source-family ownership. Every live shapes-in call still walks all form keys. **9b6df1ff4** (Sep 26 15:47) adds projection memoization in the newer checkout, **after the running archive**. Its claimed fix is not installed evidence for PID 89341. |
| Fork/regenerate contexts and acquire branch program | sci/eval.clj:2774, :3132–3270, :3570 | **ff9507c1b** (Sep 8) carries private objects in memory; **684f185f8** (Sep 16) derives acquisition from current admission provenance. acquire-program! reads all namespace sources, functions and both digest populations before selecting overrides and callers. fork-for-turn still snapshots program bindings on ordinary runs. |
| Prove installation in disposable copy | turn.clj:3352–3418; sci/eval.clj:4920–5013 | **1b77876af** (Sep 26) moved the gate to a disposable branch/context. The trace pays branch acquisition although 25 arithmetic checks consume only ~0.016 ms in f. |
| Time and validate every armed call | instrument.clj:480–521, :741–787; profile.clj:107–126; Malli core.cljc:2210–2241 | **ed62a3e06** (Sep 22) introduced the cells. Malli compiles validators at installation, then validates input/output around the callable. profile/timed encloses that wrapper. The trace does not show per-call contract recompilation as the dominant cause. |

The installed Datahike default commit wait is **0 ms**
(`writer.cljc:83`); there is no evidence of a deliberate 100 ms batching sleep.
The writer commits index/schema/metadata before delivering the callback.
JFR's FileForce events establish actual filesystem waiting on this run.

There is a current documentation/installation mismatch: CLAUDE.md's
“Recover facts, not execution” paragraph describes applied settlement followed
by fire-and-forget durability. PID 89341 instead runs the durable path after
de67fdd1a. This report records that discrepancy; it does not change the policy
or propose restoring the removed mechanism.

## Ranked avoidable work and separate durability cost

Ranking uses enclosing owner times, not sums of nested cells.

1. **Whole-program arity-base derivation on a miss: 1,261.457 ms in D1.**
   Work scales with all call edges/bounds, despite f adding one simple body.
   Cache reuse reduces it to 18.184 ms in D2. It is the largest observed
   avoidable recomputation. D1's 1,444.567 ms speculative-settlement parent
   includes it and must not be counted again.
2. **Whole-program context acquisition after the first declaration:
   277.650 ms in D2**, including 210.122 ms acquire-program! and 83.025 ms
   function-digests (nested). It reads unchanged program populations to
   reacquire a context whose useful change is f.
3. **Speculative application for the last form: 79.808 ms in warm D2,
   1,444.567 ms including the miss in D1.** This is a separate design decision,
   overlapping item 1. There is no subsequent form that needs that speculative
   value in the measured one-form run.
4. **Repeated projection derivation and whole-input scans:** D1 database
   projection loads 70.437 ms, including 35.269 ms of predicate-tree scans;
   cross-stage function-contract projection calls 32.852 ms. These overlap
   higher stages and one another. D3 post-commit installation still pays
   74.218 ms content-projection, including 51.416 ms load-projection.
5. **Gate setup and global reach selection:** disposable-copy 30.640–41.970 ms;
   gate-set around 21 ms; the full gate 53.554–80.616 ms. The tiny generated
   calls are not the expensive part. Isolation remains justified for arbitrary
   effects; the trace identifies its cost, not permission to remove it.
6. **Repeated row-shape population scan:** 13.073–13.552 ms per defn window,
   with tens of thousands of armed predicate calls. A later commit addresses
   this, but that commit is not the running program.
7. **Unchanged context regeneration on inc:** fork-for-turn 4.145 ms,
   proportional to bound program/private entries. This is smaller, but does
   not follow the size of `(inc 1)`.

**Durable transaction latency is separate from this ranking.** I spent
98.807 ms there; D1/D2 spent 402.352/358.927 ms including declaration validation.
Those totals are not entirely avoidable work: they include required final
validation and the installed durability guarantee. FileForce timing supports
a large storage component for inc, but no complete transaction phase spans
exist to split every defn commit millisecond between I/O, serialization,
validation and queue/scheduler delay.

## What the two forms fundamentally require

For `(inc 1)`: read one form, resolve/analyze its tiny expression, evaluate it
once to 2, retain the live result, record one evaluation/run transaction, and
print the scalar once. No function schema changed; no program-wide arity,
schema, source-family or digest work is required.

For the defn: read and analyze one declaration, construct one SCI function,
compile/arm its int→int contract, derive its one declaration row and changed
references, validate the affected final values/calls, record the declaration
and evaluation in one transaction, retain/install its evaluated root, and print
the Var once. The installed entry policy additionally requires its gate and
generated check. Unchanged program rows and unrelated predicate trees need
not be rediscovered.

The following are **engineering budgets**, not measured lower bounds or a
promise of a patch. They make the intended proportionality concrete.

| Required work | inc target ms | this defn target ms | Basis |
| --- | ---: | ---: | --- |
| Read source once | 0.1–0.5 | 0.2–1 | Actual aggregate reader costs were below 0.7 ms |
| Analyze/resolve only this form | 0.1–0.5 | 5–10 | Actual clj-kondo analysis 5.4–6.3 ms; full analyzed-row preparation was larger |
| Evaluate, retain root/result; compile one small contract for defn | 0.01–0.2 | 0.2–2 | Arithmetic is negligible; installed wrapper totals are sub-ms, but exact unwrapped self was not timed |
| Local declaration/admission checks | 0–0.5 | 2–10 | Target proportional to affected rows/calls, not 102k edges |
| Prepare/apply/validate one transaction, before durable I/O | 2–6 | 5–15 | Inc validator already ~3.8 ms; defn's local validation budget is a target |
| Print once | 0.5–2 | 0.5–2 | Actual shown-result 1.1–1.7 ms |
| Minimal context/dispatch bookkeeping | 1–3 | 1–3 | Target; current additional program/context work exceeds it |
| **CPU/application-path budget** | **about 4–13** | **about 14–43** | Sum of the above rounded ranges |
| Durable storage/acknowledgement | **measured separately** | **measured separately** | Inc FileForce union was 71.8 ms; the dependency/storage path must be measured, not assigned a fictitious 1 ms |

Under the current durable-before-answer policy, roughly **90–120 ms inc** is
consistent with this storage observation even after reducing several ms of
bookkeeping. A reasonable working target for a genuinely local defn would be
tens of ms of application work plus one ordinary durable commit, plausibly
roughly **100–180 ms on this observed storage path**, not a measured guarantee.
Changing when durability is acknowledged changes semantics and is outside
this diagnosis. Sub-second ordinary work does not require dropping validation;
it requires that validation and reuse follow the changed inputs.

## Reproduction and evidence retention

Local diagnostic recordings (closed, no running recorder):

- `tmp/eval-trace-inc.jfr`: excluded concurrent-work window.
- `tmp/eval-trace-inc2.jfr`: I.
- `tmp/eval-trace-defn.jfr`: D1.
- `tmp/eval-trace-defn2.jfr`: D3.

These raw diagnostic files are intentionally uncommitted; only this report was
requested for commit. The complete positive counter inventory is embedded
below so the core evidence outlives the branch and chat. Run ids above identify
the stored receipts; branch retirement removes its roster reference, so it is
not a promise of indefinite storage retention.

To reproduce, create a newly named branch with the MCP branch tool, enter once
through MCP SCI to establish its host, then run the following one-form JVM
diagnostic with explicit root/cluster, replacing the branch/name/source.
The local cell reads do not reset counters or change wrappers. For the inc2 and
defn2 recordings, also enable `jdk.FileForce` with threshold ZERO.
Do not reuse cumulative summary totals as a single-form result.

```clojure
(let [parent (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
 host (seon.cluster.host/hosted parent :eval-trace-20260926)
 live @#'seon.profile/live
 population (seon.profile/cells)
 summary (seon.profile/summary population 3)
 sample (fn [] (let [v (locking live (vec live)) m (java.util.IdentityHashMap.)] (doseq [c v] (.put m (:seon.profile/call-counter c) [(.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/call-counter c)) (.sum ^java.util.concurrent.atomic.LongAdder (:seon.profile/total-counter c)) (str (:seon.profile/sym c)) (str (:seon.profile/scope c))])) m))
 bean (java.lang.management.ManagementFactory/getThreadMXBean)
 alloc (fn [] (into {} (map (fn [id] [id [(.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) (.getThreadCpuTime bean id) (some-> (.getThreadInfo bean id) .getThreadName)]]) (.getAllThreadIds bean))))
 recording (jdk.jfr.Recording.)]
 (try
  (.setName recording "eval-trace-NAME")
  (.setMaxSize recording 33554432)
  (.setDuration recording (java.time.Duration/ofSeconds 8))
  (.withPeriod (.enable recording "jdk.ExecutionSample") (java.time.Duration/ofMillis 1))
  (.withThreshold (.enable recording "jdk.ThreadPark") (java.time.Duration/ofMillis 1))
  (.withThreshold (.enable recording "jdk.JavaMonitorEnter") (java.time.Duration/ofMillis 1))
  (.withThreshold (.enable recording "jdk.FileWrite") (java.time.Duration/ZERO))
  (.enable recording "jdk.ObjectAllocationSample")
  (.enable recording "jdk.GarbageCollection")
  (.start recording)
  (let [ab (alloc) before (sample) start (System/nanoTime)
        result (seon.cluster.agent/evaluate-source! (assoc (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id]) :seon.agent/segmenter 'seon.cluster.reply/code-sources :seon.cluster.reply/text "(inc 1)"))
        elapsed (/ (- (System/nanoTime) start) 1e6) after (sample) aa (alloc)
        rows (->> after (keep (fn [[k [calls nanos sym scope]]] (let [[bc bn] (or (.get before k) [0 0]) n (- calls bc)] (when (pos? n) [sym scope n (/ (- nanos bn) 1e6)])))) (sort-by #(nth % 3) >) vec)
        threads (->> aa (keep (fn [[id [a c n]]] (when-let [[ba bc] (get ab id)] (when (or (> (- a ba) 10000) (> (- c bc) 100000)) [id n (- a ba) (/ (- c bc) 1e6)])))) vec)]
   (.stop recording)
   (.dump recording (java.nio.file.Paths/get "/Users/sean/src/seon/tmp/eval-trace-NAME.jfr" (make-array String 0)))
   (cheshire.core/generate-string {:elapsed elapsed :result result :rows rows :threads threads :summary summary}))
  (finally (.close recording))))
```

JFR parsing used `jdk.jfr.consumer.RecordingFile`, one file at a time:
ExecutionSample's `sampledThread`, stack method/type/line; ThreadPark and
FileForce's start/duration; ObjectAllocationSample's `weight`.
Execution samples grouped by nearest Seon ancestor, library frame and leaf
separately. Empty JFR thread names were preserved, not assigned to the prepl.
FileForce union was computed from overlapping start/end intervals, rather
than summing durations.

### Complete observed armed-call inventory

Each cell is **completed calls / inclusive milliseconds**.
A dash means no positive completion delta observed, not that the function can
never be called. Host cells are JVM-wide; SCI cells are marked. Identical
symbol/scope rows, if present, are summed only for this presentation.
All positive deltas are retained, including predicates, sub-millisecond calls
and D3's concurrent web work. Calls spanning a sampling boundary may contribute
their entire duration when they complete, while still-running calls have not
yet incremented. No exact per-run/self attribution is implied.

| Function | I calls / ms | D1 calls / ms | D2 calls / ms | D3 calls / ms |
| --- | ---: | ---: | ---: | ---: |
| `my.program/supplied-context` | — | 1 / 0.011542 | 1 / 0.012417 | 1 / 0.013333 |
| `seon.ai.tokens/estimate` | — | — | — | 224 / 0.177001 |
| `seon.ai.tokens/estimate-chars` | 4 / 0.008374 | 4 / 0.014459 | 4 / 0.006833 | 4 / 0.014374 |
| `seon.ai.tokens/estimate-of-characters` | — | — | — | 112 / 0.040420 |
| `seon.ai/agent-overlay` | 3 / 0.632375 | 3 / 0.683958 | 3 / 0.557001 | 3 / 0.855208 |
| `seon.ai/agent-setting-attributes` | 3 / 0.178125 | 3 / 0.232709 | 3 / 0.146125 | 3 / 0.257791 |
| `seon.await/await-port-operations` | 1 / 112.222667 | 1 / 2013.374125 | 1 / 889.827166 | 1 / 329.979334 |
| `seon.await/await!` | 1 / 112.231709 | 1 / 2013.386125 | 1 / 889.837292 | 1 / 329.987375 |
| `seon.await/closed-operation?` | 1 / 0.004667 | 1 / 0.016917 | 1 / 0.003959 | 1 / 0.001959 |
| `seon.await/remaining-ms` | 2 / 0.002083 | 2 / 0.004166 | 2 / 0.003000 | 2 / 0.002917 |
| `seon.blob/input-stream?` | — | 2 / 0.001750 | 2 / 0.001625 | 2 / 0.001625 |
| `seon.blob/stage!` | — | 1 / 0.048625 | 1 / 0.040917 | 1 / 0.046583 |
| `seon.blob/stored-binary` | — | 1 / 0.009959 | 1 / 0.010500 | 1 / 0.011959 |
| `seon.blob/with-publication!` | 1 / 98.837375 | 1 / 411.024958 | 1 / 368.409334 | 1 / 112.431875 |
| `seon.cache/cache?` | 4 / 0.003626 | 14 / 0.014374 | 44 / 0.024540 | 146 / 0.061835 |
| `seon.cache/join` | 4 / 0.003749 | 14 / 0.012792 | 44 / 0.023082 | 146 / 0.059080 |
| `seon.cache/kind-counters` | 4 / 0.005916 | 14 / 0.021206 | 44 / 0.034794 | 146 / 0.078790 |
| `seon.cache/lookup` | 8 / 0.147457 | 28 / 0.280830 | 88 / 0.464125 | 292 / 1.221505 |
| `seon.call-preparation/argument-facts` | — | 4696 / 1.268084 | — | — |
| `seon.call-preparation/arity-facts` | — | 2352 / 3.146791 | — | — |
| `seon.call-preparation/coherent-supplier` | — | 12 / 1.145332 | 4 / 0.389958 | — |
| `seon.call-preparation/contract-arities` | — | 3 / 5.113708 | 1 / 0.292750 | — |
| `seon.call-preparation/derive-snapshot` | — | 3 / 10.830376 | 1 / 2.690417 | — |
| `seon.call-preparation/memoized-snapshot` | — | 3 / 10.880375 | 2 / 2.730542 | 2 / 0.032334 |
| `seon.call-preparation/newest-row-transaction` | — | 3 / 0.540626 | 1 / 0.200167 | — |
| `seon.call-preparation/row-attributes` | — | 3 / 0.006000 | 1 / 0.002417 | — |
| `seon.call-preparation/snapshot` | — | 5 / 10.982292 | 8 / 2.822460 | 7 / 0.111124 |
| `seon.call-preparation/snapshot-revision-key` | — | 3 / 0.012250 | 2 / 0.007541 | 2 / 0.008541 |
| `seon.call-preparation/state?` | 14 / 0.003540 | 66 / 0.013623 | 67 / 0.012377 | 179 / 0.044326 |
| `seon.call-preparation/supply` | — | 4 / 0.833541 | 4 / 0.772665 | 4 / 1.131542 |
| `seon.cluster.agent/acquire-context!` | — | 1 / 28.856750 | 1 / 31.787042 | 1 / 37.102584 |
| `seon.cluster.agent/acquire-once!` | — | 1 / 28.766583 | 1 / 31.745916 | 1 / 37.037792 |
| `seon.cluster.agent/agents-to-arm` | 1 / 0.105375 | 1 / 0.302666 | 1 / 0.092084 | 1 / 0.099041 |
| `seon.cluster.agent/arm!` | 1 / 0.014208 | 1 / 0.017209 | 1 / 0.013500 | 1 / 0.016000 |
| `seon.cluster.agent/child-roster!` | — | 1 / 0.006875 | 1 / 0.005708 | 1 / 0.005958 |
| `seon.cluster.agent/evaluate-source!` | 1 / 113.793334 | 1 / 2015.503709 | 1 / 891.922958 | 1 / 331.642333 |
| `seon.cluster.agent/mailbox-step` | 1 / 0.089958 | 1 / 0.004458 | 1 / 0.005083 | 1 / 0.031500 |
| `seon.cluster.agent/release-context!` | — | 1 / 9.551958 | 1 / 8.464834 | 1 / 8.827041 |
| `seon.cluster.agent/routing` | — | 1 / 0.001834 | 1 / 0.001542 | 1 / 0.001583 |
| `seon.cluster.agent/segmenter` | 1 / 0.007375 | 1 / 0.008375 | 1 / 0.006625 | 1 / 0.007625 |
| `seon.cluster.agent/stop-owned-graphs!` | — | 1 / 0.011292 | 1 / 0.011542 | 1 / 0.022625 |
| `seon.cluster.agent/submit-source-in-projection` | 1 / 1.139166 | 1 / 1.340250 | 1 / 1.689459 | 1 / 1.284459 |
| `seon.cluster.agent/submit-source!` | 1 / 1.149417 | 1 / 1.352459 | 1 / 1.698584 | 1 / 1.294792 |
| `seon.cluster.agent/throw-causes!` | — | 3 / 0.006375 | 3 / 0.004666 | 3 / 0.006000 |
| `seon.cluster.agent/turn-step` | 1 / 112.197042 | 1 / 2013.306542 | 1 / 889.823750 | 1 / 329.955458 |
| `seon.cluster.message/trigger` | 2 / 1.111333 | 2 / 0.859083 | 2 / 1.069666 | 2 / 0.991375 |
| `seon.cluster.registry/branch!` | — | 1 / 21.165334 | 1 / 20.806833 | 1 / 24.384000 |
| `seon.cluster.registry/retire-branch!` | — | 1 / 9.195666 | 1 / 8.040875 | 1 / 8.371333 |
| `seon.cluster.registry/roster` | — | 1 / 0.186459 | 1 / 0.134709 | 1 / 0.179375 |
| `seon.cluster.reply/code-sources` | 1 / 0.144542 | 1 / 0.213458 | 1 / 0.157167 | 1 / 0.198583 |
| `seon.cluster.store/connection-object?` | 13 / 0.005125 | 77 / 0.037919 | 79 / 0.033876 | 137 / 0.080080 |
| `seon.cluster.store/datahike-configuration` | — | 1 / 0.080250 | 1 / 0.052500 | 1 / 0.061208 |
| `seon.cluster.store/file-lock-object?` | 13 / 0.001873 | 76 / 0.012332 | 77 / 0.010921 | 132 / 0.022368 |
| `seon.cluster.store/open-branch!` | — | 1 / 4.612500 | 1 / 4.162875 | 1 / 5.892000 |
| `seon.cluster.store/release-branch!` | — | 1 / 0.302250 | 1 / 0.371792 | 1 / 0.374750 |
| `seon.cluster.wake/arming-attributes` | 1 / 0.017792 | 1 / 0.016250 | 1 / 0.024666 | 1 / 0.018500 |
| `seon.cluster.wake/wake-attributes` | 1 / 0.057416 | 1 / 0.058792 | 1 / 0.056125 | 1 / 0.124709 |
| `seon.cluster/cluster-name?` | 20 / 0.009751 | 100 / 0.043080 | 108 / 0.038961 | 272 / 0.106547 |
| `seon.config/declared-projection` | 2 / 0.186083 | 7 / 0.541915 | 10 / 0.600500 | 65 / 1.943498 |
| `seon.config/dial-attributes` | 2 / 0.012584 | 7 / 0.083666 | 11 / 0.502002 | 65 / 0.272961 |
| `seon.config/effective` | 2 / 0.688833 | 7 / 3.744790 | 10 / 3.598417 | 65 / 12.982423 |
| `seon.config/effective-in` | 2 / 0.490584 | 7 / 3.154958 | 10 / 2.948208 | 65 / 10.828290 |
| `seon.config/result-caps` | — | 4 / 0.026792 | 7 / 0.038042 | 6 / 0.038709 |
| `seon.db/absent-call` | 2 / 0.015542 | 2 / 0.016375 | 2 / 0.037458 | 2 / 0.022958 |
| `seon.db/advanced-arity-base` | — | — | 1 / 0.026750 | — |
| `seon.db/aligned-query-arguments` | 32 / 0.251125 | 49 / 0.545539 | 71 / 0.620540 | 69 / 0.756585 |
| `seon.db/arity-base` | — | 2 / 1261.456584 | 2 / 18.184208 | — |
| `seon.db/arity-comparison` | — | 5 / 59.176460 | 4 / 0.025210 | — |
| `seon.db/arity-content-key` | — | 4 / 4.085084 | 4 / 3.107168 | — |
| `seon.db/arity-mismatch-report` | — | 2 / 0.045001 | 2 / 0.045625 | — |
| `seon.db/arity-verdict` | — | 2 / 0.122541 | 2 / 0.125334 | — |
| `seon.db/as-of` | — | — | 1 / 0.024792 | — |
| `seon.db/as-of-key` | — | — | 1 / 0.002166 | — |
| `seon.db/basis-t` | 6 / 0.009958 | 20 / 0.029959 | 20 / 0.026290 | 19 / 0.030252 |
| `seon.db/call-reach` | — | 2 / 0.028958 | 2 / 0.027292 | 2 / 0.024583 |
| `seon.db/call-with-custody` | 1 / 2.264666 | 1 / 39.622708 | 1 / 304.484792 | 1 / 34.489292 |
| `seon.db/carried-projection` | 70 / 0.522334 | 108 / 101.822668 | 628 / 60.801794 | 251 / 76.958629 |
| `seon.db/carry-connection-projection-state!` | — | 3 / 0.009584 | 3 / 0.008125 | 3 / 0.008667 |
| `seon.db/carry-projection-state` | 18 / 0.059292 | 25 / 0.098123 | 27 / 0.101165 | 31 / 0.127331 |
| `seon.db/commit-id` | 2 / 0.012292 | 6 / 0.038709 | 7 / 0.042042 | 6 / 0.048792 |
| `seon.db/committed-value-identity` | 5 / 0.013792 | 9 / 0.027543 | 16 / 0.041336 | 9 / 0.026124 |
| `seon.db/connection-identity` | — | — | 1 / 0.014083 | — |
| `seon.db/connection-object?` | 82 / 0.012961 | 235 / 0.041378 | 244 / 0.038163 | 363 / 0.068162 |
| `seon.db/connection?` | 2 / 0.002125 | 2 / 0.005833 | 3 / 0.009417 | 2 / 0.004458 |
| `seon.db/content-projection` | — | 7 / 98.413918 | 1 / 50.855917 | 1 / 74.217750 |
| `seon.db/database-identity` | 7 / 0.015916 | 15 / 0.040251 | 23 / 0.051628 | 15 / 0.035875 |
| `seon.db/database-value?` | 1058 / 0.094122 | 1849 / 0.200982 | 7137 / 0.577500 | 3334 / 0.377979 |
| `seon.db/database-view` | — | 3 / 0.038209 | 3 / 0.042999 | — |
| `seon.db/datoms` | — | 11 / 3.292917 | 7 / 0.256042 | 2 / 0.093542 |
| `seon.db/datoms-call` | — | 11 / 3.235789 | 7 / 0.225833 | 2 / 0.060375 |
| `seon.db/db` | 16 / 0.163043 | 21 / 0.207582 | 23 / 0.160127 | 27 / 0.216580 |
| `seon.db/declaration-content-key` | — | 7 / 13.620291 | 1 / 41.431000 | 1 / 21.506500 |
| `seon.db/declaration-value` | — | 20 / 3.126539 | 20 / 2.479041 | — |
| `seon.db/declared-arity-bounds` | — | 1 / 56.692458 | — | — |
| `seon.db/declares-program?` | 28 / 0.148707 | 33 / 0.166375 | 51 / 8.259336 | 42 / 0.174746 |
| `seon.db/definition-digest-of` | — | 4 / 0.411208 | 4 / 1.276083 | 2 / 0.436042 |
| `seon.db/encode-query-request` | 32 / 0.962169 | 49 / 15.074833 | 71 / 2.028999 | 70 / 3.842418 |
| `seon.db/foreign-connection-error` | 1 / 0.002708 | 1 / 0.003208 | 1 / 0.003542 | 1 / 0.002875 |
| `seon.db/history` | — | 3 / 0.047043 | 2 / 0.037167 | — |
| `seon.db/identity-attributes` | 7 / 1.111501 | 13 / 2.347376 | 13 / 1.795624 | 71 / 11.986331 |
| `seon.db/index-read-dependencies` | — | 11 / 0.061876 | 7 / 0.035418 | 2 / 0.025417 |
| `seon.db/jdk-integers->long` | 1 / 0.010125 | 2 / 0.035792 | 2 / 0.037001 | 2 / 0.021958 |
| `seon.db/lookup-ref-error` | 37 / 0.063251 | 51 / 0.135915 | 554 / 0.575791 | 128 / 0.335460 |
| `seon.db/malformed-query-pattern-error` | 32 / 0.388917 | 49 / 0.842790 | 71 / 0.743165 | 70 / 0.892836 |
| `seon.db/missing-pull-selector-error` | 37 / 0.013334 | 51 / 0.029705 | 556 / 0.108755 | 130 / 0.061088 |
| `seon.db/missing-transaction-data-error` | 1 / 0.002250 | 1 / 0.003041 | 1 / 0.002958 | 1 / 0.002167 |
| `seon.db/native-query-result?` | 30 / 0.339498 | 47 / 0.511041 | 69 / 0.642623 | 68 / 0.822672 |
| `seon.db/nearest-base` | — | 2 / 0.073501 | — | 1 / 0.059459 |
| `seon.db/prepared-write` | 1 / 0.033583 | 2 / 0.194875 | 2 / 0.162875 | 2 / 0.059708 |
| `seon.db/projection-cache-key` | 27 / 0.035541 | 31 / 0.059628 | 49 / 0.074834 | 40 / 0.065791 |
| `seon.db/pull` | 38 / 2.242122 | 52 / 51.348250 | 555 / 101.253736 | 128 / 90.051213 |
| `seon.db/pull-call` | 37 / 2.130666 | 51 / 51.106501 | 556 / 101.641147 | 130 / 90.741202 |
| `seon.db/pull-many` | — | — | 2 / 0.988542 | 2 / 0.957834 |
| `seon.db/q` | 32 / 10.104418 | 49 / 59.061209 | 71 / 247.771089 | 69 / 56.603373 |
| `seon.db/query-binding-values` | 309 / 0.052585 | 695 / 0.231055 | 2729 / 1.042553 | 11149 / 5.425638 |
| `seon.db/query-find-attributes` | 31 / 0.499541 | 48 / 0.916288 | 71 / 1.417290 | 69 / 2.301867 |
| `seon.db/query-input-position` | 62 / 0.221209 | 96 / 0.441789 | 140 / 0.593297 | 138 / 0.605121 |
| `seon.db/query-input-values` | 159 / 0.175620 | 244 / 0.431674 | 355 / 1.057967 | 349 / 4.102209 |
| `seon.db/query-value-type-error` | 30 / 0.452081 | 48 / 0.814082 | 71 / 0.954132 | 68 / 1.674585 |
| `seon.db/read-declarations` | 69 / 0.067081 | 111 / 0.155329 | 635 / 0.483802 | 202 / 0.304374 |
| `seon.db/read-evidence` | 2 / 0.056375 | 2 / 0.055583 | 2 / 0.052208 | 2 / 0.053875 |
| `seon.db/read-refusal?` | 3 / 0.003500 | 43 / 0.024419 | 42 / 0.019585 | 21 / 0.013457 |
| `seon.db/removed-definition-error` | 1 / 0.004500 | 2 / 0.009791 | 2 / 0.007750 | 2 / 0.010250 |
| `seon.db/report-arity-comparison` | — | 2 / 1299.956875 | 2 / 61.751250 | — |
| `seon.db/report-identity-attributes` | 1 / 0.319750 | 2 / 0.790751 | 2 / 0.599999 | 2 / 0.746291 |
| `seon.db/retention-check` | 1 / 0.002958 | 2 / 0.010041 | 2 / 0.006167 | 2 / 0.008293 |
| `seon.db/retention-report-check` | 1 / 0.433708 | 2 / 3.512125 | 2 / 0.936792 | 2 / 1.097541 |
| `seon.db/revision-agreement` | — | 9 / 0.024585 | — | 5 / 0.017125 |
| `seon.db/schema-database` | 291 / 0.095793 | 446 / 0.172421 | 2003 / 0.608511 | 807 / 0.337757 |
| `seon.db/source-argument-error` | 32 / 0.023875 | 49 / 0.063710 | 71 / 0.087328 | 70 / 0.083166 |
| `seon.db/stored-call-reach` | — | 2 / 0.033500 | 2 / 0.032583 | 2 / 0.028417 |
| `seon.db/supplied-connection` | — | 1 / 0.003583 | 1 / 0.003500 | 1 / 0.003792 |
| `seon.db/supplied-database-value` | — | 1 / 0.016000 | 1 / 0.015583 | 1 / 0.017750 |
| `seon.db/transact-call` | 1 / 98.807125 | 1 / 402.351500 | 1 / 358.926709 | 1 / 103.878458 |
| `seon.db/transact!` | 1 / 98.831209 | 1 / 402.397208 | 1 / 358.976625 | 1 / 103.922542 |
| `seon.db/transaction-report-datom?` | 250 / 0.024948 | 1212 / 0.172703 | 1419 / 0.145569 | 427 / 0.050589 |
| `seon.db/validate-pulled-result` | 39 / 0.026042 | 53 / 0.049291 | 559 / 0.234547 | 130 / 0.110448 |
| `seon.db/validate-write-report` | 1 / 3.835666 | 2 / 1384.009959 | 2 / 72.490667 | 2 / 6.627459 |
| `seon.db/value-projection` | 26 / 0.094916 | 9 / 94.177876 | 47 / 51.145833 | 37 / 0.152586 |
| `seon.db/with` | — | 1 / 1437.157292 | 1 / 77.171666 | 1 / 2.468916 |
| `seon.db/with-declarations` | 108 / 12.727791 | 175 / 164.277206 | 1199 / 445.720622 | 333 / 232.153384 |
| `seon.db/write-agent-retraction-error` | 1 / 0.010917 | 2 / 0.026999 | 2 / 0.028834 | 2 / 0.018167 |
| `seon.db/write-attribute-plans` | 1 / 0.101416 | 22 / 12.919998 | 22 / 1.271290 | 2 / 0.241125 |
| `seon.db/write-digest-error` | 1 / 0.038375 | 2 / 4.979833 | 2 / 1.576667 | 2 / 0.062583 |
| `seon.db/write-owned-values-error` | 1 / 2.298000 | 2 / 19.901542 | 2 / 6.118792 | 2 / 3.261667 |
| `seon.db/write-report-error` | 1 / 3.801667 | 2 / 1341.920667 | 2 / 72.357875 | 2 / 6.528416 |
| `seon.effect/capabilities` | — | 2 / 0.450291 | 2 / 0.387667 | 2 / 0.809749 |
| `seon.env/advance-projection!` | 1 / 0.010042 | 5 / 0.075291 | 6 / 0.061249 | 5 / 0.100166 |
| `seon.env/carry-state` | — | 2 / 0.005084 | 2 / 0.004917 | 2 / 0.004458 |
| `seon.env/construct` | — | 5 / 0.047625 | 5 / 0.041250 | 5 / 0.080374 |
| `seon.env/environment` | — | 5 / 0.056042 | 5 / 0.049166 | 5 / 0.088875 |
| `seon.env/environment-state` | — | 6 / 0.017124 | 6 / 0.015583 | 6 / 0.016750 |
| `seon.env/environment-state?` | 26 / 0.015955 | 71 / 0.043300 | 75 / 0.041798 | 133 / 0.088786 |
| `seon.env/environment?` | 43 / 0.008538 | 173 / 0.029120 | 178 / 0.028460 | 291 / 0.055903 |
| `seon.env/members` | — | 5 / 0.010790 | 5 / 0.010125 | 5 / 0.043750 |
| `seon.env/of` | 6 / 0.043332 | 20 / 0.196873 | 21 / 0.173959 | 76 / 0.543296 |
| `seon.env/refuse-incomplete-environment!` | — | 5 / 0.010916 | 5 / 0.011250 | 5 / 0.011000 |
| `seon.env/replace-environment!` | 1 / 0.005500 | 3 / 0.014417 | 3 / 0.014624 | 3 / 0.016125 |
| `seon.env/scope` | 2 / 0.015082 | 4 / 0.039292 | 4 / 0.037583 | 4 / 0.040832 |
| `seon.env/supplied-agent-id` | — | 1 / 0.003084 | 1 / 0.003208 | 1 / 0.003292 |
| `seon.error/declared-output-validators` | — | 4 / 0.755083 | 4 / 0.696625 | 4 / 1.039374 |
| `seon.error/declared-schema-keys` | — | 3 / 2.242251 | 2 / 1.652333 | — |
| `seon.error/observation-selector` | 1 / 0.002542 | 1 / 0.002875 | 1 / 40.412500 | 1 / 0.002917 |
| `seon.error/occurred-since` | 1 / 0.617000 | 1 / 0.548042 | 1 / 40.929417 | 1 / 0.929708 |
| `seon.fn.analyzer/analyze` | — | 1 / 6.327875 | 1 / 5.396542 | 1 / 6.152459 |
| `seon.fn.analyzer/invoke-kondo` | — | 1 / 5.739667 | 1 / 4.884375 | 1 / 5.578417 |
| `seon.fn.analyzer/parsed-facts` | — | 1 / 0.098542 | 1 / 0.068500 | 1 / 0.093666 |
| `seon.fn.analyzer/program-prelude` | — | 1 / 0.006875 | 1 / 0.033750 | 1 / 0.038417 |
| `seon.fn.analyzer/referenced-program-namespaces` | — | 1 / 0.091875 | 1 / 0.110417 | 1 / 0.052917 |
| `seon.fn.analyzer/unanalyzed-namespaces` | — | 1 / 0.178333 | 1 / 0.157625 | 1 / 0.188000 |
| `seon.fn.analyzer/under?` | — | 1 / 0.006208 | 1 / 0.004834 | 1 / 0.004208 |
| `seon.fn.analyzer/with-namespace-clauses` | — | 1 / 0.014292 | 1 / 0.013375 | 1 / 0.020750 |
| `seon.fn.signature/declaration-parts` | — | 1 / 0.048833 | 1 / 0.008042 | 1 / 0.010625 |
| `seon.fn.signature/function-signatures` | — | 1 / 0.086667 | 1 / 0.033709 | 1 / 0.042084 |
| `seon.fn/assert-seed-contracts!` | — | 1 / 0.010125 | 1 / 0.006667 | 1 / 0.010125 |
| `seon.fn/call-reach` | — | 1 / 21.147041 | 2 / 0.994042 | 1 / 20.775000 |
| `seon.fn/declared-callers` | — | 1 / 16.589042 | 3 / 12.597042 | 1 / 18.413959 |
| `seon.fn/declared-reference-edges` | — | 1 / 15.852417 | 3 / 11.710334 | 1 / 17.439875 |
| `seon.fn/exact-source` | — | 2 / 0.011959 | 3 / 0.010125 | 3 / 0.015833 |
| `seon.fn/function-definition?` | — | 3 / 0.005333 | 4 / 0.004543 | 4 / 0.006625 |
| `seon.fn/gate-set` | — | 1 / 21.149667 | 1 / 0.451917 | 1 / 20.777334 |
| `seon.fn/host-bound-callers` | — | 1 / 1.626250 | 1 / 0.784583 | 1 / 1.911417 |
| `seon.fn/host-bound-declaration?` | — | 1 / 0.005542 | 1 / 0.005125 | 1 / 0.005333 |
| `seon.fn/identity-names` | — | 3 / 2.196168 | 8 / 2.442208 | 3 / 2.900666 |
| `seon.fn/interpreted-namespaces` | — | 1 / 1.608834 | 1 / 0.769375 | 1 / 1.894333 |
| `seon.fn/invocation-attributes` | — | 3 / 0.003208 | 3 / 0.003292 | 3 / 0.004084 |
| `seon.fn/invocations-by-definition` | — | 2 / 0.012124 | 2 / 0.010833 | 2 / 0.011084 |
| `seon.fn/invoked-attributes` | — | 1 / 0.010875 | 1 / 0.010000 | 1 / 0.010917 |
| `seon.fn/namespace-context` | — | 1 / 0.012500 | 2 / 0.011333 | 2 / 0.014334 |
| `seon.fn/namespace-row` | — | 1 / 0.127333 | 2 / 0.144167 | 2 / 0.346583 |
| `seon.fn/population-targets` | — | 1 / 0.001625 | 1 / 0.001083 | 1 / 0.001625 |
| `seon.fn/program-namespaces` | — | 1 / 0.500958 | 1 / 0.176083 | 1 / 0.491291 |
| `seon.fn/program-targets` | — | 1 / 0.003583 | 1 / 0.003458 | 1 / 0.004041 |
| `seon.fn/read-or-throw!` | — | 7 / 0.006917 | 20 / 0.013125 | 7 / 0.006584 |
| `seon.fn/reverse-closure` | — | — | 1 / 50.935916 | — |
| `seon.fn/source-rows` | — | 1 / 24.691208 | 1 / 17.201417 | 1 / 25.721166 |
| `seon.fn/task-links` | — | 2 / 0.028792 | 3 / 0.022084 | 3 / 0.042916 |
| `seon.fn/unresolved-macro-refusal` | — | 1 / 0.001917 | 1 / 0.001958 | 1 / 0.001833 |
| `seon.fn/var-row` | — | 1 / 0.310125 | 1 / 0.261792 | 1 / 0.261833 |
| `seon.fn/writes-by-writer` | — | 1 / 0.004583 | 1 / 0.004042 | 1 / 0.004542 |
| `seon.id/digest` | 13 / 0.194917 | 20 / 0.471417 | 21 / 0.348751 | 19 / 0.543623 |
| `seon.id/evaluation` | 11 / 0.158834 | 11 / 0.179500 | 11 / 0.137000 | 11 / 0.306831 |
| `seon.id/id` | 13 / 0.184044 | 23 / 0.502126 | 24 / 0.364750 | 22 / 0.619210 |
| `seon.id/printed` | 13 / 0.071460 | 21 / 0.197167 | 22 / 0.154956 | 20 / 0.201123 |
| `seon.id/sha-256` | 13 / 0.099332 | 22 / 0.250708 | 23 / 0.171670 | 21 / 0.338621 |
| `seon.id/symbol-in` | 1 / 0.003458 | 1 / 0.003542 | 1 / 0.003500 | 1 / 0.004583 |
| `seon.instrument/original-interpreted` | — | 4 / 0.008542 | 7 / 0.010833 | 6 / 0.011751 |
| `seon.instrument/wrap-interpreted` | — | 4 / 0.432040 | 7 / 0.630708 | 6 / 0.715710 |
| `seon.issue/exhaust-tx` | 1 / 0.286042 | 1 / 4.815125 | 1 / 0.285666 | 1 / 0.561833 |
| `seon.issue/settle-call` | 1 / 0.217208 | 1 / 4.875792 | 1 / 0.209458 | 1 / 0.641958 |
| `seon.plan/settle-call` | 1 / 0.646375 | 1 / 5.211583 | 1 / 0.442000 | 1 / 1.773958 |
| `seon.print/default-options` | 1 / 0.002208 | 1 / 0.004083 | 1 / 0.002292 | 1 / 0.002000 |
| `seon.print/elision` | 1 / 0.011333 | 1 / 0.028792 | 1 / 0.012084 | 1 / 0.013416 |
| `seon.print/emit-both` | 1 / 0.034541 | 1 / 0.094459 | 1 / 0.030417 | 1 / 0.040125 |
| `seon.print/node?` | 5 / 0.008000 | 105 / 0.052873 | 105 / 0.041957 | 105 / 0.075621 |
| `seon.print/print-number?` | 4 / 0.001125 | 100 / 0.009758 | 100 / 0.009917 | 100 / 0.031007 |
| `seon.problems/form-problem` | 1 / 0.849500 | 1 / 1.019292 | 1 / 0.913250 | 1 / 1.150042 |
| `seon.profile/accumulator?` | — | 4 / 0.002500 | 7 / 0.003459 | 6 / 0.003249 |
| `seon.profile/adder?` | — | 12 / 0.004416 | 21 / 0.005958 | 18 / 0.006168 |
| `seon.profile/cell` | — | 1 / 0.013167 | 2 / 0.017291 | 1 / 0.010250 |
| `seon.profile/reusable?` | — | 4 / 0.023918 | 7 / 0.032291 | 6 / 0.037460 |
| `seon.program/base-context-injected-symbols` | — | 1 / 1.595083 | 2 / 1.413292 | 1 / 1.879375 |
| `seon.program/canonical-row` | — | 8 / 0.125168 | 18 / 0.512129 | 14 / 0.449127 |
| `seon.program/contract-facts` | — | 1 / 0.402167 | 1 / 0.287625 | 1 / 0.376000 |
| `seon.program/declaration-row` | — | 3 / 5.696375 | 3 / 4.687750 | 3 / 7.451917 |
| `seon.program/definition-digest` | — | 9 / 0.674582 | 11 / 0.609083 | 9 / 0.926042 |
| `seon.program/deletion-row` | 1 / 0.002208 | 1 / 0.002416 | 1 / 0.002000 | 1 / 0.002292 |
| `seon.program/edn-round-trip-symbol?` | — | 24 / 0.096084 | 24 / 0.058791 | 4 / 0.038502 |
| `seon.program/exact-replacement-tx` | — | — | 2 / 0.067917 | — |
| `seon.program/program-attributes` | — | — | 1 / 10.728084 | — |
| `seon.program/row-identity` | — | 5 / 0.010833 | 33 / 0.030959 | 5 / 0.012459 |
| `seon.program/shape` | — | 5 / 0.040250 | 6 / 0.013250 | 5 / 0.012667 |
| `seon.program/shapes` | — | — | 12 / 0.364122 | 8 / 0.265957 |
| `seon.program/shapes-in` | — | 8 / 13.552499 | 9 / 13.227209 | 6 / 13.072751 |
| `seon.program/source-family?` | — | 28384 / 2.896251 | 31932 / 2.794617 | 21288 / 2.479272 |
| `seon.program/with-contract-facts` | — | 1 / 0.492833 | 1 / 0.361750 | 1 / 0.466417 |
| `seon.render.block/surface-id` | — | — | — | 2 / 0.048042 |
| `seon.render.hiccup/->string` | — | — | — | 2 / 1.950416 |
| `seon.render.hiccup/hiccup?` | 17 / 0.007502 | 17 / 0.012085 | 17 / 0.006251 | 5581 / 8.718773 |
| `seon.render.hiccup/raw` | — | — | — | 2 / 0.006042 |
| `seon.render.hiccup/raw?` | 7 / 0.001417 | 7 / 0.002001 | 7 / 0.001084 | 2387 / 0.187733 |
| `seon.render.hiccup/shorthand` | — | — | — | 1294 / 0.319727 |
| `seon.render.route/path` | — | — | — | 60 / 0.646752 |
| `seon.render.value/breaks?` | 1 / 0.001584 | 1 / 0.005500 | 1 / 0.001167 | 1 / 0.001583 |
| `seon.render.value/chain` | 1 / 0.001917 | 1 / 0.003667 | 1 / 0.001625 | 1 / 0.002042 |
| `seon.render.value/charge` | 2 / 0.002084 | 2 / 0.004833 | 2 / 0.001834 | 2 / 0.002084 |
| `seon.render.value/classify` | 1 / 0.004292 | 1 / 0.014208 | 1 / 0.004042 | 1 / 0.024000 |
| `seon.render.value/declared` | 1 / 0.002917 | 1 / 0.008625 | 1 / 0.002333 | 1 / 0.003208 |
| `seon.render.value/extras` | 1 / 0.001333 | 1 / 0.011750 | 1 / 0.001584 | 1 / 0.001750 |
| `seon.render.value/flat-width` | 3 / 0.002083 | 3 / 0.013792 | 3 / 0.001792 | 3 / 0.002374 |
| `seon.render.value/keyword-lookup?` | 1 / 0.000917 | 1 / 0.002042 | 1 / 0.000833 | 57 / 0.019626 |
| `seon.render.value/leaf-width` | 1 / 0.011208 | 1 / 0.003542 | 1 / 0.003375 | 1 / 0.018583 |
| `seon.render.value/legend-text` | 1 / 0.025084 | 1 / 0.033792 | 1 / 0.015875 | 1 / 0.016583 |
| `seon.render.value/make-node` | 1 / 0.026542 | 1 / 0.059167 | 1 / 0.016500 | 1 / 0.054084 |
| `seon.render.value/more?` | 3 / 0.001500 | 3 / 0.005750 | 3 / 0.001541 | 3 / 0.001874 |
| `seon.render.value/node-id` | 1 / 0.016334 | 1 / 0.033792 | 1 / 0.014375 | 1 / 0.020167 |
| `seon.render.value/prepare` | 2 / 2.791125 | 2 / 3.169791 | 2 / 2.092875 | 2 / 3.347874 |
| `seon.render.value/render-ai-data` | 1 / 0.002584 | 1 / 0.002250 | 1 / 0.002125 | 1 / 0.002333 |
| `seon.render.value/schedule` | 1 / 0.001917 | 1 / 0.009333 | 1 / 0.001208 | 1 / 0.001833 |
| `seon.render.value/select-nodes` | 1 / 0.056958 | 1 / 0.146583 | 1 / 0.041375 | 1 / 0.088959 |
| `seon.render.value/settle` | 1 / 0.015208 | 1 / 0.056708 | 1 / 0.011833 | 1 / 0.017583 |
| `seon.render.value/tree` | 1 / 0.006917 | 1 / 0.007042 | 1 / 0.005208 | 1 / 0.008209 |
| `seon.render.value/value-identity` | — | — | — | 56 / 9.253415 |
| `seon.render.walk/definitions-first` | — | — | — | 2 / 0.032791 |
| `seon.render.walk/finished-graph` | — | — | — | 2 / 1.087500 |
| `seon.render.walk/graph` | — | — | — | 2 / 7.313209 |
| `seon.render.walk/graph-structure` | — | — | — | 2 / 0.038709 |
| `seon.render.walk/joined` | — | — | — | 10 / 5.780457 |
| `seon.render.walk/level-step` | — | — | — | 2 / 5.413833 |
| `seon.render.walk/owning-schemas` | — | — | — | 6 / 1.411917 |
| `seon.render.web/agent-namespace` | — | — | — | 4 / 0.669542 |
| `seon.render.web/context-graph-model` | — | — | — | 2 / 0.372168 |
| `seon.render.web/graph-debug-href` | — | — | — | 34 / 0.466419 |
| `seon.render.web/graph-debug-html` | — | — | — | 2 / 56.727543 |
| `seon.render.web/graph-node-label` | — | — | — | 28 / 0.912376 |
| `seon.render.web/graph-node-name` | — | — | — | 82 / 0.728086 |
| `seon.render.web/handler` | — | — | — | 2 / 2.893959 |
| `seon.render.web/inclusion-paths` | — | — | — | 2 / 0.092416 |
| `seon.render.web/mult?` | — | — | — | 4 / 0.010166 |
| `seon.render.web/shell` | — | — | — | 2 / 2.365166 |
| `seon.render/agent-render-profile` | 1 / 0.006583 | 1 / 0.008000 | 1 / 0.005958 | 57 / 0.207415 |
| `seon.render/branch-scope` | — | — | — | 2 / 0.008000 |
| `seon.render/call-cache-evidence` | — | — | — | 56 / 10.144708 |
| `seon.render/call-with-walk-context` | 1 / 2.353375 | 1 / 120.342458 | 1 / 358.112417 | 1 / 112.376000 |
| `seon.render/head-function` | 1 / 0.013458 | 1 / 0.024666 | 1 / 0.016542 | 1 / 0.062458 |
| `seon.render/render-call` | — | — | — | 56 / 45.922041 |
| `seon.render/request-profile` | 3 / 0.332249 | 3 / 0.267875 | 3 / 0.214042 | 115 / 11.296920 |
| `seon.render/request-projection` | 1 / 0.002625 | 1 / 0.003791 | 1 / 0.003208 | 1 / 0.002500 |
| `seon.render/same-committed-database?` | — | — | — | 56 / 0.093213 |
| `seon.render/shared-cache` | — | — | — | 2 / 0.017333 |
| `seon.render/target-profile` | — | — | — | 56 / 9.357376 |
| `seon.render/viewer` | 1 / 0.887166 | 1 / 0.970917 | 1 / 0.753041 | 1 / 1.090958 |
| `seon.render/viewing-namespace` | 1 / 0.081208 | 1 / 0.144000 | 1 / 0.065333 | 1 / 0.121375 |
| `seon.schema.datahike/compiled-storage` | 52 / 0.194876 | 2654 / 2.635495 | 9384 / 9.061718 | 62 / 0.250794 |
| `seon.schema.datahike/declared-storable?` | — | — | 9246 / 38.392568 | — |
| `seon.schema.datahike/decode-attribute-value-in` | 4 / 0.412458 | 4 / 0.467626 | 8 / 0.813418 | 4 / 0.502875 |
| `seon.schema.datahike/edn-encoded-attr-in?` | 60 / 0.218960 | 2668 / 3.158127 | 152 / 0.514497 | 70 / 0.290415 |
| `seon.schema.datahike/encode-transaction-in` | 1 / 0.018375 | 2 / 0.140959 | 2 / 0.110333 | 2 / 0.025500 |
| `seon.schema.datahike/malli->datahike-attr-in` | — | 2 / 0.018666 | 9248 / 37.089626 | — |
| `seon.schema.datahike/storable-attribute-in?` | — | — | 9354 / 40.185851 | — |
| `seon.schema.datahike/storable-properties-in` | — | — | 3548 / 6.329952 | — |
| `seon.schema.datahike/storage-schema` | — | 2766 / 0.719881 | 364 / 0.071250 | — |
| `seon.schema.datahike/value-schema` | — | 228 / 0.089044 | 334 / 0.128290 | — |
| `seon.schema.edn/packaged-forms` | 2 / 0.176626 | 7 / 0.506790 | 22 / 0.869458 | 73 / 1.986085 |
| `seon.schema.edn/packaged-population` | 2 / 0.172209 | 7 / 0.491291 | 22 / 0.847919 | 73 / 1.932002 |
| `seon.schema.internal/assert-complete-schema!` | — | 16 / 0.145665 | — | — |
| `seon.schema.internal/assert-error-declaration!` | — | 16 / 0.019957 | — | — |
| `seon.schema.internal/entity-entries` | — | 844 / 5.663894 | 630 / 3.001949 | 36 / 0.439917 |
| `seon.schema.internal/entity-maps` | — | 18926 / 11.134849 | 9689 / 5.333274 | 144 / 0.151085 |
| `seon.schema.internal/entity-properties` | — | 2252 / 2.366498 | 4189 / 3.836949 | 36 / 0.068289 |
| `seon.schema.internal/entity-schema?` | — | 7144 / 5.480859 | 54 / 0.094626 | 36 / 0.110083 |
| `seon.schema.internal/extends-schema?` | — | 1298 / 0.755386 | 653 / 0.368720 | 8 / 0.025835 |
| `seon.schema.internal/fold-walk` | — | 42 / 0.198878 | 2 / 0.015875 | 3 / 0.110001 |
| `seon.schema.internal/nilable-union?` | — | 24 / 0.017043 | — | — |
| `seon.schema.internal/reference-id` | — | 22370 / 1.994739 | 11416 / 1.015509 | 162 / 0.023964 |
| `seon.schema.internal/same-scoped-schema?` | — | 218 / 0.106169 | 108 / 0.052375 | — |
| `seon.schema/assert-complete-contract!` | — | 8 / 1.323083 | — | — |
| `seon.schema/build-projection` | — | 2 / 13.860750 | — | 1 / 7.666958 |
| `seon.schema/byte-array?` | — | 14 / 0.005625 | 14 / 0.007502 | 14 / 0.005538 |
| `seon.schema/call-with-forms` | 1 / 0.015208 | 2 / 0.135709 | 2 / 0.105041 | 2 / 0.020916 |
| `seon.schema/call-with-projection` | 2 / 4.133167 | 27 / 1342.338748 | 21 / 494.196583 | 61 / 76.970293 |
| `seon.schema/call-with-projection-state` | 4 / 225.862875 | 4 / 4065.715583 | 4 / 2084.126291 | 4 / 693.449334 |
| `seon.schema/canonical-data-string` | — | 23 / 0.385330 | 8 / 0.223376 | 8 / 0.345249 |
| `seon.schema/canonical-definition` | — | 1 / 0.113542 | 1 / 0.084166 | 1 / 0.072167 |
| `seon.schema/canonical-value-string` | — | 658 / 0.930968 | 611 / 0.647631 | 483 / 1.105736 |
| `seon.schema/compilable-form` | — | 20 / 0.480082 | 12 / 0.267289 | 13 / 0.421917 |
| `seon.schema/compiled-references` | — | 26 / 1.843374 | 2 / 0.137834 | 3 / 0.329208 |
| `seon.schema/declaration-changes` | — | 32 / 3.384916 | 24 / 1.269873 | 28 / 2.401707 |
| `seon.schema/deleted-predicate-functions` | — | 2 / 35.269208 | — | 1 / 20.979667 |
| `seon.schema/dependent-schema-keys` | — | 8 / 0.018585 | — | 1 / 0.003000 |
| `seon.schema/explain-candidate-value` | — | 2 / 0.017916 | 3 / 0.012708 | 3 / 0.030166 |
| `seon.schema/fingerprint-with` | — | 48 / 0.201623 | — | 6 / 0.029834 |
| `seon.schema/identity-attr?` | — | 3604 / 0.908690 | — | — |
| `seon.schema/identity-only-descriptors-in` | — | — | 1 / 2.841833 | — |
| `seon.schema/identity-only-projector` | — | 25 / 0.019039 | 25 / 2.863620 | 25 / 0.026625 |
| `seon.schema/incremental-base?` | — | 4 / 0.030125 | — | 2 / 0.019208 |
| `seon.schema/load-projection` | — | 2 / 70.437125 | — | 1 / 51.415875 |
| `seon.schema/malli-form?` | — | 7 / 0.279041 | 7 / 0.261333 | 7 / 0.329625 |
| `seon.schema/population-options` | — | 6 / 0.070334 | 6 / 0.067833 | 6 / 0.072917 |
| `seon.schema/predicate-functions-in` | — | 19 / 0.105167 | 17 / 0.075668 | 18 / 0.092375 |
| `seon.schema/projection-admissions` | — | 2 / 5.435709 | — | 1 / 9.240708 |
| `seon.schema/projection-build` | — | 2 / 13.839833 | — | 1 / 7.653833 |
| `seon.schema/projection-cache-value` | 108 / 0.079120 | 7895 / 50.621268 | 1713 / 69.865430 | 1133 / 1.712503 |
| `seon.schema/projection-explainer` | — | 2 / 0.011333 | 3 / 0.007542 | 3 / 0.020875 |
| `seon.schema/projection-from-database` | — | — | 1 / 0.004500 | — |
| `seon.schema/projection-from-rows` | — | 2 / 63.870416 | — | 1 / 45.943333 |
| `seon.schema/projection-registry` | — | 8 / 15.833374 | — | 1 / 2.798583 |
| `seon.schema/projection-rows` | — | 4 / 6.543542 | — | 2 / 5.455625 |
| `seon.schema/projection-validator` | 6 / 0.012874 | 18 / 0.057332 | 19 / 0.034582 | 6 / 0.015042 |
| `seon.schema/projection-with-declarations` | — | 8 / 46.365667 | 6 / 1.404709 | 7 / 9.183751 |
| `seon.schema/projection-with-function-contract` | — | 6 / 32.852250 | 6 / 1.701376 | 6 / 1.933252 |
| `seon.schema/set-changes` | — | 16 / 0.016080 | 12 / 0.008543 | 14 / 0.013166 |
| `seon.schema/sha-256` | — | 1 / 0.021375 | 1 / 0.007291 | 1 / 0.013958 |
| `seon.schema/structural-schema` | — | 20 / 0.121958 | 12 / 0.083793 | 13 / 0.128626 |
| `seon.schema/validate-contracts!` | — | 8 / 1.453917 | — | 1 / 0.011584 |
| `seon.schema/validate-declarations!` | — | 8 / 4.453708 | — | 1 / 0.663250 |
| `seon.schema/widen-component-children` | — | 20 / 0.138252 | 12 / 0.075458 | 13 / 0.091167 |
| `seon.schema/with-predicate-functions` | — | 8 / 0.055831 | — | 1 / 0.013458 |
| `seon.sci.admit/admit-value` | — | 25 / 0.213209 | 25 / 2.987249 | 25 / 0.203044 |
| `seon.sci.admit/admit*` | — | 25 / 0.180168 | 25 / 2.957460 | 25 / 0.163332 |
| `seon.sci.admit/interrupt-fn?` | — | 50 / 0.006877 | 50 / 0.005793 | 50 / 0.006874 |
| `seon.sci.admit/result-handle` | 1 / 0.005500 | 1 / 0.005666 | 1 / 0.005458 | 1 / 0.005750 |
| `seon.sci.admit/semantic-value` | — | 25 / 0.057460 | 25 / 0.028876 | 25 / 0.031335 |
| `seon.sci.eval/accept-candidate!` | — | 1 / 6.589125 | 1 / 0.912833 | 1 / 1.168417 |
| `seon.sci.eval/acquire-program!` | — | — | 1 / 210.122125 | — |
| `seon.sci.eval/acquire!` | — | 1 / 0.180834 | 2 / 277.650250 | 1 / 0.220750 |
| `seon.sci.eval/acquired-database?` | 1 / 0.094750 | 2 / 0.306708 | 3 / 0.338416 | 2 / 0.354458 |
| `seon.sci.eval/acquired-program` | 1 / 0.005125 | 2 / 0.017833 | 2 / 0.016917 | 2 / 0.073625 |
| `seon.sci.eval/acquisition-attributes` | 1 / 0.016166 | 2 / 0.041916 | 5 / 11.259623 | 2 / 0.042875 |
| `seon.sci.eval/admit-program-change!` | 1 / 0.178625 | 1 / 36.933208 | 1 / 21.411167 | 1 / 31.597833 |
| `seon.sci.eval/agent-namespace` | 1 / 0.170167 | 1 / 0.247625 | 1 / 0.124334 | 1 / 0.197333 |
| `seon.sci.eval/analyzed-rows` | — | 1 / 27.381458 | 1 / 18.736625 | 1 / 28.335916 |
| `seon.sci.eval/base-ctx` | — | — | 1 / 222.263375 | — |
| `seon.sci.eval/base-ctx-key` | — | — | 1 / 11.251916 | — |
| `seon.sci.eval/bind-result!` | 2 / 0.036207 | 2 / 0.032250 | 2 / 0.028958 | 2 / 0.059833 |
| `seon.sci.eval/build-base-ctx` | — | — | 1 / 0.835000 | — |
| `seon.sci.eval/call-detecting-program-change` | 2 / 4.390208 | 2 / 79.080291 | 2 / 608.833708 | 2 / 68.856709 |
| `seon.sci.eval/commit-id-of` | 5 / 0.018917 | 9 / 0.036794 | 15 / 0.049375 | 9 / 0.035210 |
| `seon.sci.eval/committed-row?` | — | 1 / 8.957667 | 1 / 2.173583 | 1 / 3.564791 |
| `seon.sci.eval/context-capture` | 1 / 0.113083 | 1 / 0.087167 | 1 / 0.056333 | 1 / 0.153500 |
| `seon.sci.eval/context-restoration` | 1 / 0.011042 | 1 / 0.010292 | 1 / 0.010208 | 1 / 0.010875 |
| `seon.sci.eval/context-state?` | 6 / 0.001458 | 6 / 0.001416 | 6 / 0.001290 | 6 / 0.001625 |
| `seon.sci.eval/copy-base-ctx` | — | — | 1 / 0.005458 | — |
| `seon.sci.eval/copy-host-var` | — | — | 4110 / 1.144700 | — |
| `seon.sci.eval/ctx?` | 32 / 0.008669 | 182 / 0.044633 | 4163 / 0.353428 | 304 / 0.097833 |
| `seon.sci.eval/database-effective-config` | — | 4 / 3.578291 | 7 / 3.839000 | 6 / 3.987417 |
| `seon.sci.eval/definition-row` | — | 1 / 6.549750 | 1 / 2.036500 | 1 / 1.651083 |
| `seon.sci.eval/derive-base-ctx` | — | — | 1 / 210.984000 | — |
| `seon.sci.eval/disposable-copy` | — | 1 / 30.640208 | 1 / 34.923167 | 1 / 41.969958 |
| `seon.sci.eval/evaluate` | 1 / 2.328417 | 1 / 39.708708 | 1 / 304.542209 | 1 / 34.569333 |
| `seon.sci.eval/evaluate-candidate` | — | 1 / 52.390209 | 1 / 51.913083 | 1 / 55.488084 |
| `seon.sci.eval/fork-cluster-ctx` | — | 2 / 4.301709 | 2 / 9.513957 | 2 / 11.055792 |
| `seon.sci.eval/fork-for-turn` | 1 / 4.145333 | 1 / 5.007667 | 1 / 3.692917 | 1 / 5.765416 |
| `seon.sci.eval/form-read-sink` | 1 / 0.001625 | 1 / 0.001417 | 1 / 0.001250 | 1 / 0.001375 |
| `seon.sci.eval/function-digests` | — | — | 2 / 83.024792 | — |
| `seon.sci.eval/held-intern` | — | 1 / 0.008542 | 1 / 0.009875 | 1 / 0.010042 |
| `seon.sci.eval/host-bound-row?` | — | 1 / 0.001833 | 2 / 0.003874 | 1 / 0.001875 |
| `seon.sci.eval/install-caller-host-bindings!` | — | — | 1 / 0.550542 | — |
| `seon.sci.eval/install-evaluated-rows!` | — | 1 / 16.586167 | 1 / 30.936417 | 1 / 80.104709 |
| `seon.sci.eval/install-row!` | — | 1 / 8.742750 | 2 / 5.238792 | 1 / 77.029250 |
| `seon.sci.eval/installation-covers-program-change?` | — | 1 / 0.072125 | 1 / 25.919125 | 1 / 0.033500 |
| `seon.sci.eval/loaded-basis` | 1 / 0.006416 | 2 / 0.020125 | 4 / 0.022540 | 2 / 0.019792 |
| `seon.sci.eval/loaded-commit-id` | — | — | 1 / 0.353166 | — |
| `seon.sci.eval/loaded-program` | — | — | 1 / 4.709459 | — |
| `seon.sci.eval/loaded-program-current?` | 1 / 0.019208 | 2 / 0.062459 | 3 / 0.057416 | 2 / 0.087667 |
| `seon.sci.eval/loaded-source` | 1 / 0.008791 | 2 / 0.034167 | 4 / 0.058918 | 2 / 0.032625 |
| `seon.sci.eval/native-mutation` | — | — | 4 / 0.002584 | — |
| `seon.sci.eval/overridden-row?` | — | 1 / 0.047542 | — | — |
| `seon.sci.eval/program-basis` | 3 / 0.032125 | 8 / 0.103582 | 16 / 0.187123 | 6 / 0.115167 |
| `seon.sci.eval/program-change` | — | 1 / 0.032792 | 1 / 0.024542 | 1 / 0.041666 |
| `seon.sci.eval/program-definitions` | 1 / 0.054417 | 1 / 0.032834 | 1 / 0.032166 | 1 / 0.036041 |
| `seon.sci.eval/program-identity` | 3 / 0.042583 | 5 / 0.063625 | 9 / 0.095750 | 5 / 0.059707 |
| `seon.sci.eval/program-value?` | 12 / 0.004957 | 7 / 0.004794 | 10 / 0.005875 | 16 / 0.008168 |
| `seon.sci.eval/projection-state` | — | 3 / 0.067083 | 3 / 0.059751 | 3 / 0.099500 |
| `seon.sci.eval/projection-state?` | 20 / 0.020497 | 49 / 0.045042 | 52 / 0.045501 | 55 / 0.056713 |
| `seon.sci.eval/record-acquisition-refusals!` | — | — | 2 / 0.004125 | — |
| `seon.sci.eval/record-refusals-once!` | — | — | 1 / 0.065625 | — |
| `seon.sci.eval/remember-program-identity!` | 3 / 0.019666 | 6 / 0.040790 | 9 / 0.041915 | 5 / 0.030377 |
| `seon.sci.eval/removed-interns` | 1 / 0.077500 | 1 / 0.086583 | 1 / 0.113583 | 1 / 0.144709 |
| `seon.sci.eval/revision-basis` | 4 / 0.005749 | 9 / 0.012041 | 17 / 0.019079 | 7 / 0.011165 |
| `seon.sci.eval/sci-var?` | 30 / 0.003165 | 20 / 0.003833 | 26 / 0.004043 | 41 / 0.005371 |
| `seon.sci.eval/shown-result` | 1 / 1.429792 | 1 / 1.625458 | 1 / 1.078875 | 1 / 1.724417 |
| `seon.sci.eval/source-offset` | — | 1 / 0.007833 | 1 / 0.005875 | 1 / 0.008125 |
| `seon.sci.eval/top-level-do?` | 2 / 0.002666 | 2 / 0.003958 | 2 / 0.002291 | 2 / 0.002792 |
| `seon.sci.eval/trusted-evaluation` | 1 / 2.203584 | 1 / 39.549583 | 1 / 304.422584 | 1 / 34.438875 |
| `seon.sci.eval/watch-program-identity!` | — | 2 / 0.024792 | 2 / 0.022459 | 2 / 0.040249 |
| `seon.sci.kernel/cache-function!` | — | 3 / 0.026709 | 4 / 0.018000 | 3 / 0.015124 |
| `seon.sci.kernel/cache-program!` | — | — | 1 / 0.007459 | — |
| `seon.sci.kernel/context-options` | — | — | 1 / 0.003583 | — |
| `seon.sci.kernel/context-projection` | — | — | — | 58 / 0.070832 |
| `seon.sci.kernel/ensure-function!` | — | 25 / 0.019291 | 25 / 0.018129 | 25 / 0.023126 |
| `seon.sci.kernel/invoke` | — | 25 / 0.480335 | 25 / 3.213375 | 25 / 0.683916 |
| `seon.sci.kernel/mark-installed!` | — | 3 / 0.011459 | 3966 / 1.356136 | 3 / 0.013084 |
| `seon.sci.kernel/program-function` | — | — | 1 / 0.002291 | — |
| `seon.sci.kernel/program-namespace` | — | — | 1 / 0.002167 | — |
| `seon.sci.kernel/public-functions-in` | 1 / 0.802291 | 1 / 0.822458 | 1 / 0.684541 | 1 / 0.966084 |
| `seon.sci.kernel/with-arm` | 1 / 2.242625 | 26 / 40.042664 | 26 / 307.643540 | 26 / 35.075670 |
| `seon.sci.reader/read` | 3 / 0.365833 | 3 / 0.567874 | 4 / 0.563958 | 3 / 0.607292 |
| `seon.test.accretion/auto-check` | — | 1 / 4.606208 | 1 / 7.636083 | 1 / 3.450250 |
| `seon.test.accretion/candidate-capabilities` | — | 1 / 0.471083 | 1 / 0.407833 | 1 / 0.862167 |
| `seon.test.accretion/data-contract!` | — | 1 / 0.005291 | 1 / 0.005333 | 1 / 0.007125 |
| `seon.test.accretion/gate-report` | — | 1 / 0.027625 | 1 / 0.025208 | 1 / 0.026958 |
| `seon.test.accretion/non-generatable-advisory` | — | 1 / 0.001459 | 1 / 0.001500 | 1 / 0.001792 |
| `seon.test.accretion/observation` | — | 25 / 0.537958 | 25 / 3.273540 | 25 / 0.772543 |
| `seon.test.accretion/seed-for` | — | 1 / 0.051125 | 1 / 0.035292 | 1 / 0.031417 |
| `seon.test.accretion/supplied-registry` | — | 1 / 3.264833 | 1 / 3.605500 | 1 / 1.205375 |
| `seon.test.runner/class-loader?` | 1 / 0.000959 | 2 / 0.002667 | 2 / 0.002292 | 2 / 0.002208 |
| `seon.turn/agent-dial` | 1 / 0.262166 | 1 / 0.274750 | 1 / 0.254125 | 1 / 0.300333 |
| `seon.turn/arm-turn-completion-backstop!` | 1 / 0.044875 | 1 / 0.045416 | 1 / 0.036458 | 1 / 0.035416 |
| `seon.turn/close-call` | 1 / 0.660166 | 1 / 0.697875 | 1 / 0.675292 | 1 / 0.846459 |
| `seon.turn/close-folded-call` | 1 / 2.000500 | 1 / 41.335708 | 1 / 2.260000 | 1 / 2.408250 |
| `seon.turn/current-run` | 5 / 0.185499 | 5 / 0.220583 | 5 / 0.211209 | 5 / 0.237042 |
| `seon.turn/disposition` | 3 / 0.002833 | 3 / 0.003708 | 3 / 0.004083 | 3 / 0.004625 |
| `seon.turn/disposition-rule-error` | 1 / 0.133958 | 1 / 0.208125 | 1 / 0.181584 | 1 / 0.292917 |
| `seon.turn/evaluate-sources` | 1 / 3.442208 | 1 / 1566.097583 | 1 / 438.872666 | 1 / 120.512584 |
| `seon.turn/evaluation-facts` | 1 / 0.011875 | 1 / 0.019792 | 1 / 0.018041 | 1 / 0.022208 |
| `seon.turn/fault-window` | 1 / 1.048708 | 1 / 1.026750 | 1 / 41.342083 | 1 / 1.761792 |
| `seon.turn/final-namespace-digests` | 1 / 0.008833 | 1 / 0.011166 | 1 / 0.011167 | 1 / 0.036542 |
| `seon.turn/gate-function-install` | 1 / 0.011625 | 1 / 80.616250 | 1 / 53.553500 | 1 / 77.788334 |
| `seon.turn/namespace-rows` | 3 / 0.253083 | 3 / 0.419459 | 3 / 0.243874 | 3 / 0.392125 |
| `seon.turn/next-id` | 1 / 0.561542 | 1 / 0.672917 | 1 / 0.903125 | 1 / 0.662958 |
| `seon.turn/open-call` | 3 / 1.046625 | 3 / 1.146000 | 3 / 1.103625 | 3 / 1.485042 |
| `seon.turn/open-for-agent` | 5 / 1.228458 | 5 / 1.657375 | 5 / 1.302293 | 4 / 1.407666 |
| `seon.turn/open?` | 2 / 0.002583 | 2 / 0.002708 | 2 / 0.003125 | 2 / 0.003458 |
| `seon.turn/opening-db` | — | — | 1 / 0.269625 | — |
| `seon.turn/phase` | 5 / 109.395499 | 5 / 1984.976792 | 5 / 854.318458 | 5 / 242.602793 |
| `seon.turn/planner-scoped-attempt?` | 1 / 0.112208 | 1 / 0.155083 | 1 / 0.119541 | 1 / 0.172709 |
| `seon.turn/receipt-identity` | 10 / 0.160709 | 10 / 0.181039 | 10 / 0.137709 | 10 / 0.298665 |
| `seon.turn/receipt-settle-batch-call` | 1 / 0.128875 | — | — | — |
| `seon.turn/receipt-settle-batch-tx` | 1 / 0.003292 | 1 / 0.007875 | 1 / 0.013875 | 1 / 0.009708 |
| `seon.turn/receipt-settle-call` | 1 / 0.112292 | 1 / 7.558708 | 1 / 62.999000 | 1 / 3.044584 |
| `seon.turn/receipt-settle-tx` | — | 1 / 0.003667 | 1 / 0.006375 | 1 / 0.004834 |
| `seon.turn/refuse-closed-admission!` | 3 / 0.061750 | 3 / 0.057500 | 3 / 0.062707 | 3 / 0.063291 |
| `seon.turn/require-open-run` | 1 / 0.059625 | 1 / 0.086833 | 1 / 0.086333 | 1 / 0.097000 |
| `seon.turn/resume-turn` | 1 / 110.438792 | 1 / 2011.643250 | 1 / 888.386875 | 1 / 327.910000 |
| `seon.turn/row-tx` | — | 2 / 14.816584 | 2 / 65.501000 | 2 / 6.967293 |
| `seon.turn/settle-batch!` | 1 / 100.759542 | 1 / 413.074166 | 1 / 410.713625 | 1 / 115.748875 |
| `seon.turn/settled-value` | — | 1 / 1444.562666 | 1 / 79.803417 | 1 / 6.548208 |
| `seon.turn/settlement-projection` | 1 / 0.004708 | 1 / 0.059833 | 1 / 0.051084 | 1 / 0.074292 |
| `seon.turn/stage-reply!` | 1 / 0.100416 | 1 / 0.097042 | 1 / 0.086458 | 1 / 0.087959 |
| `seon.turn/step` | 1 / 112.184583 | 1 / 2013.294541 | 1 / 889.811709 | 1 / 329.944958 |
| `seon.turn/submission-open-tx` | 1 / 0.004709 | 1 / 0.002916 | 1 / 0.002500 | 1 / 0.003000 |
| `seon.turn/system-run-call` | 2 / 1.536501 | 2 / 1.944708 | 2 / 1.786542 | 2 / 2.676459 |
| `seon.turn/system-run-plan` | 3 / 2.149043 | 3 / 2.480084 | 3 / 2.228459 | 3 / 3.340166 |
| `seon.turn/terminal?` | 2 / 0.002291 | 2 / 0.003375 | 2 / 0.003417 | 2 / 0.002667 |
| `seon.turn/turn` | 1 / 110.849083 | 1 / 2012.034959 | 1 / 888.711125 | 1 / 328.457875 |
| `seon.turn/unbound-value?` | 2 / 0.004459 | 2 / 0.004792 | 2 / 0.004917 | 2 / 0.006292 |
| `seon.turn/with-fault-windows` | 1 / 1.061125 | 1 / 1.043709 | 1 / 41.384333 | 1 / 1.795584 |
| `seon.turn/with-settled` | — | 1 / 1444.567000 | 1 / 79.807542 | 1 / 6.553958 |
| `seon.turn/with-submission` | 1 / 0.004750 | 1 / 0.007875 | 1 / 0.018709 | 1 / 0.015125 |
| `user/f` (SCI) | — | 25 / 0.015624 | 25 / 0.016376 | 25 / 0.017500 |

## Completion

The branch tool returned
`{:seon.store/branch :eval-trace-20260926, :seon.dev.mcp/retired? true}`.
All owned JFR recordings were stopped and closed. Raw recordings are preserved
as diagnostic evidence. No source or test files were changed; source/test line
delta is **0 / 0**. No tests, reload, restart, reset, worktree, adoption,
publication or merge was used to produce this report.

The unresolved measurement boundary is explicit: exact self time and exclusive
per-run armed counts are unavailable from the installed telemetry, and the
sub-second target budgets are estimates. The large arity-base miss, the
durability wait, the repeated population scans and the version mismatch are
supported by live measurements, source and history.
