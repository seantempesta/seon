---
type: issue
status: open
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
