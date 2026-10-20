---
type: research
status: evidence
tags: [research, census, contracts, generators]
---

# Census: does each contract generate data and hold?

Evidence for lane census-gen, 2026-10-20, HEAD `ef7cf957d` (machine-readable rows: [gen.edn](gen.edn), one map per function, `:census/sym` first).

## What was measured

Every contracted function in `src` (3,640 `:seon.schema.projection/function-contracts` entries whose namespace has a file under `src/`; the projection holds 4,562 including `test/` and `script/`). Each arity of a contract is taken apart into its input `[:cat ...]` and its output schema. Per function:

- compile: the contract through `seon.schema/compilable-form` against the packaged projection (`:seon.schema.projection/compile-options`, the registry the program itself uses);
- generate: `malli.generator/generate` of the input and of the output, seed 0, size 5, 2 s bound each; the failure is classified from the `ex-data` malli throws (`:malli.generator/no-generator` and the failing sub-schema, `:malli.generator/such-that-failure`, `:malli.core/invalid-schema`, timeout);
- named blame: each registered schema key the failing side references is generated alone (all 4,203 registered schemas are attempted); the blame is the minimal failing named schemas (no failing named schema beneath them), or `:census/inline` when the failing leaf sits in the contract itself;
- check: 10 generated argument vectors (seeds 0..9, sizes 0..5) per arity applied to the loaded Var (instrumented as the check JVM arms it), the result validated with `m/validator` of the output. Same sampling idea as `malli.generator/function-checker`, but size is capped at 5 (`check` ramps size to 200) and there is no shrinking; the smallest counterexample of the ten is kept. Per function wall bound 2 s.

Effects are skipped, not run (`:census/skipped :effectful`): a name ending in `!`, or a body (read from `src` and followed through every `src` callee to a fixed point) that reaches a datahike write, `konserve`, `core.async`, `babashka.fs`, file/shell I/O (`slurp`, `reader`, `writer`, `spit`, `delete-file`, `.mkdirs`...), `require`, `alter-var-root`, `future`, `System/exit` or a library `!` call (except atoms, volatiles and transients). Functions whose generated arguments contain a host object (connection, SCI context, record, function) are skipped as `:host-object-input`: those cannot be called with data.

Run in throwaway check JVMs (`bin/seon check --at <dangling sha> --include-long`), never on default. The probe was a test namespace in a dangling commit; it is deleted and only these files are committed. The run is ten sequential checks (about 7 minutes in total): the check JVM has a 4 GB heap, and `seon.cluster.source/record-results!` and its siblings allocate over 1 GB on generated input, so the probe watches each call's thread allocation and wall time, aborts the run when a call outlives cancellation by 3 s, and the driver resumes with the remaining functions in a fresh JVM. One call killed the JVM outright and is marked `:crashes-jvm`.

## Totals (3,640 contracted functions)

| Measure | Functions |
|---|---|
| contracts that compile | 3,639 |
| compile failures | 1 (`seon.schema.internal/assert-compilable-schema!`: `:seon.schema.internal/bound-definition` is an invalid schema) |
| input and output both generate | 3,377 |
| input or output does not generate | 262 (input 204, output 105; 47 both) |
| not classified (JVM killed by the call before generating) | 1 (`seon.cluster.source/record-results!`) |
| generation timeout (2 s) | 13 functions |
| effectful, skipped | 2,040 |
| host-object input, skipped (a connection, context or function is generated as an argument) | 538 |
| input does not generate, so not callable | 204 |
| source unreadable (`seon.schema.internal`, 2 functions) | 2 |
| killed the check JVM | 1 |
| called with 10 samples | 855 |
| of those, contract held on all 10 | 701 |
| of those, a counterexample | 154 |

