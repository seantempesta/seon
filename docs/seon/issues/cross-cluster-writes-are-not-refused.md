---
type: defect
status: open
severity: blocker
tags: [custody, cluster, db, class/absence-as-health]
---

# A write from one cluster into another cluster's branch is not refused

Two tests, one invariant, both red under the instrumented gate:
`seon.cluster.custody-stability-test` (cross-cluster write isolation) and the
custody red `verify-repair-2` closed as "cause not established". Found by
`verify-p1-p6-and-backlog` (`research/verify-p1-p6-and-backlog-2026-09-08.md`,
production defect 2).

## Why it is a blocker

Branch custody is the boundary between clusters; a write that crosses it
silently is the two-JVMs-one-store class in a new coat. Nothing in the
turn-loop wave may build on custody until a probe shows the refusal.

## To do

One lane: reproduce on a scratch root with two clusters; find the seam
(`seon.db/transact!` custody check vs the connection actually handed); fix;
one class regression asserting the typed refusal.
