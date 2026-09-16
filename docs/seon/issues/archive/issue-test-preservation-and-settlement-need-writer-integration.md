---
type: issue
status: resolved
severity: friction
created: 2026-09-16
tags: [issue, database, agent, test]
---

# Enforce issue success tests and verified settlement at their owners

## Verdict — 2026-09-16

Resolved by `d132df212` (P5) and `a6fee5b31` (P6). The canonical in-process
regressions `seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion`
and `seon.issue-settlement-test/started-issue-tests-retain-historical-authority`
verify 19 and 33 assertions respectively. The writer uses materialized snapshots
and assignment/creator history, correcting the dated proposal below.

Live default issue 43695 acquired green run 70922 and completed its verification
step with resolved-tx in transaction 536871824. After schema adoption, an ordinary
worker retraction was refused with the issue and test named; its basis did not
change. The full live system-turn proof has a separate
[generated-read boundary](../issue-status-generated-read-depends-on-turn-taking.md).
Publication seal convergence and the orchestrator's serial gate remain explicit
verification boundaries in the
[landing note](../../../prds/steward-platform/research/issue-settlement-2026-09-16.md).

## Problem

The issue family has additive tests and atomic worker creation. Direct
database writes can still remove success tests, and plan completion does not
write the issue's resolved transaction. The owner explicitly deferred these
changes on 2026-09-16 while approving indexing and inspection.

## Evidence

`src/seon/db.clj:2810` preflights assertions rather than expanded transaction
effects. `src/seon/plan.clj:554` records step completion. `src/seon/turn.clj`
calls `seon.plan/settle-call` at its existing settlement transitions.
`seon.issue/start-tx` refuses an empty test set; `seon.issue/tests-tx` only
adds refs. Neither API can enforce the direct database write invariant.

## Owner and proposed design

Declare `:seon.db/append-only-after :seon.issue/agent` on the tests attribute.
This is a schema property, not a stored issue state. The supplied projection
lets transaction admission derive guarded attributes and the activating
attribute; do not maintain an issue-specific attribute roster.

Use one `:db.fn/call` guard at the issue writer for domain requests. Extend
the existing database admission owner to wrap admitted work in a transaction
function. Capture the before value inside Datahike's writer, expand the
requested transaction, then append a final guard transaction function. It
compares the final test refs against the before refs for every affected
issue that was or becomes assigned. Require a nonempty final set and retain
every prior test. Checking both before and after assignment prevents
retracting the assignment to evade the guard. Checking final facts covers
maps, retractions, entity retraction, CAS and nested transaction functions
without interpreting a second transaction language. Refusal aborts the
whole transaction. Derive affected entities from the transaction database
change; carry the projection and before database into the final guard.

Before the existing plan settlement transaction, run the issue tests with
`seon.test/run` using the calling cluster's connection and declared execution
bound. Never run tests inside a Datahike transaction function. The completion
query reads the resulting evidence. Once reach-digest's verified predicate
is integrated, require current verification for every test; do not infer
verification from another test's latest digest. Extend the one plan
completion seam to assert `:seon.issue/resolved-tx` in the same transaction
as the issue-backed step's completion. Indexed Markdown lifecycle remains
authored evidence; do not silently rewrite a file from settlement.

## Acceptance

One canonical regression must prove atomic refusal of removing the last or
any earlier test, including nested transaction functions and retracting the
assignment. Additions succeed. A real in-process test run followed by
settlement records step completion and issue resolution together; missing,
red, stale or interrupted results cannot complete either. Cover absence of
tests explicitly. These owners remain outside the issue-family lane.
