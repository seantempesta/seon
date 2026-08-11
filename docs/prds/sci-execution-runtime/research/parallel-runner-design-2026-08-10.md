---
type: research
status: complete
tags: [testing, performance, concurrency, operator]
---

# Parallel test runner design — 2026-08-10

## Verdict

Evolve `bin/test` into one coordinator with nine long-lived runner JVMs. Nine
is derived from this machine's 18 available processors (`cores / 2`). Each
worker owns a complete checkout root, operator root, JVM, and store beneath the
one suite root. It executes one task at a time. Workers execute different tasks
concurrently.

This is the simplest topology that satisfies all three constraints at once:

- root-owning tests run concurrently because worker roots are disjoint by
  construction;
- tests assigned to one worker share its JVM/store only serially; and
- the 69 test Vars absent from the program manifest fail closed into one serial
  remainder instead of being guessed safe.

The coordinator starts the serial remainder alongside the parallel pool. The
platform tier uses the same workers first and remains fail-fast: no bulk task
is submitted until every platform result is green.

Do not implement a second runner. `bin/test` remains the only command,
`seon.test.runner` remains the only runner namespace, and the existing
selection/tier semantics remain unchanged.

The full-suite target is met on the owner's post-fix 20–25 minute sequential
floor: nine workers project 128–162 seconds of balanced work; the 188-second
n-agent test is the larger lower bound. Including worker load projects about
196–203 seconds when the 69-test serial remainder overlaps, or 239–246 seconds
if it is conservatively appended. Both are below five minutes and therefore
below the hard 5–8 minute ceiling.

The bare `<60 s` target is not met by parallelism alone. The current focused
cohost platform test is 84.24 seconds wall / 68.84 seconds inside its test Var.
It must consume the shared published base and reduce its post-publication proof
to at most 15 seconds. One retained 36.49-second publication plus roughly eight
seconds of worker load plus a 15-second cohost proof is 59.5 seconds; worker
startup and the 5.6–7.4-second selection graph build must overlap.

## Phase boundary

This report is phase 1 only. No production or test source was edited. Probes
lived below `tmp/` and used either the frozen retained suite source or a fresh
isolated `bin/test` root. Phase 2 remains unauthorized until the orchestrator
says `gate landed`.

## Authorities read end to end

- `bin/test`;
- `src/seon/test/runner.clj` and `src/seon/test/selection.clj`;
- `.agents/skills/clojure-testing/SKILL.md` and
  `.agents/skills/data-oriented-clojure/SKILL.md`;
- `script/seon/dev/changed_test.clj`, `script/seon/dev/test_roots.clj`, and
  `script/seon/dev/state.clj`;
- `test/seon/test_support.clj`;
- `resources/seon/schemas/seon.test.edn`;
- `test/seon/test_runner_test.clj` and
  `test/seon/test/selection_test.clj`;
- `docs/prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md`;
- `docs/prds/sci-execution-runtime/research/suite-fat-tail-2026-08-10.md`;
- `docs/prds/sci-execution-runtime/research/test-linkage-census-2026-08-03.md`;
  and
- the current working edge and relevant test-infrastructure/tiering rulings in
  `docs/prds/sci-execution-runtime/plan/unsettled.md` and
  `docs/prds/sci-execution-runtime/plan/README.md`.

The 2026-08-08 tiering implementation was also read as its two landing commits:
`b6886bf36` (platform-first and changed-only tiers) and `5e65fb7c9` (one
selector, with changed-test delegating to `bin/test --changed`).

## Dependency ledger

