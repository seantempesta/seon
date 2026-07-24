---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

# Claimant eval dispatch read a keyword as a map

## Problem

`seon.agent.driver.host/eval-step!` binds
`:seon.agent.driver/disposition` to a keyword such as `:no-dispatch`, then
attempted to read `:seon.agent.driver/disposition` from that keyword again
before its `case`. Every successful execution-plan classification therefore
fell through instead of selecting the existing disposition branch.

R52's interaction writer regression loads the same claimant leaf and exposed
this pre-existing dispatch defect before the interaction phase could be
verified. The fix is the direct class-level correction: dispatch on the
already-derived keyword, without adding another classifier or fallback.

## Acceptance

- `eval-step!` cases directly on the value returned by
  `execution-plan-disposition`.
- Existing no-dispatch, handoff, and eval branches retain their meanings.
- The claimant writer gate loads the host driver and completes a guarded
  authored interaction through terminal database facts.

## Resolution

`eval-step!` now cases directly on the derived disposition keyword. The R52
writer regression then traverses the same claimant namespace and completes an
interaction through the guarded JVM door, terminal result facts, request
transaction provenance, and the database-derived render.

## Proof

- `seon.host-authored-invocation-writer-test`: 1 test, 10 assertions, zero
  failures/errors.
- Focused claimant and reactive-call CLJS gate: 22 tests, 103 assertions, zero
  failures/errors.
