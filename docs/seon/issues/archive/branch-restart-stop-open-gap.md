---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Serialize branch restart stop and open reconciliation

## Problem

`seon.dev.branch/restart!` read the retained record and stopped its pod before
calling `open!`. Only `open!` acquired the existing `:branch` lifecycle lock,
so another open or close could interleave after pod absence and before restart
reconciliation. A concurrent close could delete the native branch that the
restart then intended to reopen.

## Evidence

Commit `50974ca4` placed `process/stop!` outside the `state/with-lock` owned by
`open!`. The deterministic regression now blocks restart inside the real stop
call, starts a concurrent open, and proves that it cannot pass the existing
kernel-owned branch lock until restart has reconciled the retained intent.

## Owner

`seon.dev.branch` owns one serialized retained-branch transition. Open and
restart share `open-under-lock!`; restart holds the same existing `:branch`
lock continuously across pod stop and open reconciliation.

## Acceptance

Resolved by `60797eaa`. The focused branch/process/CLI checkpoint ran 37 tests
and 232 assertions with no failures. The concurrency proof also verifies that
restart never creates the native branch again and that both serialized callers
finish with the retained record in the ready phase.
