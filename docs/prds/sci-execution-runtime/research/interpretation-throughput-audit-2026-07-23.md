---
type: research
status: complete
tags: [research, runtime, architecture]
---

# JVM SCI interpretation throughput audit

## Verdict

The all-JVM execution switch is computationally cheap for the workload shape
that dominates Seon's current program artifact, and predictably expensive for
pure corpus loops:

- A DB-query-plus-render glue function took **0.0492 ms** through the guarded
  JVM SCI door versus **0.0384 ms** compiled: **1.28×**, an absolute
  **10.8 µs** gap.
- A validation-heavy function took **0.1820 ms** guarded versus **0.1359 ms**
  compiled: **1.34×**.
- A pure transform over 10,000 elements took **0.5097 ms** guarded versus
  **0.1464 ms** compiled: **3.48×**. The active guard accounts for 59.5% of
  guarded wall time because the interrupt-aware `into` and `reduce` charge
  20,000 safepoints.
- A worst-case 20,000-iteration numeric/string loop took **1.1586 ms** guarded
  versus **0.0949 ms** compiled: **12.20×**. Here interpretation, not the
  guard, dominates: unguarded SCI was still **10.63×** compiled.

The build-emitted `program-rows.edn` contains 2,945 function rows. A complete
source-shape classification (zero unreadable rows) finds **2,055 / 69.8%**
glue-shaped and **890 / 30.2%** compute-shaped. Of the compute-shaped rows,
665 use callback traversals and 225 contain explicit iteration or direct
recursion. The 927 schema'd functions have nearly the same split:
**657 / 70.9% glue** and **270 / 29.1% compute**.

Implication for P6: SCI remains the correct total placement floor. Compiled
leaf routing is a material optimization for the computed 30% candidate set,
especially explicit loops and recursive functions, but it should not tax the
70% glue majority with eager compilation. The execution plan already has the
right inputs for this decision; consumers must not invent a second placement
classifier.

## Honest scope and omissions

The JVM matrix is complete. It exercises the real host evaluation code:

1. `seon.host.context/build-base!`;
2. registry-provisioned compiled helpers;
3. a retained `seon.host.context/fork-context`;
4. `seon.host.eval/eval-form!`, including parsing, output capture, envelope
   construction, and wire-safe value projection; and
5. `seon.host.guard/call!` with an enforced interpreter-step budget.

The unguarded comparison uses the same base, fork, helpers, and
`eval-form!` envelope path, but sets the SCI context's `:interrupt-fn` to nil.
The installed interrupt-aware core functions then take their native fallback.
This isolates the active guard and its door reset/finally work without
replacing the host evaluator.

The compiled JVM result is the requested ceiling: a direct compiled call to
the same input, Datahike query, render helper, Malli validator, and workload
logic. It deliberately excludes SCI parsing and envelope work.

There are **no Bun numbers** in the result table. The source tree still
contains the historical production self-host owner:
`src/seon/eval.cljs:1155-1203` calls `cljs.js/eval-str`. However, the Bun pod
could not execute on this checkout. Both
`bin/seon cluster status interpbench --edn` and
`bin/seon cluster open interpbench` refused before startup because the
canonical artifact manifest predates the required program-row fields:

```clojure
{:path [:seon.dev.artifact/version]}
{:path [:seon.dev.artifact/program-row-path]
 :type :malli.core/missing-key}
{:path [:seon.dev.artifact/program-row-digest]
 :type :malli.core/missing-key}

```

The shared tree also contained in-flight source from the freeze chain. Building
a fresh canonical artifact from a moving tree would not be a valid benchmark
checkpoint. Per the lane's stop rule, this audit does not substitute old B1/B2
burst figures or a Shadow REPL call for the requested same-form production
self-host comparison. Bun is **absent, not estimated**. Consequently there
are also no fresh-cluster receipt/transport timings; the JVM numbers are the
real in-process host door, not a UDS round trip.

## Dependency ledger and measured state

The benchmark read the maintained sources on both sides of the boundary:

