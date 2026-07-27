---
type: issue
status: open
severity: blocker
tags: [issue, runtime, testing, database]
---

# Fence terminal receipt state and observe it in the run model

## Problem

The N2 revision added a real command-sequence model and repaired recovery's
terminal-preservation assertion. Receipt creation and settlement are still
plain upserts, however, and the state-machine invariants never compare receipt
facts with the model. A terminal receipt can return to `:running`, violating
the namespace contract that a form has at most one terminal receipt ever.

## Evidence

- `src/seon/cluster/run.cljc:102-117` registers receipt facts and states the
  terminality rule, but owns no transition that fences status changes.
- `test/seon/cluster/run_test.clj:241-246` generates receipt status commands,
  and `:receipt` at `test/seon/cluster/run_test.clj:391-399` transacts an
  identity upsert directly.
- `invariants-hold?` at `test/seon/cluster/run_test.clj:423-449` checks run,
  pointer, custody, and plan facts, but no receipt identity, epoch, ordinal, or
  status.
- A focused Datahike probe wrote one receipt as `:done`, then upserted the same
  identity as `:running`. Both transactions committed and the durable status
  became `:running`.
- The separate recovery property at
  `test/seon/cluster/run_test.clj:503-589` now correctly compares complete
  terminal entities before and after recovery; that resolved part is not
  reopened here.

## Owner

The one receipt transition owner in `seon.cluster.run`, plus the receipt
projection in `test/seon/cluster/run_test.clj`.

## Acceptance

- Receipt start is absent-to-`:running`; settlement is
  `:running`-to-one-terminal-status under the run's exact epoch.
- No terminal receipt can return to `:running` or change terminal outcome.
- The model independently queries and compares every receipt's identity,
  run, ordinal, epoch, and status after each receipt or recovery command.
- Generated sequences include duplicate start, duplicate settlement,
  conflicting terminal outcomes, stale epochs, and recovery.

## Triage 2026-07-27

- **N3-OWNED.** The N3 receipt-transition contract must replace the direct
  upsert at `test/seon/cluster/run_test.clj:391-399`; fresh
  `src/seon/cluster/run.cljc:426-474` currently owns recovery only, so N3 must
  add absent→`:running` and `:running`→terminal transitions plus the model
  projection before this can close.
