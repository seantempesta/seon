---
type: issue
status: resolved
severity: friction
created: 2026-09-16
tags: [issue, database, test]
---

# The proposed issue test guard loses its before value and activation

## Verdict — 2026-09-16

Resolved by `a6fee5b31`. The writer realizes immutable membership, assignment,
creator and identity maps before expanding work. Activation comes from assignment
history and creator authority from its first historical assertion. The canonical
`seon.issue-settlement-test/started-issue-tests-retain-historical-authority`
regression passed 33/0/0 after adoption of the declaration on default (run 73966,
03:42:38Z, 5970 ms). It verifies the two-transaction creator-unassign then
worker-retract case as well as direct and nested retractions. An ordinary live
worker retraction of the issue-family test returned `:seon.db/retention-refused`
and left the basis unchanged. Exact bounds and publication limitations are in
the [landing note](../../../prds/steward-platform/research/issue-settlement-2026-09-16.md).

## Problem

The proposed guard in
[the settlement integration issue](issue-test-preservation-and-settlement-need-writer-integration.md)
cannot retain a transaction-function database as an immutable before value.
Its activation rule also permits removing assignment in one transaction and
removing the success tests in the next. These are defects in the proposed
construction; the guard has not been implemented.

## Evidence

MCP JVM probes on default, based on HEAD `28038838c`, used Datahike `with`
on the existing issue 43695. No issue changes were committed.

1. A transaction function captured `before`; a later operation changed the
   title; the final function read the changed title from BOTH `before` and
   `after`. The original committed title remained unchanged.
2. Materializing the old test set fixes that first problem, but the proposed
   before-or-after assignment check accepts two successive speculative
   transactions: retract assignment while retaining test 45221, then retract
   all tests. Both decisions were true. The committed issue remained assigned
   with test 45221.

Datahike creates transient indices in
`reference-code/datahike/src/datahike/db.cljc:198`; its transaction loop enters
the transient database at
`reference-code/datahike/src/datahike/db/transaction.cljc:1230` and passes that
value to functions at line 1153. Capturing the enclosing value does not freeze
the indices.

Exact forms and complete small results are in the
[settlement landing note](../../../prds/steward-platform/research/issue-settlement-2026-09-16.md)
and its adjacent probe script. Live schema inspection at basis 536871615 found
neither the proposed append-only property nor `:seon.issue/created-by`.

## Owner

The `seon.db` transaction admission seam and `seon.issue` lifecycle writer.
Preserve immutable guard inputs at the writer, and make the assignment and
creator facts supporting the invariant non-removable by the worker. An
alternative is a dependency-owned final-report validation seam; it must not
re-evaluate transaction functions to discover their effects.

## Acceptance

The canonical class regression must cover nested transaction functions,
direct and whole-entity retractions, two-transaction assignment removal,
creator substitution, and creator-authorized test removal with a nonempty
remaining set. Refusals retain named issue/test evidence and leave the
database unchanged. The design must explicitly account for indexed issues
whose creator is not recorded, without guessing an author.