| Boundary | Selected source |
|---|---|
| SCI interpreter | `reference-code/sci` at `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; `sci.core/eval-string*`; generated function checks in `sci.impl.fns`; interrupt-aware materializers in `sci.interrupt` |
| Seon guarded door | `src/seon/host/guard.cljc` blob `0683c6373f7ef23cc218309e5b0e37b74a915e78` |
| Seon host evaluator | `src/seon/host/eval.clj` blob `8e3e7a702db5a4c4ed087efc8c63f5f1167de185` |
| Seon retained context and binding table | `src/seon/host/context.clj` blob `f6dce66a56d0d46d6e789f11bb0157879f12c3d0` |
| Datahike query core | `reference-code/datahike` at `9c356e32a0f2b0afcd41ce5000cba2a575a59a8a` |
| Malli validation | `metosin/malli` 0.20.0; maintained source at `reference-code/malli` `80138076960e7820523b4cb932c5b5d1936d4e7f` |
| Corpus sample | `out/client/program-rows.edn`, 5,487 rows, blob `a1e96e235077554988ed2f230729bf9a0fc8eed1` |

Machine: Apple M5 Max (`Mac17,6`), 128 GiB RAM, macOS 26.5.2/Darwin 25.5,
OpenJDK 26.0.1 aarch64. The final measurement's four owner/harness hashes were
identical before and after the run.

## Workload matrix

Every workload consumes its complete input and returns a small checksum or
count so output serialization does not masquerade as computation.

| Workload | Representative shape | Fixed work |
|---|---|---:|
| Pure transform | `into` transducer, filter, inline map, reduce | 10,000 integers |
| Corpus glue | compiled Datahike `d/q`, branch, compiled render helper | 1,000 stored rows; 77 query results |
| Hot loop | corpus-local `loop/recur`, modular arithmetic, periodic string growth | 20,000 iterations |
| Malli-heavy | interpreted reduce/callback around one compiled nested-map validator | 1,000 maps |

The glue query uses an in-memory Datahike database to isolate call and
interpretation cost. A real writer round trip would add transport/database
latency equally and make the relative SCI tax smaller; this audit does not add
an unmeasured live latency estimate to the table.

“Work tok/s” is a comparison-friendly throughput projection, not a tokenizer
or an operation count. Per invocation, the work-token numerator is the
canonical `seon.ai.tokens/estimate` (`chars / 4`) over the fixed input:
12,222 transform tokens, 189 query-result tokens, 5,000 hot-loop character
tokens, and 26,317 validation-input tokens. It lets the rows read like model
throughput while keeping the numerator explicit.

## Warm post-JIT results

Protocol:

- 32 initial invocations;
- a timing probe;
- enough additional invocations for approximately 750 ms of
  workload/runtime-specific warmup;
- a 16-invocation post-warm probe;
- nine samples, each calibrated to approximately 300 ms; and
- mean wall time, population standard deviation, and coefficient of variation
  (CV) computed over per-invocation sample means.

`n/sample` and `warm n` are invocations, not collection elements.

| Workload | Runtime | Mean ms ± SD | CV | n/sample | warm n | Work tok/s |
|---|---|---:|---:|---:|---:|---:|
| Transform 10k | guarded SCI | 0.5097 ± 0.0174 | 3.41% | 554 | 1,075 | 24.0M |
| Transform 10k | unguarded SCI | 0.2062 ± 0.0014 | 0.70% | 1,478 | 3,428 | 59.3M |
| Transform 10k | compiled JVM | 0.1464 ± 0.0042 | 2.87% | 2,033 | 5,299 | 83.5M |
| DB/render glue | guarded SCI | 0.04925 ± 0.00065 | 1.32% | 5,912 | 6,764 | 3.84M |
| DB/render glue | unguarded SCI | 0.04392 ± 0.00028 | 0.64% | 3,468 | 16,644 | 4.30M |
| DB/render glue | compiled JVM | 0.03840 ± 0.00394 | 10.27% | 7,679 | 20,056 | 4.92M |
| Hot loop 20k | guarded SCI | 1.1586 ± 0.0039 | 0.34% | 261 | 698 | 4.32M |
| Hot loop 20k | unguarded SCI | 1.0094 ± 0.0257 | 2.55% | 299 | 822 | 4.95M |
| Hot loop 20k | compiled JVM | 0.09494 ± 0.00208 | 2.19% | 3,186 | 1,166 | 52.7M |
| Malli-heavy 1k | guarded SCI | 0.1820 ± 0.0041 | 2.26% | 1,704 | 2,476 | 144.6M |
| Malli-heavy 1k | unguarded SCI | 0.1638 ± 0.0049 | 2.97% | 1,791 | 4,752 | 160.6M |
| Malli-heavy 1k | compiled JVM | 0.1359 ± 0.0027 | 2.01% | 2,357 | 6,095 | 193.6M |

The compiled glue row has one noisy sample (CV 10.27%); its p50 is
0.03737 ms versus the 0.03840 ms mean. Reporting the mean rather than silently
dropping the outlier makes the compiled ceiling slightly less favorable, not
more.

## Ratios and guard economics

| Workload | Guarded / unguarded | Guarded / compiled | Unguarded / compiled | Guard share of guarded wall |
|---|---:|---:|---:|---:|
| Transform 10k | 2.47× | 3.48× | 1.41× | 59.5% |
| DB/render glue | 1.12× | 1.28× | 1.14× | 10.8% |
| Hot loop 20k | 1.15× | 12.20× | 10.63× | 12.9% |
| Malli-heavy 1k | 1.11× | 1.34× | 1.21× | 10.0% |

The step counter is deterministic for these inputs:

| Workload | Interpreter steps | Guarded − unguarded | Approx. delta per step |
|---|---:|---:|---:|
| Transform 10k | 20,000 | 303.5 µs | 15.2 ns |
| DB/render glue | 1 | 5.33 µs | fixed door cost dominates |
| Hot loop 20k | 20,459 | 149.2 µs | 7.3 ns |
| Malli-heavy 1k | 2,001 | 18.2 µs | 9.1 ns |

The transform's 15.2 ns/step agrees in scale with the independent guard-batch
lane's 13.6 ns/safepoint measurement at current HEAD. The source explains the
shape:

- SCI's generated functions call `:interrupt-fn` at interpreted function entry
  and on each `recur` (`reference-code/sci/src/sci/impl/fns.cljc:24-80`).
- Seon's retained holder decrements one primitive long and checks enforcement
  plus the current platform interrupt predicate
  (`src/seon/host/guard.cljc:185-196`).
- The opt-in `sci.interrupt` replacements charge collection materialization
  and reduction per element
  (`reference-code/sci/src/sci/interrupt.cljc:205-285`).

That is why the guard is expensive as a *fraction* of a transform made mostly
of compiled core operations, but only 13% of a hot loop whose interpreted body
already costs more. Batching the counter would attack the smaller term in the
hot-loop case and weaken deadline granularity; the independently measured
do-not-land decision is consistent with this matrix.

## Corpus classification

The classifier reads every `:seon.fn/source` from the emitted artifact with
tools.reader, selects the CLJ side of reader conditionals, supplies aliases
only for reading auto-resolved keywords, ignores quoted data, and walks call
heads.

The rule is deliberately mechanical:

- **compute-shaped** when source contains an explicit iteration/callback
  traversal head (`loop`, `recur`, `map`, `reduce`, `filter`, `doseq`, and the
  recorded set) or a direct self-call; otherwise
- **glue-shaped**.

This is a candidate classifier, not a claim that every call is hot. It errs
toward compilation: a `map` over ten elements and a `map` over ten million
elements are both candidates. Runtime size evidence and the execution plan
decide whether promotion is worthwhile.

| Population | Glue | Callback traversal | Explicit loop/recursion | Total compute |
|---|---:|---:|---:|---:|
| All function rows | 2,055 (69.8%) | 665 (22.6%) | 225 (7.6%) | 890 (30.2%) |
| Schema'd functions | 657 (70.9%) | 202 (21.8%) | 68 (7.3%) | 270 (29.1%) |
| Non-private functions | 980 (71.2%) | 291 (21.1%) | 106 (7.7%) | 397 (28.8%) |

There were 106 directly recursive functions in the full artifact. The most
common traversal heads were `map` (356 sites), `mapv` (254), `keep` (193),
`recur` (155), `filter` (149), `some` (147), `remove` (132), `reduce` (116),
and `loop` (93). Examples at the compute-heavy end include
`seon.schema/build-projection`, `seon.program.plan/plan-execution`,
`my.plan.internal/compile-resolved`, and
`seon.repl.parse/first-top-level-close`. Glue samples include thin
capability entries, transaction-data builders, error projection, and direct
query/render wrappers.

## Where the space-for-computation trade wins and loses

### Wins

- **Agent orchestration and capability glue.** A 10.8 µs absolute compiled
  gap is noise beside a real database, filesystem, provider, or package call.
  The static corpus split says this is the modal function shape.
- **Validation around compiled cores.** Calling a compiled Malli validator
  1,000 times through an interpreted reduction costs only 46.1 µs more than
  the fully compiled function, including the host eval envelope.
- **Bounded transforms.** Even the guarded 10k transform remains below
  0.51 ms on this machine. A 3.48× ratio sounds dramatic; the absolute
  latency is still small for turn-scale orchestration.
- **Operational simplicity and memory.** The computation tax is paid only
  while a function executes. The retired self-host memory class was retained
  per child/process. This matrix therefore supports the shared-JVM choice for
  the dominant short-lived glue population even without using historical
  memory figures as fresh measurements.

### Loses

- **Sustained corpus-local loops.** The hot loop is 12.20× slower than
  compiled and 10.63× slower even with the guard disabled. Removing safety
  checks cannot close that gap.
- **Large callback traversals.** The guard adds a safepoint at every
  interrupt-aware collection step. For very large inputs, the transform's
  15 ns/step is linear and visible.
- **Latency-critical pure parsers/planners.** The artifact contains 225
  explicit-loop/recursion candidates and several central graph/parser
  functions. If they run over large values on a turn's critical path, they
  deserve compiled placement or a compiled core leaf.

## P6 placement implications

1. Keep SCI-local as the total fallback. The guarded glue rows demonstrate
   that ordinary agent orchestration does not need speculative compilation.
2. Derive compute candidacy from the existing P1/P2 program graph and source
   edges. The audit classifier is evidence for the decision, not a new runtime
   authority.
3. Prefer compiled leaf routing for explicit loops/recursion first, then
   callback traversals with measured large inputs. Those groups are only
   7.6% and 22.6% of the artifact respectively.
4. Route at the function/call boundary already owned by `plan-execution`.
   Do not weaken the guarded SCI door or add a size-based bypass inside it.
5. Preserve differential tests at promotion. The largest wins occur where a
   semantic drift would also be most expensive: parsers, graph folds, schema
   projection, and numeric/string crunching.
6. Measure the missing same-form Bun matrix only after the canonical artifact
   and fresh `interpbench` cluster are checkpoint-ready. Historical self-host
   burst timings answer compilation latency and retention, not these warm
   workload bodies.

## Reproduction

The ignored probes are:

- `tmp/interpbench/bench.clj`, blob
  `67d1ab05caf9a175f5d412e0ce8fd4ae23e91090`;
- `tmp/interpbench/classify.clj` (the final classification adds subclasses to
  blob `0191baf6d1d90634d35267ffa7c2d0159353080`; rerun `git hash-object` if
  extending it).

Commands:

```bash
clojure -M:writer:host tmp/interpbench/bench.clj
clojure -M:writer:host tmp/interpbench/classify.clj
bin/seon cluster status interpbench --edn
bin/seon cluster open interpbench

```

The definitive benchmark was bracketed by `git hash-object` over the guard,
host evaluator, context owner, and benchmark source. All four hashes matched.
No production or test source was edited by this lane.