| Boundary | Selected source | Contract used |
|---|---|---|
| Clojure test execution | Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`; `reference-code/clojure/src/clj/clojure/test.clj:698-811` | `test-var` binds the active Var and reports begin/end; `test-vars` serializes Vars and applies namespace once/each fixtures; counters and output are dynamic bindings. |
| Static program graph | `src/seon/fn.clj:292-365,868-900`; clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2` | Test rows and forward call edges come from the one analyzer pass. Missing rows are missing facts, not permission to guess. |
| Changed selection | `src/seon/test/selection.clj`; commits `b6886bf36`, `5e65fb7c9` | Reverse reachability is derived from `:seon.fn/calls` and `:seon.test/subject`; content digests define the green basis. |
| Test declaration family | `resources/seon/schemas/seon.test.edn`; `src/seon/test/runner.clj:386-443` | `:seon.test/platform` and `:seon.test/long` are effective Var-or-namespace declarations, although the current index still discards those reasons. Isolation/grouping facts belong here too. |
| Isolated suite root | `bin/test:154-435`; `script/seon/dev/state.clj`; `script/seon/dev/test_roots.clj` | One invocation owns one copied checkout and operator root beneath `tmp/test-runs`; success reaps it, failure retains it. |
| Test database fork | Datahike `10540578248eaa686c1f88a7fe57644ee4c9f993`; `test/seon/test_support.clj:34-116,290-386` | One immutable populated base; ordinary tests receive distinct branches/connections and release them in `finally`. |
| Production source base | `src/seon/cluster.clj`; landing `d50275487` | A source base carries the published commit, projection, and acquired SCI context; branch forks reuse it. |
| Process workers | JDK 26.0.1 `Process`, `ProcessHandle`, and standard streams | Parent owns child identity and completion. Commands/results cross child stdin/stdout as data; test output never shares that stream unframed. |

## Frozen measurement

### Evidence quality

The complete log is `tmp/full-gate-2026-08-10b.log`. It ran frozen Git SHA
`05bf0a8918013c9a86a8f737cba5a9adbc78feee` and reported 1,151 tests, 11,874
assertions, 80 failures, and 10 errors in 4,827.02 seconds. Its red assertions
crossed in-flight source work and are not used here. Its BEGIN/END timestamps
are valid execution-cost evidence.

The census re-created that exact Git snapshot below
`tmp/parallel-runner-snapshot`, built its `src` + `test` program manifest, and
joined its test rows to the 1,151 top-level BEGIN/END pairs. The four deliberate
`seon.test-runner-failure-fixture` rows are indexed but are not top-level suite
tests. The 69 missing rows are the generated `seon.repl-parity-test` Vars.

### Group census

The resource categories below are derived by transitive call reachability.
The phase-1 probe used the concrete production boundaries named in the
dependency ledger: `seon.test-support/with-database`, `seon.cluster/start!`,
`seon.cluster/refresh-source!`, `seon.cluster.store/open-store!`, and the
effectful `seon.operator` entry functions. Phase 2 must lift the corresponding
resource/isolation facts into the program graph; the runner must not preserve
this probe's seed set as a source list.

| Derived class | Tests | Sequential body | Parallel treatment |
|---|---:|---:|---|
| No recorded resource owner | 617 | 1,285.239 s | Parallel pool. |
| Isolated database constructor | 356 | 878.534 s | Parallel pool; every worker invocation still gets its own Datahike branch. |
| Boot/operator/store owner | 109 | 2,595.317 s | Parallel across worker JVMs, serial within each worker group. Worker roots make the physical resources disjoint. |
| No indexed test row | 69 | 42.563 s | One serial fail-closed remainder; run alongside the pool. |
| **Total** | **1,151** | **4,801.653 s** | 1,082 resolved tests plus 69 serial. |

The runner overhead outside test Vars was about 25.37 seconds. The 1,082
resolved tests pack by longest-processing-time into nine old-baseline chains
of 528.787–528.788 seconds each. A dynamic queue reaches at least this balance
without depending on a retained timing file; latest durations may only improve
which expensive task starts first.

### Platform tier

The frozen platform tier contained 72 tests and 110.816 seconds of test body.
Nine-way longest-first packing gives worker chains of 2.657, 2.657, 2.657,
2.658, 2.658, 8.463, 8.745, 10.755, and 69.566 seconds. The last number is
entirely the cohost test.

