---
type: issue
status: open
severity: friction
tags: [issue, effect, ordering]
---

# Order effect feedback by numeric facts

## Problem

Effect feedback and unanswered background-result selection still use effect id
strings as ordering keys. Multi-effect forms therefore retain the same
two-digit lexical-order failure class fixed for receipts and messages.

## Evidence

`src/seon/effect.clj` sorts pending rows, settled background results, and
duration rows by `:seon.effect/id` or the query's first id field.
`src/seon/cluster/run.clj` sorts unanswered background results by transaction
then effect-id string. Effect rows already declare `:seon.effect/ordinal` and
temporal facts. This was found by the class sweep accompanying commit
`7cfb2435f`; `src/seon/cluster/run.clj` was owned by W2 and was not edited.

## Owner

The effect feedback projection plus the run transaction function's background
result selection.

## Acceptance

- At least twelve effects from one form appear in numeric effect ordinal in
  pending, settled, duration, and unanswered-background projections.
- Every tie is resolved by numeric or temporal facts and numeric entity id,
  never an effect id string.
- The change strengthens the existing effect/run owners without adding a
  second feedback path.
