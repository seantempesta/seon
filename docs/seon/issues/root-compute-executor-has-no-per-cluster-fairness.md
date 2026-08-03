---
type: issue
status: open
severity: friction
tags: [issue, architecture, cluster, flow, scheduling]
---

# Give the shared compute executor per-cluster fairness

## Problem

Every cluster graph submits compute work to one fixed process-root pool. The
pool has a global capacity but no cluster identity, quota, or fair admission
mechanism. A compute storm from one cluster can occupy every platform thread
and starve unrelated clusters even when their own graphs and queues are healthy.

## Evidence

- `resources/seon/operator/runtime.clj:17-22` constructs one bounded platform
  executor at `availableProcessors` and one shared I/O executor.
- `src/seon/flow.clj:424-446` wires the root compute executor directly into the
  work-launcher graph.
- Ruling #51 records the missing fairness mechanism explicitly at
  `docs/prds/sci-execution-runtime/plan/README.md:1885-1887`.

## Owner

Process-root compute admission and the cluster graph submission boundary.

## Acceptance

Under sustained compute saturation from cluster A, cluster B receives a stated
minimum or fairly scheduled share and completes a bounded representative eval.
The mechanism derives cluster identity from the submission's existing custody;
it does not maintain a hand list of clusters.
