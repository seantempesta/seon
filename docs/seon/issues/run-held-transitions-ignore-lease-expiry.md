---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database, testing]
---

# Fence held-run transitions by the live lease

## Problem

The shared `held-run` transition fence checks open state, process, and epoch,
but not whether the lease is live. Heartbeat, release, close, and plan requests
do not carry `now`. An expired holder can therefore renew itself, freeze a
plan, release, or close after its custody has lapsed.

## Evidence

- `src/seon/cluster/run.cljc:230-248` calls the result “exact live custody” but
  never reads `::lease-until` or a clock.
- The request schemas at `src/seon/cluster/run.cljc:342-455` omit `::now` for
  every transition using `held-run`.
- A focused Datahike probe created a run whose lease had expired at the probe's
  current time. `run/expired?` returned true; `heartbeat-tx` nevertheless
  committed a later lease, after which `run/claimed?` returned true.
- The model oracle at `test/seon/cluster/run_test.clj:269-281` intentionally
  treats holder+epoch as sufficient. Its heartbeat generator always supplies a
  future new lease and never asks whether the old lease was live, so the model
  agrees with the defect.

## Owner

The `seon.cluster.run/held-run` transition family and its model oracle.

## Acceptance

- Every operation that requires custody decides from the mid-transaction
  database value whether the exact process and epoch still hold a live lease at
  the supplied first-party time.
- An expired holder cannot heartbeat, plan, release, or close; an eligible
  takeover increments the epoch and permanently fences the old holder.
- The generated state machine emits commands on both sides of the lease
  boundary, including a stale heartbeat racing an expired takeover.
