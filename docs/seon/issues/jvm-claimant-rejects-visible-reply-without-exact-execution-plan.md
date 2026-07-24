---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime]
---

# Plan a visible JVM claimant reply on an inspected tier

## Problem

The JVM claimant can persist a successful model response containing valid
agent forms, then reject the parsed reply because no inspected tier yields an
exact execution plan. The run closes visibly and releases custody, but no eval
receipt is admitted.

## Evidence

The source-frozen claimant2 agent `smooth-apes-relate` produced run
`dwvphar4i9yf`, turn `cw9vv09zcp8x`, and attempt `sj29e811vgsg`. JVM host
workload PID `50645` persisted DeepSeek HTTP 200 and a 163-byte reply containing
the requested message and completion forms. The attempt is `:success`; the
turn then closed `:error` with:
`The parsed reply has no exact execution plan on an inspected tier.`

No eval receipt exists. The run is closed and claimant/current-run custody is
absent, so this is not a wedge. Exact database values, claim/phase histories,
and verbatim reply evidence are in
`tmp/orchestrator/claimant2-gate.log`.

The source-frozen `planschema` run `q5ddb6i4pp4z` narrows the remaining class.
The same JVM claimant successfully executed six preceding forms, including
`my.plan/plan!`, three schema registrations, a transaction, and its read-back.
The final single registered form
`(seon.agent.lifecycle/complete "PLANSCHEMA_ALIVE")` alone produced the same
no-exact-execution-plan refusal. The turn became `:published`/`:error`, the run
closed, and custody was absent, so this remains a placement/projection defect
rather than a wedge. Evidence is in
`tmp/orchestrator/planschema-gate.log`.

## Owner

The derived `plan-execution` acquisition/enforcement boundary in
`seon.agent.driver.host` owns the inspected-tier disposition. It must consume
the canonical program graph and artifact inventories; do not add a symbol
allowlist or bypass for lifecycle/message forms.

## 2026-07-24 status

The no-roots class fix deliberately does not weaken this exact-plan boundary.
A reply with no executable roots is classified by `plan-execution` as
`:no-roots` and receives the explicit `:no-dispatch` disposition; the former
driver-local pre-classifier is deleted. A reply containing an unresolved
executable root still returns the existing steering error before phase or
receipt writes.

This closes the adjacent formless-reply classification defect, not this
issue's registered-form defect. The two visible lifecycle forms in Acceptance
still need one exact JVM execution plan and two successful receipts. Focused
portable planner coverage is green; the current-artifact writer gate and the
source-frozen live re-drive remain pending.

## Acceptance

- The same two visible registered forms derive one exact JVM execution plan.
- The claimant writes two terminal successful eval receipts and completes the
  run.
- A genuinely unavailable or unresolved function still returns the existing
  flat steering error and releases custody.