A current-tree isolated rerun at `2d9e2ef25` confirmed the boundary rather than
assuming the old log remained representative:

- namespace load to verdict: 84.24 seconds wall;
- test Var: 68.84 seconds;
- first cluster ready about 43.0 seconds after test begin; and
- second cluster ready another 22.5 seconds later.

Parallelism cannot make a one-test critical path shorter. Bare `<60 s`
therefore requires the cohost cut in the follow-up section below.

### JVM and machine footprint

The machine exposes 18 processors and 128 GiB physical memory. Loading only
`seon.test.runner` in one test JVM measured:

- 7.66 seconds wall;
- 20.73 seconds user CPU; and
- 3,545,432,064 bytes maximum resident set (3.30 GiB).

Nine workers therefore project 29.7 GiB resident; keeping one coordinator JVM
alive projects about 33.0 GiB before child JVMs launched by individual tests.
That is intentionally substantial but fits this machine with wide margin. It
is the price of making Var roots, loaded namespaces, system properties, child
registries, and Datahike connection registries disjoint without a hand-curated
serial list. Worker count remains derived from available processors; reduce it
only after an observed throughput or memory regression.

## Topology decision

### Option 1: one JVM with parallel test Vars

This has the smallest resident footprint and the simplest work queue, but it
does not make the current test population independent. Sixty-four test files
contain process-global replacement/interning/namespace or system-property
operations. Namespace `:once` fixtures also assume one ordered invocation.
Classifying all those call shapes before the first parallel run would either
create a hand list or turn most expensive tests into one global serial tail.

Reject for v1.

### Option 2: static groups across runner JVMs

This isolates process state, but namespace or round-robin partitioning leaves
the long tail to chance. `seon.dev.fresh-operator-test` alone carried several
100–297 second historical tests, while a namespace-only group would serialize
all of them. A hard wall-time target cannot depend on lucky symbol ordering.

Reject as the scheduler, though each live worker still forms one serial group.

### Option 3: dynamic tasks across nine runner JVMs — recommended

The coordinator owns a queue of selected tasks. A task is normally one test
Var. A namespace with a `:once` fixture is one atomic task containing its
selected Vars, preserving Clojure's actual fixture contract. Each worker asks
for another task only after publishing the prior result, so nothing assigned
to the same worker can overlap. Distinct workers have distinct roots and JVMs,
so they may run concurrently even when both tests boot clusters, open stores,
or replace Vars.

The 69 missing-row Vars form one serial task stream. Their absence is loud in
the summary. They are never silently admitted to the parallel queue. Repairing
their index producer automatically moves them into the ordinary derived path;
no scheduler list changes.

This topology turns the owner's stress-test ruling into structure: concurrent
boot, store, publication, and evaluation tests execute unless they literally
share one worker. A parallel-only red is a finding, not evidence to demote the
test.

## Derived grouping rule

Phase 2 should make the split queryable in the `:seon.test` declaration family:

1. Discover the selected runtime test Vars exactly as today.
2. Join each Var symbol to the same snapshot's `:seon.test/sym` row.
3. Derive transitive resource reach from forward program facts. Resource-owner
   and isolation-boundary properties are declared on the owning functions and
   projected onto the test row as `:seon.test/isolation`; no namespace prefix,
   filename, symbol spelling, source scan, or scheduler exception list is
   admissible.
4. A resolved row runs in the worker pool. The assigned worker root is its
   isolation boundary; resource-sharing tests serialize inside that worker.
5. A missing row, unresolved call path, or invalid isolation declaration runs
   in the serial remainder and prints the missing fact.
6. A namespace `:once` fixture is an atomic multi-Var task, derived from the
   loaded namespace metadata. It is not a manually named exception.