Counterexample kinds (154): 42 are contract refusals from the instrumented wrappers (24 are the function's own output refusing its own contract, 18 are a callee refusing an argument this function passed on), 108 are other exceptions from the body on inputs its contract admits (untyped `ex-info` 30, `IllegalArgumentException` 31, NPE 20, `StringIndexOutOfBounds` 11, `IndexOutOfBounds` 6, `ClassCast` 4, others 6), 1 test execution error, 2 timeouts (`seon.turn/proved-unanswered?`, `seon.turn/verdict-proved?`), 1 harness error (`seon.cluster.reconcile/admit`: its generator hit `such-that-failure` at call time).

Cost of the census: 72.6 s of function time in total (median 0.7 ms, p95 10.9 ms, the slowest 11 s: `record-results!`'s family).

## Why schemas do not generate

Leaf causes, counted in functions (a function is counted once per distinct cause among its input and output):

| Cause (malli type, failing schema) | Functions |
|---|---|
| no generator, `[:fn clojure.core/ifn?]` | 69 |
| no generator, `[:fn clojure.core/bytes?]` | 35 |
| no generator, `[:fn malli.core/schema?]` | 30 |
| no generator, bare `fn?` | 18 |
| no generator, `[:fn clojure.core/volatile?]` | 16 |
| generation timeout (2 s) | 13 |
| no generator, `[:fn malli.registry/registry?]` | 9 |
| no generator, `[:fn clojure.core/some?]` | 9 |
| no generator, `[:fn seon.cache/cache?]` | 8 |
| no generator, `[:fn seon.profile/histogram?]` | 7 |
| invalid schema, `:seon.schema/value` | 6 |
| `such-that-failure` on an `:and` | 6 |
| no generator, `process-handle?`, `directory-handle?` | 5, 5 |
| no generator, `seon.profile/world-counter?`, `clojure.core/fn?` | 4, 4 |
| about 25 further single predicates (`class?`, `delay?`, `seq?`, `deref`, `var?`, `future?`, `seon.fs/path?`, `rewrite-clj.node/node?`, ...) | 1 to 3 each |

Nearly everything is `no-generator` on a `[:fn pred]` that no `:gen/*` property completes: 235 of the 262 non-generating functions have only that kind of cause. The others are 13 timeouts, 6 `invalid-schema` (a reference that malli's generator cannot resolve while the registry still validates it) and 6 `such-that-failure`.

Leverage, greedy by cause (functions that would generate if the top k causes got a generator): k=1, 55 of 262; k=3, 119; k=5, 152; k=8, 179; k=10, 193; k=15, 218; k=20, 234; k=30, 251.

### Named schemas that lack generators

Of 4,203 registered schemas, 64 do not generate on their own (every one is minimal: the failure is its own leaf, not a registered schema beneath it). Causes: `bytes?` 12, bare `fn?` 10, `invalid-schema :seon.schema/value` 6, `ifn?` 6, timeout 5 (including `:seon.test.run/completion`, `:seon.turn/record-evaluated-call-request`, `:seon.test.run/claim-completion`, `:seon.source/test-recording-request`), `seon.profile/adder?` 5, `world-counter?` 4, `such-that-failure` 3, `volatile?` 3, the rest single predicates. Functions blamed on each (a failing function names every minimal failing schema it references; 154 of the 262 also or only name a predicate inline in the contract and blame `:census/inline`):

| Named schema | Functions | Cause |
|---|---|---|
| `:seon.heap/on-crossing` | 27 | `[:fn ifn?]` |
| `:seon.schema/compiled-validator` | 24 | bare `fn?` |
| `:seon.embed/octets` | 21 | `bytes?` |
| `:seon.source/file-bytes` | 20 | `bytes?` |
| `:seon.embed/request` | 19 | `[:fn ifn?]` |
| `:seon.embed/matrix` | 12 | `bytes?` |
| `:seon.profile/volume-histogram`, `/sizes` | 10, 9 | `seon.profile/world-counter?` |
| `:seon.profile/adder`, `/accumulator`, `/histogram`, `/ordinal`, `/entered` | 9 each | `adder?`, `accumulator?`, `histogram?`, `seon.batch/stamped-clock?`, `thread-local?` |
| `:seon.cache/cache` | 8 | `seon.cache/cache?` |
| `:seon.test.run/completion` | 6 | generation timeout |
| `:seon.flow.actions/task-state` | 5 | `such-that-failure` |
| `:seon.await/accept?`, `:seon.cluster.store/writer-operation` | 4, 4 | `invalid-schema` (`:seon.schema/value`, `:seon.db/database-value`) |
| `:seon.profile/counts`, `/world-counter` | 4, 3 | `counts?`, `world-counter?` |

Fixing a few leaves fixes hundreds of blames: a generator for the three most common predicates (`ifn?`, `bytes?`, `malli.core/schema?`) moves 119 of the 262 into generating, whether declared inline or on the named schemas above.

## Where the contract does not hold

Of the 855 functions called, 154 returned a counterexample on valid generated input. Examples worth reading in [gen.edn](gen.edn) (`:census/failure`):

- 24 functions return a value their own contract refuses (`:census/failure :census/thrown :operation` equals the function): `seon.ai/fold-tool-calls`, `seon.batch/test-cost-diff`, `seon.blob/file-store-directory`, `seon.cluster.agent/attempt-agent`, `seon.cluster.boot/heap-dials`, `seon.cluster.branch-evidence/verdict`, `seon.cluster.reconcile/row`, `seon.context/proof-entry`, `seon.context/refusal-data`, `seon.cost.growth/dimension`, `seon.health/stored-observation`, `seon.mine/error-producer`, `seon.mine/top`, `seon.ref/span-candidate`, `seon.ref/with-sections`, `seon.render/agent-render-profile`, `seon.render/selection-refusal`, `seon.render.value/display-budget`, `seon.sci.eval/kept-evaluation`, `seon.sci.eval/plan-test-row`, `seon.sci.reader/error-value`, `seon.task/attempt-view`, `seon.test.runner/failure-report`, `seon.turn/executed`. Either the output schema is too strict for what the body produces or the body returns `nil`/a wrong shape for an admitted input.
- 108 functions throw a non-contract exception (31 `IllegalArgumentException`, 20 NPE, 11 `StringIndexOutOfBounds`, ...) on inputs the contract admits: the input schema is looser than the body's domain (empty strings and negative integers are common). NPE examples: `seon.batch/source-of`, `seon.cache/kind-counters`, `seon.call-preparation.plan/arity-plan`, `seon.cluster.agent/taskless-actions`, `seon.cluster.agent/unfinished-intent`.
- 18 functions pass a generated value to a callee whose contract refuses it (e.g. `seon.batch/cost-diff` into `seon.batch/sample`, `seon.cluster.agent/refusals-verdict` into `seon.context/refusal-data`, `seon.db/write-problem` into `seon.error.problem/explain-problem`, `seon.mine/seen-after` into `seon.mine/first-under`).
- `seon.cluster.source/record-results!`, and its siblings, allocate over 1 GB and run for seconds on tiny generated input; one call killed the 4 GB check JVM.

## Limits

- The effect classification is conservative and static (names and symbol reachability, no data flow): 2,040 functions are skipped as effectful, many of them reads whose bodies reach a skipped effect through `seon.db/q` or `seon.error.refusal/diagnostic`. Executed coverage is 855 of 3,640 (23%), so "contract holds" is a statement about those 855 only.
- Ten samples at size ≤ 5 find the obvious counterexamples; a pass is not a proof. No shrinking: the smallest of the ten failures is reported.
- Output generation is measured separately from checking: a function whose output schema does not generate is still checked (the output is validated, not generated).
- An instrumented Var reports its own contract refusal as an exception, so "output violates contract" is seen as a thrown `:seon.instrument/contract-error` whose operation is the function.
- The census ran against `src` on HEAD `ef7cf957d` plus a deleted probe; nothing in `src` was changed.
