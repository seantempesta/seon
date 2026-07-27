---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, database, agent]
---

# Fence the agent pointer when opening and closing a run

## Problem

The N2 revision removed the independent caller-supplied agent identity, but
`close-call` still does not require the run's owning agent to point at that
run. It conditionally emits the retraction only when the pointer happens to
match and otherwise commits the close. The transition therefore does not prove
the exact connection it claims to settle.

## Evidence

- `src/seon/cluster/run.cljc:419-428` derives the owning agent correctly, but
  uses `cond->` to omit the pointer retraction when the current pointer is
  absent or different. No refusal branch enforces the relation.
- A focused in-memory probe opened and claimed a run, retracted its agent
  pointer, and called `close-tx`. The close returned committed and asserted
  `closed-at` even though the required pointer was absent.
- The model at `test/seon/cluster/run_test.clj:285-334` has no command that
  changes or removes a pointer independently, so
  `invariants-hold?` only observes worlds the model already assumes valid.
- The quarry's `run-fence` asserted the agent pointer before the epoch
  (`src-old/seon/agent/run/core.cljc:108-118`).

This violates the data-model rule that relations are refs and the transaction
rule that a close must atomically prove and remove the relation it settles.

## Owner

`seon.cluster.run/close-call` and the shared run fence. Keep deriving the agent
from the run's own ref, then refuse unless that exact agent currently points to
that exact run.

## Acceptance

- `close-tx` fails if the addressed agent does not point at the addressed run.
- A successful close removes the exact real pointer in the same transaction.
- A relational property generates absent, foreign, and correct pointers and
  proves only the exact connection can commit.

## Closed 2026-07-27

Resolved by `5c95e259c`: `src/seon/cluster/run.cljc:346-374` derives the owning
agent from the run, refuses `::agent-pointer-broken` unless that agent points
to the exact run, and retracts the exact pointer in the closing transaction.
`test/seon/cluster/run_test.clj:616-643` severs the pointer, observes the
refusal, and proves `closed-at` was not committed.