The current schema already proves why this addition belongs here:
`:seon.test/platform`, `:seon.test/long`, calls, subject, namespace, and source
all describe one test declaration. The existing index still drops the marker
reasons; phase 2 should lift platform, long, and isolation together rather
than creating scheduler-only metadata.

## Ordering and reporting semantics

The coordinator assigns every selected task its current deterministic ordinal.
Workers return ordinary data containing worker id, test symbols, root, start,
end, elapsed time, counters, captured events, and bounded output.

- Platform tasks run first. The coordinator waits for all platform tasks. Any
  red stops bulk submission.
- Bulk tasks are longest-known-first, with declared long tests first when no
  duration exists. Dynamic assignment prevents a fixed slow group.
- Each worker binds its own `clojure.test/*report-counters*` and
  `*test-out*`. A namespace task calls `test-vars`, preserving once and each
  fixtures from the pinned Clojure implementation.
- Worker stdout carries only structured coordinator data. Test output is
  captured per task and never interleaves with another task's output.
- The parent prints concise live BEGIN/END lines including worker id and test
  symbol. At stage end it emits failures and the final results in original
  ordinal order, so attribution remains deterministic.
- Summary counters reduce over task results. Per-test results remain keyed by
  `:seon.test/sym`; timing and isolation are additional facts in the same
  result family.
- A signal or launcher exit makes the parent reap exact worker identities and
  their descendants before retaining the one suite root.

## Parallel-only failure attribution

On a parallel red, preserve the original task output and worker root identity,
then rerun that exact task once in a new isolated worker root.

| Parallel result | Isolated rerun | Classification |
|---|---|---|
| red | red | Ordinary reproducible failure. |
| red | green | `parallel-only`, attribution still open: platform concurrency defect or test isolation defect. |
| worker exits or wedges | green/red | Worker/process fault remains separate from the test assertion outcome. |

The runner must not automatically move a parallel-only test to the serial
remainder. The original failure stays in the verdict, and an issue is required
before any isolation declaration changes. The issue names both candidate
owners, the worker/root/process evidence, the exact isolated rerun, and the
acceptance criterion. This makes load a recurring platform stress test instead
of a mechanism for hiding flakes.

## Wall-time model against the hard target

### Full suite

| Model | Pool critical path | Serial remainder | Projected wall | Verdict |
|---|---:|---:|---:|---|
| Frozen old baseline | 528.8 s plus load | 42.6 s | about 537 s overlapping / 580 s appended | Misses 8 min; expected because this predates the day's speed fixes. |
| Owner's 20 min floor | max(128.6 s balanced, 188 s largest) | about 42.6 s | about 196 s overlapping / 239 s appended | Meets `<5 min`. |
| Owner's 25 min floor | max(161.9 s balanced, 188 s largest) | about 42.6 s | about 203 s overlapping / 246 s appended | Meets `<5 min`. |

The 188-second n-agent test is intentionally launched first and becomes the
largest worker chain. Forced reset at about 61 seconds and each retained
publication proof at about 36 seconds fit on other workers and do not extend
that critical path.

### Bare tier

The platform tier's present 69-second single-test chain plus JVM load is already
above one minute. The target requires all of these cuts:

1. The cohost regression consumes the suite's published source base and uses
   the production fork constructor for A and B. Keep the exact instrumentation
   ordering and assertions; remove only its private `refresh-source!`. Target:
   at most 15 seconds after the base exists.
2. Exactly one direct publication regression retains real publication. Every
   other publication-bearing test uses the shared base. Current publication is
   36.487 seconds, so two publication tasks on one critical chain are forbidden.
3. Worker startup, manifest selection, and source-base preparation begin
   concurrently. Sequentially paying 8 + 7 + 36 + 15 seconds would be 66
   seconds; overlapping startup/selection leaves about 36 + 15 + overhead.
4. If the one retained publication remains above 36 seconds after the runner
   lands, its program-row phase is the next development-velocity incident. The
   prepared cut is a shared published base copied/reflinked into worker roots,
   not a skipped platform proof.

