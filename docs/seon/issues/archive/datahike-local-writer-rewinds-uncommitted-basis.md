---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow, architecture]
---

# Preserve the uncommitted basis inside Datahike LocalWriter

## Problem

Datahike's LocalWriter rewinds the locally threaded uncommitted database
value's `:max-tx` to the connection's older committed `:max-tx` before the
next queued transaction. Multiple pipelined transactions carrying the same
`:datahike/expected-basis-t` can therefore pass the apply-time precondition
and commit, violating optimistic concurrency.

## Evidence

U3's full writer gate on 2026-07-23 failed
`expected-basis-is-enforced-inside-the-serialized-writer`: both requests
pinned to the same database value succeeded and both domain facts landed.
The raw transcript is `tmp/orchestrator/u3-gate-full.log`.

The direct dependency probe in
`tmp/orchestrator/u3-probe-expected-basis-direct.log` leaves two
`d/transact!` calls uncompleted with expected basis `536870913`. Both report
success, both return transaction `536870914`, and both `"left"` and `"right"`
facts land. This removes the Seon executor, request cache, and UDS transport
from the failure.

`reference-code/datahike/src/datahike/writing.cljc` checks
`:datahike/expected-basis-t` against the `old` database value's `:max-tx`.
However, the processing loop in
`reference-code/datahike/src/datahike/writer.cljc` threads each successful
`:db-after` as `old`, then replaces its `:max-tx` with
`@(:wrapped-atom connection)` whenever they differ. During commit batching
that atom still names the older committed value, so the second queued apply
sees the first transaction's data but the old basis transaction.

The failure appears only after Seon's artificial one-mutation-per-database
ceiling is removed. Re-serializing expected-basis transactions in
`seon.db.executor` would conceal the dependency defect and discard the
intended batching gain.

## Owner

The maintained Datahike fork owns the LocalWriter processing-loop database
value and the apply-time expected-basis contract. After the direct probe
proved the defect, the orchestrator granted U3 a narrow fork repair rather
than a Seon-side workaround.

## Acceptance

- A direct LocalWriter regression enqueues two uncompleted transactions with
  the same `:datahike/expected-basis-t`; exactly one commits and the other
  returns `:transaction/stale-basis`.
- Distinct successful queued transactions never reuse one transaction id.
- The processing loop preserves the full uncommitted `db-after` between
  queued writes without regressing import or other writer operations.
- Seon's
  `expected-basis-is-enforced-inside-the-pipelined-writer` regression passes
  with ordinary mutations still pipelined.
- Probe A retains a rising throughput curve and the forced-kill recovery
  regression remains green.

## Resolution

The maintained fork commit `9c356e32a0f2b0afcd41ce5000cba2a575a59a8a`
makes the import-era basis synchronization monotonic: an externally committed
basis may advance the processing loop, but the older connection value can
never rewind its threaded uncommitted `db-after`. The parent pins that fork
revision in `3b63b2393`.

Fork evidence:

- the deterministic JVM regression blocks the first commit until two
  uncompleted `d/transact!` calls reach LocalWriter; exactly one report
  succeeds and the other carries `:transaction/stale-basis`;
- the focused regression passes under `clj-pss`, specs, and `clj-hht`;
- the complete `datahike.test.writer-error-test` namespace passes under all
  three profiles.

Seon evidence:

- `tmp/orchestrator/u3-probe-expected-basis-direct-postfork.log` records one
  success at transaction `536870914`, one
  `:transaction/stale-basis` rejection, and only `"left"` stored;
- `tmp/orchestrator/u3-gate-writer-integration-postfork-2.log` passes 16 tests
  and 120 assertions;
- `tmp/orchestrator/u3-probe-aprime-postfork.log` retains 32.04x throughput at
  queue depth 64 relative to depth 1;
- `tmp/orchestrator/u3-gate-recovery-postfork.log` passes the real forced-kill
  recovery regression.

The fork commit remains local. Pushing `9c356e32` to
`git@github.com:seantempesta/datahike.git` is a recorded morning item for the
repository owner.
