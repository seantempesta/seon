---
type: issue
status: resolved
severity: friction
tags: [issue, testing, instrumentation, wave/contract-gate]
---

# A test asserting an armed contract is unarmed by another test in the same worker

## Problem

`seon.instrument-test` legitimately arms and removes instrumentation in the
worker JVM it runs in — `remove-is-total` and
`production-instruments-nothing-and-undoes-what-is-there` both call
`seon.instrument/remove!`, which is the behaviour they exist to prove. Pooled
workers run many tests per JVM, so any OTHER test in that worker that asserts a
contract refusal is asserting `seon.instrument-test`'s timing, not its own
subject.

Observed 2026-09-08 by the `production-defects-p1-p6` lane, running
`bin/test seon.cluster-test seon.instrument-test seon.print-test
seon.sci.eval-test seon.test-runner-test seon.db-test`:

```text
FAIL in (seon.db-test/malformed-reads-return-flat-errors) (db_test.clj:1107)
expected: (= :seon.instrument/contract-violated (:seon.error/kind refusal))
  actual: (not (= :seon.instrument/contract-violated :seon.db/invalid-read))
```

`seon.db/pull-many` was not armed when that assertion ran. The runner's own
confirmation phase names the shape exactly — the same lane's first draft of
`seon.test-runner-test/the-gate-runs-under-the-contracts-a-cluster-runs-under`
was reported `confirmation parallel-only`: red in the pool, green in isolation.

This is not a defect in either test's subject. It is a MISSING CONSTRAINT
between them: nothing declares that `seon.instrument-test` owns the worker's
instrumentation state for the duration of its run, and nothing stops a
contract-refusal assertion from being scheduled beside it.

## Why it matters

A red that depends on scheduling is the worst kind of gate signal: it is
reproducible only sometimes, it attributes to the wrong owner, and the
confirmation phase's `parallel-only` verdict is the only thing that tells the
reader it is not a real defect. Every future test that asserts a contract
refusal inherits the hazard silently.

## Candidate directions

- Give the worker's instrumentation state an owner, the way root-owning task
  groups already get one: schedule `seon.instrument-test` in a group nothing
  else co-runs with, so the constraint is DECLARED rather than hoped for.
- Or make `seon.instrument-test` restore the worker's entering instrumentation
  in a fixture, so the mutation it proves is bounded by the test that proves it.

The first is preferable — it is the mechanism the runner already has for
exactly this class (`root-owning-tasks-never-co-run-inside-one-worker-group`),
and the second still leaves a window while the test runs.

## Not affected

`seon.db-test/malformed-reads-return-flat-errors` is green when `db-test` runs
without `instrument-test` in the same selection, and
`the-gate-runs-under-the-contracts-a-cluster-runs-under` was rewritten to
compare LOADED program namespaces rather than an armed-set snapshot, which is
the property its subject actually owns.

## Resolved 2026-09-08 (`instrumented-gate-backlog-2`)

Not by declaring a worker group. The candidate directions both start from
"which tests own this state" — a declaration that has to be maintained, on a
set the tree keeps growing (six suites call the whole-image
`seon.instrument/apply!` or `remove!` today, and `seon.db-test` is BOTH a
mutator and a victim of the class). The owner law says derive at the
authority instead: the worker's armed state is a PRECONDITION of admitting a
task, so the worker derives it per task rather than trusting what the last
task left.

`seon.test.runner/reassert-contracts!` counts the wrappers actually installed
before every `:run` command and re-arms when that is LESS than the worker
armed at initialization. More is left alone — a test arming a filter of its
own is expected to undo it, and re-arming over that would fight its subject.
Nothing has to be declared, so nothing can drift: a new suite that strips
contracts costs one re-arm rather than a silently unarmed remainder.
Regression:
`seon.test-runner-test/a-worker-rearms-only-when-a-task-stripped-its-contracts`.

Two production defects fell out of building it, both of the class this
project keeps meeting — a check that reports health because its subject was
never asked:

1. **`seon.instrument/instrumented` read an ALIAS as its subject.** Malli
    stamps `::mi/original` on the wrapper fn and names no var
    (`reference-code/malli/src/malli/instrument.clj:8,38`), and the function
    scanned every var in every loaded namespace. `(def real-evaluate
    sci.eval/evaluate)` in `seon.cluster.agent-test`, captured while
    contracts were armed, therefore answered `instrumented?` true forever —
    and nothing could ever unstrument it, because malli unstruments what it
    REGISTERED. So `remove!` reported a survivor it had no way to remove and
    `apply!` in `:record` mode reported instrumenting one var while
    instrumenting none. The candidates are now the vars malli holds a
    function schema for. This is the whole of
    `seon.instrument-test/remove-is-total` and
    `production-instruments-nothing-and-undoes-what-is-there`.
2. **The gate armed itself from an uncompiled decision document.**
    `arm-contracts!` handed `seon.config/default-decisions` — which carries
    `:seon.config/absent` sentinels for optional dials — to
    `seon.config/result-caps`, whose declared input is
    `:seon.config/effective`. It answered correctly because every cap key it
    reads is decided, so the disagreement was invisible for exactly as long
    as arming happened before contracts existed to observe it. It surfaced
    the first time a worker had to re-arm mid-run. The gate now compiles
    `seon.config/defaults`, which is what the docstring already claimed.

Not reproducible at HEAD, for the record: in
`bin/test seon.db-test seon.instrument-test seon.test-runner-test`, both tests
this issue names —
`seon.db-test/malformed-reads-return-flat-errors` and
`the-gate-runs-under-the-contracts-a-cluster-runs-under` — are green in the
pool, and every red the selection reports is confirmed `reproducible` rather
than `parallel-only`. The repair is therefore the constraint, not a cure for
a red: the hazard was real, is removed by construction, and its two
by-products were genuine defects.