The bare target is a release condition for phase 2, not an expectation to
waive because the full target passes.

## Follow-up cost classes if measurement misses

| Class | Current/post-fix cost | Required cut |
|---|---:|---|
| N-agent concurrency | about 188–195 s | Keep it parallel and launch first. If it grows beyond 240 s, split the six `[5 5 5 10 10 10]` scenarios into independent tasks over one acquired source base; do not reduce agent/form coverage. |
| Forced reset | about 61 s post-fix | Keep one real destructive proof. If it exceeds 90 s under load, separate source publication from reset composition and reuse the already-published commit; retain exact process-death and refork assertions. |
| Publication-bearing tests | 36.487 s each | One publication per suite source digest. All consumers fork/copy the shared base. The one direct proof retains full population. |
| Cohost platform proof | 84.24 s focused wall / 68.84 s test | Remove private publication; use the acquired source base and two production forks. Target `≤15 s` after base readiness. |
| Generated properties | situation about 40 s; transcript about 59 s in the old log | Run as independent pool tasks. If either becomes a worker-chain limiter after fixture cuts, split properties by fixed seed/size partitions while preserving total trials and one aggregated assertion. |
| Serial unresolved Vars | 69 tests / 42.563 s | Teach the one index pass to publish generated `deftest` identities. Until then they stay serial and loud. |

None of these tests may be marked long or moved to serial merely because it
failed under parallel load.

## Exact phase-2 edit plan

1. `bin/test`: derive `max(1, available-processors / 2)` workers; create one
   child checkout/operator root per worker below the suite root; start and reap
   exact child identities; retain the existing top-level snapshot and cleanup
   contract.
2. `src/seon/test/runner.clj`: add coordinator and worker modes inside the same
   namespace; preserve selection/tiering; derive tasks; dispatch dynamically;
   buffer per-task output; aggregate deterministic results; rerun parallel
   failures once in isolation.
3. `resources/seon/schemas/seon.test.edn` and `src/seon/fn.clj`: lift effective
   platform/long reasons plus the derived or declared isolation fact into the
   existing test row. Missing/invalid facts remain serial and loud.
4. `test/seon/test_runner_test.clj`: add one class regression. Two synthetic
   root-owning tasks assigned to one worker group block on latches and prove
   maximum active count is one, while a task in another group overlaps. This
   proves the class: one group's shared root/JVM/store can never execute two
   tests concurrently.
5. Keep `test/seon/test/selection_test.clj` unchanged unless an assertion is
   needed to prove selected symbols are identical between serial and parallel
   modes. Selection itself is already the one owner.
6. Measure the current focused cohost test, platform tier, bare changed tier,
   and full suite before/after on the same source digest. The implementation
   does not graduate without full `≤8 min` and bare `<60 s` observed walls.

## Phase-2 acceptance evidence

- platform tasks always finish before bulk starts, and a platform red submits
  zero bulk tasks;
- the resolved/serial census is printed and sums exactly to the selected test
  count;
- no two tasks in one worker group overlap; different worker groups do;
- sorted serial and parallel outcomes are identical on a green selection;
- failure output names the exact test and worker without interleaving;
- a deliberately parallel-only fixture is rerun in isolation and remains a red
  classified finding, never a silent serial demotion;
- launcher interruption reaps all worker JVMs and their descendants before the
  suite root is retained;
- a successful run removes every worker root with the suite root;
- full suite wall is at most eight minutes, with five minutes the working
  target; and
- bare changed/no-change tiers remain below one minute.

## Prepared implementation diff, not applied

The production diff should be narrow: one launcher, one runner namespace, one
existing test declaration family, and one regression namespace. There is no
new runner command, scheduler namespace, fixture registry, or test list. The
69-row gap and every parallel-only failure remain visible until their actual
owners are fixed.
