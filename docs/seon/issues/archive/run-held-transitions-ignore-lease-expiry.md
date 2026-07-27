---
type: issue
status: resolved
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

## Triage 2026-07-27

- **OPEN-CURRENT.** The current shared fence at
  `src/seon/cluster/run.cljc:173-191` checks open state, process, and epoch but
  still accepts no `::now` and reads no `::lease-until`; heartbeat, release,
  close, and plan requests at `:285-405` therefore still permit an expired
  holder.

## Closed 2026-07-27

`held-run` now requires the request's first-party `::now` and refuses
`::lease-expired` unless the exact process and epoch still hold a live lease.
Heartbeat, release, close, and plan make `::now` mandatory.

The run-loop pass creates one instant and threads that same value through
next-work, plan, receipt settlement, release, and close; no transition reads
the wall clock. The seeded state machine includes an expired takeover followed
by the old holder's stale heartbeat, and the focused regression independently
proves all four expired-holder transitions refuse without changing durable
custody.

Evidence:

- `bin/test seon.cluster.run-test seon.cluster.loop-test
  seon.cluster.turn-test` → 25 tests / 126 assertions / 0 failures / 0 errors.
- `bin/test` → 169 tests / 773 assertions / 0 failures / 0 errors.
