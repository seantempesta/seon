---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, research]
---

# Inspect offline proof accepted a dead database golden

## Problem

The fixed database good fixture no longer satisfied the maintained structured
database workflow oracle, but the sixteen-arm offline proof still exited zero.
It treated successful Inspect execution as sufficient and did not assert the
expected reduced scorer metrics.

## Evidence

The pre-fix `milestone db good` arm produced a
`milestone_scorer:mean/accuracy` of 0.0. The fixture contained only a measure
schema, one incomplete transaction, and a query. It omitted the identity
schema, three requested records, the user report, and completion. The proof
runner returned zero because no Inspect run had a failed status.

## Owner

The fixed fixture is owned by
`src-inspect-ai/src/seon_inspect/tasks/milestone_lift.py`. The executable
offline proof contract is owned by
`src-inspect-ai/src/seon_inspect/offline_proof.py`.

## Acceptance

- The database good and bad fixtures score 1.0 and 0.0 through the real
  milestone scorer.
- Every offline proof run declares an expected primary reduced mean.
- A failed run, missing metric, or changed expected mean makes the proof exit
  nonzero.
- Focused regression tests and the real sixteen-arm proof pass.

## Resolution

The resolving commit aligns the good fixture with the complete maintained
workflow and turns the proof runner into an assertion over all sixteen primary
mean metrics. The focused suite passes 55 tests. The real proof exits zero,
including database good at 1.0 and database bad at 0.0.
