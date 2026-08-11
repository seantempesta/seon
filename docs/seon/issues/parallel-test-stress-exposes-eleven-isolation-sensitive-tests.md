---
type: issue
status: open
severity: friction
tags: [issue, testing, concurrency, operator]
---

# Classify eleven parallel-only test failures by their shared resource

## Problem

The first complete nine-worker `bin/test --full` stress run after shared-base
preparation produced eleven tests that failed in the parallel pool and passed
when the runner immediately reran each test alone. The tests own isolated
operator roots where applicable, so root identity alone does not explain the
failures. Moving them into the serial remainder would hide either a production
concurrency defect or an undeclared test resource and is not an admissible fix.

## Evidence

The frozen-tree run at `b9c0f63fd2c7f2dc36f61098c69688eee1604234` is
recorded in
[`parallel-runner-measurement-2026-08-10.md`](../../prds/sci-execution-runtime/research/parallel-runner-measurement-2026-08-10.md).
The runner's isolated confirmations classified these tests as parallel-only:

- `seon.cluster.boot-test/incremental-source-refresh-preserves-agreement-across-real-edits`;
- `seon.concurrency-independence-test/n-agents-fold-independently-on-one-live-cluster`;
- `seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store`;
- `seon.dev.fresh-operator-test/forced-reset-clears-an-exact-dead-process-record`;
- `seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`;
- `seon.dev.fresh-operator-test/isolated-cached-boot-reports-refusal-then-reaches-readiness`;
- `seon.dev.fresh-operator-test/live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`;
- `seon.dev.fresh-operator-test/populated-stopped-cluster-reopens-after-full-operator-restart`;
- `seon.dev.fresh-operator-test/source-less-root-reset-republishes-and-reforks-default`;
- `seon.sci.desk-test/desk-survives-kill-9-and-explicit-clear`; and
- `seon.sci.eval-test/generated-sources-compose-fork-guard-and-admission`.

The boot failure exposed one concrete shared-resource symptom: clj-kondo
threw `Clj-kondo cache is locked by other thread or process` during
`seon.fn/build-artifact`. Other failures include load-sensitive process
lifecycle assertions and generated-property counterexamples. Those are
observations, not yet one asserted cause.

The cache-lock class is now fixed at its owner. `bin/test` copied each
worker's source tree but symlinked the top-level `.clj-kondo` directory, so all
nine processes wrote the same cache. A losing worker could poison the delayed
canonical database base on its first population attempt; later database tests
in that worker then failed immediately. Worker checkouts now copy `.clj-kondo`
copy-on-write, and
`seon.test-runner-test/worker-checkouts-own-the-writable-clj-kondo-cache`
proves the directory is private rather than a symlink. The remaining rows in
this issue still require classification after the repaired complete-tier run.

The same run had six failures that reproduced alone. They are deliberately
excluded from this issue because isolated confirmation falsified parallelism
as their cause.

The 2026-08-11 default-tier rerun at `e75b2a553` exposed the operational cost
of leaving this class open. One worker returned a run of database-dependent
pool failures in 0--4 ms; the same two-namespace sequence then passed 33 tests
and 200 assertions in one worker. Before that falsifier completed, the runner
had spent more than 14 minutes serially starting a fresh approximately
12-second JVM for each clean confirmation. `seon.test.runner` now retains one
clean JVM per failed task but starts those confirmations with bounded
parallelism. This removes diagnosis serialization; it does not classify away
or hide any row in this issue.

## Owner

The test and production owner of each resource named during triage: clj-kondo
analysis/cache ownership, fresh-operator process lifecycle, cluster restart,
and SCI evaluation fixtures. The parallel runner remains the stress and
attribution mechanism; it does not acquire a hand-maintained serial list.

## Acceptance

- Reproduce each row under parallel load and identify whether the production
  mechanism or the test fixture owns the shared resource.
- Make that resource's ownership explicit or make the production operation
  concurrency-safe, with one regression for each distinct failure class.
- Keep the repaired tests in the derived parallel pool.
- A complete `bin/test --full` run produces no parallel-only confirmations for
  these rows, without reducing worker count or adding names to a serial list.
