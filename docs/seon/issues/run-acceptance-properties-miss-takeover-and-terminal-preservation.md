---
type: issue
status: open
severity: blocker
tags: [issue, runtime, testing, database]
---

# Make run acceptance properties cover the claimed state transitions

## Problem

The sealed fresh run suite describes generated interleavings and terminal
receipt preservation, but its properties do not observe those claims. The
green suite therefore did not exercise the invalid takeover and close states
that the contract says are impossible.

## Evidence

- `test/seon/cluster/run_test.clj:133-154` varies process count and round, but
  applies every claim sequentially with `mapv`. It generates no sequence of
  claim, heartbeat, release, expired takeover, close, and stale writes.
- The "live foreign claim is not stealable" example at
  `test/seon/cluster/run_test.clj:156-179` calls the fresh/reacquire request
  shape without observed takeover fields. It never calls the public
  takeover-shaped branch.
- No test supplies an expired observed lease to `claim-tx`.
- No test calls successful `heartbeat-tx` or `close-tx`, races close against
  work, mismatches an agent pointer, or attempts to reclaim a closed run.
- The recovery property at `test/seon/cluster/run_test.clj:223-290` claims
  terminal receipts are untouched, but asserts only that no status remains
  `:running` and that receipt count is unchanged. An implementation that
  rewrote every `:done` and `:error` receipt to `:interrupted` would satisfy
  those checks.

This violates the handbook's property-first rule: a state-transition contract
needs a generated transition sequence and independent database observations,
not generated parameters around one example path.

## Owner

`test/seon/cluster/run_test.clj`, owned by the run contract author. Production
repairs remain in `seon.cluster.run`; tests should express the complete state
machine without weakening schemas.

## Acceptance

- One fixed-seed state-machine property generates every public transition,
  kill/recovery, stale observation, and mismatched relation.
- The property independently queries open/closed state, agent pointer,
  process, epoch, lease, plan, and each receipt identity/status after every
  committed or rejected transaction.
- Expired takeover is exercised; live takeover and closed-run reclaim are
  rejected.
- Terminal receipt identity-to-status mappings are equal before and after
  recovery, except `:running` to `:interrupted`.
- Shrunk counterexamples print the seed, generated transition sequence,
  transaction result, and observed database facts.
