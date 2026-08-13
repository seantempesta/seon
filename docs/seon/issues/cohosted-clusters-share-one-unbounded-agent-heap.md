---
type: issue
status: open
severity: blocker
tags: [issue, runtime, wave/no-crash]
---

# Prevent one cluster from exhausting every co-hosted cluster's heap

## Problem

Clusters are isolated by branches and graphs but share one JVM heap and GC.
SCI's time limit interrupts interpreted function entrances; it is not a retained
heap limit. One cluster can retain or enqueue enough data to exhaust or thrash
the process heap, taking availability from every sibling cluster and the shared
Datahike writers. This violates ruling #51's requirement that one agent going
insane not affect the others.

## Evidence

- `resources/seon/operator/runtime.clj:11-22` places all cluster instances and
  executors in one JVM process.
- `src/seon/sci/eval.clj:266-325` implements one scheduled time-limit flag and
  per-thread entry/allocation observation; it does not enforce a retained heap
  ceiling.
- `docs/prds/sci-execution-runtime/plan/reference/measurements-2026-07-25.md:958-975`
  proves one direct 2 GB allocation can flatten as an error and the next
  transaction can survive, but explicitly says sustained retention across
  threads was never reproduced and leaves co-located blast radius open.
- The same measurements at `:1329-1349` did reproduce the terminal wall from
  queued transaction state: a 4 GiB heap remained full, with 699 GC events and
  heap exhaustion.
- `docs/prds/sci-execution-runtime/plan/reference/scheduling-design-2026-07-26.md:515-524`
  identifies the co-hosted heap blast radius as cross-cluster.

## Owner

The process/cluster isolation design under ruling #51. This is an owner design
gate, not a local SCI catch-site patch.

## Acceptance

A deliberately retaining or transaction-flooding agent cannot cause sibling
cluster turns, writers, web servers, or prepls to fail or suffer unbounded GC
stall. The proof must run two co-hosted clusters under a declared heap ceiling,
drive one to its enforced resource boundary, and show the sibling continues to
query, transact, and answer through SCI. Catching one `OutOfMemoryError` is not
acceptance because sustained retention and global GC are the failure class.
