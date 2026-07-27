---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database, agent]
---

# Fence the agent pointer when opening and closing a run

## Problem

The fresh run transitions accept an agent reference and agent identity as
independent caller values. `open-tx` can attach the run to one agent while
winning another agent's current-run CAS. `close-tx` can close a fenced run
while retracting a different or absent agent pointer because the retraction is
not preceded by a pointer CAS.

A committed close can therefore leave the real agent pointing at a closed run,
preventing its next open.

## Evidence

- `src/seon/cluster/run.cljc:153-174` independently accepts
  `:seon.cluster.run/agent` and `:seon.cluster.agent/id`; no relational
  contract requires them to name the same entity.
- `src/seon/cluster/run.cljc:241-264` fences epoch and process, but it does not
  assert that the supplied agent's current-run pointer is this run or that the
  run's stored agent is the supplied agent.
- A focused in-memory probe opened and claimed a run for its real agent, then
  called `close-tx` with a second existing agent id. The transaction committed
  `closed-at`, while the real agent still pulled as:

  ```clojure
  {:seon.cluster.agent/id "agent-close-mismatch-…"
   :seon.cluster.agent/run {:db/id 26}}
  ```

- The quarry's `run-fence` CAS-asserted the agent pointer before the epoch
  (`src-old/seon/agent/run/core.cljc:108-118`).

This violates the data-model rule that relations are refs and the transaction
rule that a close must atomically prove and remove the relation it settles.

## Owner

`seon.cluster.run/open-tx`, the shared run fence, and `close-tx`. Derive the
agent ref from one identity input and reuse one pointer-plus-epoch fence for
all run work.

## Acceptance

- `open-tx` has one agent identity source and derives the stored ref and CAS
  target from it.
- `close-tx` fails if the addressed agent does not point at the addressed run.
- A successful close removes the exact real pointer in the same transaction.
- Heartbeat, plan freeze, release, and close reuse one run fence instead of
  hand-copying partial CAS shapes.
- A relational property generates mismatched agents and proves that none can
  commit a split run/pointer state.
