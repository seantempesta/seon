---
type: issue
status: open
severity: blocker
tags: [issue, seon.db, publication, bounded-execution, performance]
---

# The 30 s write bound fails program publication under load

## Problem

`eb8db3503` bounded every `seon.db/transact!` wait with the declared
`:seon.config.db/write-time-limit-ms` (30,000 ms), returning
`:seon.db/write-bound-exceeded` with `:seon.db/transaction-outcome-unknown`.
The program-population transaction of a base publication (87,639 datoms plus
whole-entity validation on the writer thread) takes longer than that when the
machine is loaded (load average 13 with four lanes' fast JVMs), so on
2026-09-18 ~02:05Z a cold gate's base publication died:

```text
Execution error (ExceptionInfo) at seon.fn/require-committed! (fn.clj:48).
Program indexing transaction was refused. seon.db/transact! stopped waiting after 30007 ms (bound 30000 ms). Datahike may still commit the queued transaction; its outcome is unknown.
```

(`tmp/orchestrator/gate-results/predicate-revert-gate.log:625`, retained root
`tmp/test-runs/run.puLgjn`). The bound did its job — the wait was loud — but
one bound for every write is the wrong shape: a two-datom turn write and an
87k-datom publication are not the same obligation.

## Root causes, both open

1. Publication writes carry no declared bound of their own; the publication
   already has a `publication-bound-ms` for its lifecycle lock and should
   hand the writer that obligation (the value carries its world).
2. The whole-entity validator runs on the writer thread and dominates the
   population transaction (the reset batch measured 75.9 → 41.6 s "more
   owed"). A publication that takes 40 s single-threaded on an idle machine
   is the defect the bound exposed, not a tuning item.

## Interim

The default `:seon.config.db/write-time-limit-ms` is raised to 600,000 ms
(the scale of the per-test exchange bound) so gates can run; it stays a
declared config fact. This note is resolved when publication declares its
own bound and the validator cost is measured off the writer's critical path.
