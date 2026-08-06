---
type: issue
status: open
severity: blocker
tags: [issue, flow, error, observability]
---

# Commit every dropped core-fault observation

## Problem

When the bounded core-fault channel overflows, the only structured record is a
per-cluster atom increment. The callback also prints one stderr line, but a
restart loses the count and the identity of the dropped fault. This is exactly
the failure path recovery and forensics need: a core fault existed and was not
committed through the normal fault committer.

The callback must remain nonblocking on the faulting proc's thread. That
constraint does not justify making the observation process-only; it requires a
nonblocking handoff whose surviving owner commits the drop fact.

## Evidence

- `src/seon/cluster.clj:1757` creates `drops` as `(atom 0)` for one cluster
  graph.
- `src/seon/cluster.clj:1776-1787` increments it and prints when
  `CountedDroppingBuffer` overflows; no transaction or durable handoff occurs.
- `src/seon/flow.clj:847-897` routes admitted faults to the fault-committer proc
  but invokes `commit-drop!` synchronously for overflow.
- The isolated scratch runtime probe on 2026-08-06 found the live instance's
  `:seon.error/drops` value at zero and no corresponding database owner; the
  value existed only in the instance map.

## Owner

The fault committer and `:seon.error` facts own core-fault observability. An
overflow observation should enter that same durable mechanism without
blocking the faulting proc.

## Acceptance

- Buffer overflow performs only a bounded nonblocking handoff on the faulting
  thread.
- The fault-committer owner commits a queryable drop fact containing cluster,
  process, source graph/proc provenance, and a bounded representation or digest
  of the dropped fault.
- Restart preserves the fact; no atom or stderr line is the authority for how
  many core faults were dropped.
- A focused saturation proof overflows the channel, observes the durable fact,
  and proves the producer remains nonblocking.
