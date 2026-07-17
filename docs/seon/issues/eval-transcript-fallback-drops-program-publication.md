---
type: issue
status: open
severity: blocker
tags: [issue, architecture, agent, database, flow]
---

# Keep eval outcome and program publication atomic

## Problem

`seon.eval/record-eval!` first tries to commit an eval outcome and its accepted
program-graph rows in one transaction. If that transaction fails for an
eligible non-stale reason, it retries without the program rows and may report a
committed eval result whose accepted functions, schemas, tests, or namespace
facts will not survive restart. A later transaction attempts to annotate that
split publication with `:seon.eval/record-error`, but the annotation does not
restore atomicity.

## Evidence

- `src/seon/eval.cljs:3193-3205` documents the transcript-first retry as part
  of the public recording behavior.
- `src/seon/eval.cljs:3263-3300` builds the primary transaction by combining
  the turn/eval row with the accepted program tee.
- `src/seon/eval.cljs:3323-3328` already treats stale database movement as a
  retryable acquisition/publication problem and correctly avoids the no-tee
  fallback.
- `src/seon/eval.cljs:3343-3389` retries `transact-record!` with an empty tee,
  records that the program rows were dropped and will not survive restart,
  and returns `::tee-recorded? false` after the transcript-only commit.
- [[multi-form-eval-order-is-not-durable]] mentions the fallback only as a
  consumer of a future ordinal. It does not own or resolve the split program
  publication root cause.

## Owner

`seon.eval/record-eval!` and the database authority's accepted-program
transaction own one atomic receipt, outcome, turn connection, and program
publication. Recovery may reacquire database facts and recompile frozen data,
but it must not rerun agent code or convert failed program publication into a
successful transcript-only eval.

## Acceptance

- One successful transaction terminalizes the eval receipt and publishes the
  turn connection, outcome, and every accepted program row.
- A forced program-row schema, conflict, or admission failure publishes none
  of those success facts and returns an established error/recovery value.
- Recovery may reacquire current database facts and rebuild a transaction from
  the frozen execution result without executing the agent form again.
- No success response, result handle, or durable eval row claims accepted code
  that is absent after restart.
- Focused transaction-history proof shows one transaction for every accepted
  eval/program fact, and restart proof reloads the accepted definition.
