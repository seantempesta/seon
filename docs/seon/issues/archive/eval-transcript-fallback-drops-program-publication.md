---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, agent, database, flow]
---

# Keep eval outcome and program publication atomic

## Problem

`seon.eval/record-eval!` first tried to commit an eval outcome and its accepted
program rows in one transaction. If that transaction failed for an eligible
non-stale reason, it retried without the program rows and could report a
committed eval result whose accepted functions, schemas, tests, or namespace
facts would not survive restart. A later transaction annotated that split
publication with `:seon.eval/record-error`, but the annotation did not restore
atomicity.

## Evidence

- `record-eval!` already built the receipt terminalization, turn connection,
  eval outcome, and accepted program rows as one transaction.
- A stale database value already returned without writing and allowed the
  caller to reacquire database-dependent inputs and recompile from the frozen
  execution result.
- The removed branch retried the transaction with no program rows, then wrote
  `:seon.eval/record-error` separately and returned a transcript-only success.

## Owner

`seon.eval/record-eval!` and `eval-batch!` own one atomic receipt, outcome,
turn connection, and accepted-program transaction. Recovery may reacquire
database facts and recompile frozen data, but it never reruns agent code or
converts failed program publication into a successful transcript-only eval.

## Acceptance

- One successful transaction terminalizes the eval receipt and publishes the
  turn connection, outcome, and every accepted program row.
- A program-row admission failure publishes none of those success facts and
  returns the established database error value.
- Stale-coordinate recovery reacquires current database facts and rebuilds the
  transaction from the frozen execution result without executing the form
  again.
- No success response, result handle, or durable eval row claims accepted code
  that is absent after restart.

## Resolution

`record-eval!` now has one publication outcome. Its existing transaction either
commits the receipt, terminal outcome, turn connection, and accepted program
rows together, or returns the database error. The transcript-only retry, later
error-stamp transaction, `:seon.eval/record-error` schema, fallback classifier,
and redundant tee-recorded response state are removed. The existing stale
coordinate loop remains the only retry: it reacquires authority inputs and
recompiles the transaction from the already-executed frozen result.

## Verification

- From one coordinated frozen source digest,
  `bin/test-cljs --test=seon.eval.receipt-test` passed 11 tests and 49
  assertions with zero failures, errors, or warnings.
- The forced program-row failure performs one transaction containing the
  receipt CAS, terminal eval row, and program row; after rejection it performs
  no transcript or error-stamp write and returns the original database error.
- The stale-coordinate falsifier performs two authority acquisitions and two
  transaction compilations while both publication attempts retain the
  identical already-executed result value.
