---
type: issue
status: open
severity: blocker
tags: [issue, operator, database, flow, wave/operator-lock-contention]
---

# Bound scheduled store collection before it holds the installation control lock

## Problem

The root maintenance schedule can invoke `seon.operator/collect!` while a test
or operator command needs to start another cluster. Collection holds the
installation-wide lifecycle lock and then dereferences Datahike's GC
`throwable-promise` without a bound. If GC does not settle, every later start
sleeps behind the lock until the suite's 300-second liveness watchdog kills the
worker.

This is distinct from the history-database render wedge fixed by `e93d995cd`.
That formerly blocked test completed and the runner advanced to
`partial-clusters-refuse-and-fresh-clusters-are-current` before this second
stack appeared.

## Evidence

The exact gate was `bin/test seon.cluster.boot-test` on 2026-08-13. Its retained
root is `tmp/test-runs/run.sEniHq`:

- `tmp/test-liveness/31644-1786664959617.log` records no reporter progress for
  300 seconds after the partial-cluster test began.
- `tmp/test-liveness/31767-1786664959294-threads.json` shows the test main
  thread at `boot_test.clj:885` inside `cluster/start!`, sleeping in
  `seon.operator.state/with-lifecycle-lock!` while `claim-root!` waits for the
  installation control lock.
- The same dump shows virtual thread 204 holding that lock through
  `seon.operator/collect!` → `collect-under-lock!` →
  `seon.cluster.registry/collect!`, parked in Datahike's
  `throwable-promise` deref at `reference-code/datahike/src/datahike/tools.cljc:102`.

The unbounded production seam is `src/seon/cluster/registry.clj:503-530`:
`collect!` directly dereferences `d/gc-storage`. The lock scope is
`src/seon/operator.clj:834-854`; scheduled invocation reaches it through
`src/seon/schedule.clj:535-617`. The waiting start is bounded only by the
operator lifecycle lock deadline, which exceeds the suite's silence watchdog
in this run and therefore supplies no test-level terminal evidence.

## Owner

The scheduled `seon.operator/collect!` execution boundary across
`seon.schedule`, `seon.operator`, and `seon.cluster.registry`. Collection must
remain behind Seon's one blob-aware GC owner, but it cannot hold the
installation control lock across an unbounded dependency wait.

## Acceptance

- Scheduled collection settles success or a typed bounded failure before any
  lifecycle lock can remain held indefinitely.
- A regression parks or withholds the Datahike GC completion and observes the
  exact bounded failure plus release of the lock to a waiting cluster start.
- `bin/test seon.cluster.boot-test` reaches a total tally; the partial-cluster
  test cannot disappear behind absence of reporter progress.
