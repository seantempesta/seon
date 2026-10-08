---
title: "Card RT-3 An agent holds several assignments; completing or giving back retracts one"
type: reference
status: active
created: 2026-10-08
---

# RT-3 An agent holds several assignments; completing or giving back retracts one

Ledger §3.28 RT-3 (groups E, then D for the block line). Q-RT1 (recommended option assumed). Lane: Sonnet.

## What becomes true
- Holding a task is its assignment's presence. An agent may hold several; root usually does.
- The transaction that records a task complete (F5's completion: the eval request recording the green that completes
  it; for a merge request, the merge transaction, RT-10) or given back (RT-13) retracts the assignment
  (`[:db/retractEntity assignment]`); history keeps it.
- `seon.task/held` answers the agent's current assignments, oldest first; `seon.task/assignment` answers the oldest
  held one on the branch instead of refusing several; `seon.task/agent-task` answers that one.
- The current-state block shows the held task as today and, when more are held, `:seon.task/also-held` with their
  handles and count (data, no prose).

## Functions
- NEW `seon.task/held` `[database agent-id]` → `[:vector :seon.task/handle]`: one query on current
  `:seon.task/agent` datoms ordered by their transaction.
- CHANGE `seon.task/assignment` (the `(= 1 (count assigned))` cases), `seon.task/agent-task` (latest → oldest held).
- CHANGE F5's completion writer (`seon.task`'s outcome path, as F5 lands it) to include the retraction.
- CHANGE `seon.context` block assembly: one key for the other held tasks.

## Deleted
`assignment`'s `:unassigned` refusal for several assignments.

## Proof
`seon.task-ingestion-test/an-agent-holds-several-assignments-oldest-first` (new): two red tasks assigned to one agent;
`held` answers both in assignment order; `assignment` answers the first; completing the first (its proof run green
through the step) retracts it and `assignment` answers the second.

## Cost
`held`: one indexed query over current assignments, O(held), never O(history).

## Dependencies
F5 (the completion writer); CX-1 (the block, group D). Commit with
`--test seon.task-ingestion-test --test seon.context-test`.
