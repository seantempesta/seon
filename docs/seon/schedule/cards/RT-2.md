---
title: "Card RT-2 Assignment checks inside any producer's transaction"
type: reference
status: active
created: 2026-10-08
---

# RT-2 Assignment checks inside any producer's transaction

Ledger §3.28 RT-2 (group E). Lane: Sonnet.

## What becomes true
A producer of work (a merge request, an error opening, a give-back) assigns in its own home transaction with the same
guarantee `assign!` gives: the task exists, has proofs, and not every proof is verified on the home.

## Functions
- NEW `seon.task/assign-tx` `[database {:seon.task/assigned handle :seon.task/agent agent :seon.task/branch branch}]`
  → tx-data, invoked as `[:db.fn/call #'seon.task/assign-tx request]`: the body of `assign!`'s `:else` checks
  (`task-revision`, `task-edges`, `seon.test/verified?` per proof) over the in-transaction value; a refusal throws the
  refusal map in `ex-info` (the writer's thrown refusal, as `adopt-tasks-tx` does); success is the one assignment map
  `assign!` writes today. Contract `[:=> [:cat :seon.db/database-value :seon.task/assign-request] :seon.db/tx-data]`.
- CHANGE `seon.task/assign!`: `(written connection [[:db.fn/call #'assign-tx request]])` through `refusal-data`, so
  the two never diverge; its answer is unchanged.

## Deleted
`assign!`'s inline checks (moved, not copied).

## Proof
`seon.task-ingestion-test/an-assignment-in-a-producers-transaction-refuses-all-green` (new): a transaction carrying
`assign-tx` for a task whose proofs are all verified refuses `:proofs-green` and commits nothing; for a red proof it
commits the assignment beside the producer's own datoms. Existing `assign!` tests stay green.

## Cost
Same reads as `assign!` (one revision, one edges query, one `verified?` per proof), now inside the writer: follows the
task's proofs; measure `assign!` before and after (41-54 ms today, merge.md §8).

## Dependencies
None. Commit: `bin/lane commit LANE -m "RT-2: assignment checks as a transaction function" --test seon.task-ingestion-test src/seon/task.clj resources/seon/schemas/seon.task.edn test/seon/task_ingestion_test.clj`
