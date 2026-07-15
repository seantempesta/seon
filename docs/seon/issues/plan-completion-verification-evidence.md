---
type: issue
status: open
severity: friction
tags: [issue, agent, database, schema]
---

# Plan completion has no checkable verification evidence

## Problem

`my.plan/done!` can persist `:done` and `:my.plan/completed-at` after an
unchecked or wrong result. The step's expectation is advisory text, so a false
completion becomes durable plan state and can license later durable claims.

## Evidence

`my.plan/done!` accepts only `:my.plan/id`. Its implementation checks that the
step exists, then transacts `:my.plan/status :done` plus `completed-at`; it does
not read `:my.plan/expect` or require evidence that the outcome holds. Existing
tests prove this unconditional transition and rendered “verify before done!”
teaching, not a verification gate.

The plan-preload pilot's ferry scenario closed its only address step before the
remaining outcomes held, then calculated the wrong value `26` where the handed
down expectation was `25` and stored the result as verified knowledge. The
research records plan integrity `0/1` for that scenario and identifies
`done!` as docstring-gated only.

## Owner

The one private `my.plan.internal` transition authority, `my.plan/done!`, and
the schema-owned representation of a falsifiable expectation, registered
verifier, committed evidence refs, and one completion receipt.

## Acceptance

- A step with a falsifiable expectation cannot become durably done from an
  unchecked or mismatching result.
- Completion has one machine-checkable evidence path tied to the step and its
  expectation; exact prose or a self-asserted “verified” label is not evidence.
- A behavioral test demonstrates that a wrong result leaves the step open and
  cannot poison subsequent durable plan state, while matching evidence closes
  it once and remains idempotent.
- The mechanism derives from existing eval/database facts or one schema-owned
  completion record; it does not add a parallel plan ledger.
- Completion asserts the expected owner, status, verifier, and complete
  database coordinate; a stale or concurrent attempt returns data and writes
  nothing.

The grounded authority and evidence shape are specified in
[[docs/prds/agent-runtime-correctness/research/plan-transition-authority-audit-2026-07-15]].
