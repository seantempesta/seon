---
type: issue
status: open
severity: friction
tags: [issue, operator, wave/directory-claims]
---

# Report malformed root claims in the reap result

## Problem

`seon.operator/reap-dead-roots!` receives unreadable external claims from its
census but omits them from `:seon.operator.reap/refused`. Its embedded result
can therefore say `:seon.operator.reap/complete? true` while its census says
incomplete; the outer flat error is the only indication that a claim was not
handled. Scheduled execution consequently settles the error arm instead of
the queryable reap-result arm.

The issue's original nil-path diagnosis was false. An isolated instrumented
JVM reproduced the exact `:seon.error/diagnostic-offending [nil]` exception and
the complete trace reaches `src/seon/fs.clj:81`: the public two-arity
`delete-recursively!` self-calls its instrumented three-arity Var with a nil
options map. Both path strings were present. That separate owner defect is now
tracked in
[`delete-recursively-two-arity-refuses-under-instrumentation.md`](delete-recursively-two-arity-refuses-under-instrumentation.md).

Payload from the original scheduled failure remains
`tmp/ctxprobe-reap-error.edn` (print-node EDN, 26 KB; frames
`seon.schedule$fire_due_BANG_` → `settle_BANG_`).

## Evidence

On 2026-09-03, a direct call using the complete request shape assembled by
`src/seon/schedule.clj:603-620` created one valid dead ephemeral root and one
root claim whose `:seon.operator.claim/root` was removed. Before the repair,
the valid root was reaped, but the returned flat error embedded a reap result
with an empty `:seon.operator.reap/refused` vector and
`:seon.operator.reap/complete? true`; the malformed root remained on disk.

The regression at `test/seon/operator_test.clj:368` now proves that the valid
dead root is reaped, the malformed claim remains, its exact claim id/path and
`:seon.operator.claim/malformed-record` cause appear as one
`:seon.operator.reap/unreadable-claim` refusal, the census and reap result are
both incomplete, and the handler returns the ordinary result rail rather than
a flat error.

## Owner

`seon.operator/reap-dead-roots!` owns the translation from claim-census errors
to the operation-specific reap result. `seon.schedule` already supplies both
required root paths in its declared execution context.

## Model-run decision

A maintenance failure should not open a model run automatically. The durable
maintenance receipt plus error fact is the authority; its root-addressed
message should surface in root's next derived context and maintenance report.
An explicit R41 policy dial may opt a deployment into an immediate wake when
the cost is wanted, but the default should not spend an unbounded paid turn to
rediscover a mechanical failure already recorded with evidence. This answer is
design guidance only; changing the shared error-message wake policy is outside
this repair.

## Acceptance

A claim/cluster without a resolvable root is a typed refusal in the reap
RESULT (named, counted), never a nil handed to the deletion owner; the
task settles `:result` on a scratch root with one dead ephemeral root and
one malformed claim; regression in the operator test namespace.
