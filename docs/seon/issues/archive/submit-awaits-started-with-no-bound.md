---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, flow]
---

# `seon.flow/submit!!` awaits `started` with no bound

Found 2026-07-28 by the F1 lane's hook review while making `var-process`
public (not an F1 seam; recorded rather than fixed mid-wave).

## Observed

`src/seon/flow.clj` `submit!!` derefs the `started` promise
unconditionally (`(let [started-at @started] ...)`) BEFORE applying
`time-limit-ms` to the `result` promise. `started` is delivered only
when the submission's work-fn begins executing (or its dispatch
throws). If every bounded `:compute` worker is wedged, a queued
submission never starts and `submit!!` parks forever — the
`time-limit-ms` bound never engages.

## Expected owner

`seon.flow` — the one `:compute` door. The wait-for-start is honest
backpressure by design (a full queue parks the submitter), but "all
workers wedged" is an observable state the door already accounts
(`::wedged-submissions` in `capacity-facts`), so the unbounded deref is
a clock standing in for an event the launcher can observe.

## Acceptance

A submission queued behind fully-wedged workers ends as a flat
`:seon.error` value (or a `::time-limit` outcome) within a bound, with
a regression in `test/seon/flow_test.clj`; no ordinary backpressure
wait is converted into a spurious timeout.

## Triage 2026-07-29

**PRESSING — fold into [[agent-turns-bypass-the-bounded-compute-door]].** This
note and [[flow-submit-waits-forever-before-time-limit]] name the same startup
wait in `submit!!`; preserve both evidence trails during the one fix wave, then
close them together.

## Resolution

Resolved by commits `f14a6cf7a` and `24544c1d1`.

The launcher publishes a single terminal submission event rather than making
callers await an independently delivered `started` promise. An accepted
submission queued behind fully occupied capacity therefore settles through
the declared time limit, and a later executor start observes its cancelled
state instead of running it.

`submission-time-limit-covers-the-pre-start-wait` holds the sole admitted
evaluation with latches, times out the queued caller as a flat
`::seon.flow/time-limit` outcome, then releases the owner. The complete
focused flow suite passes with zero failures or errors.

Final integrated proof: `bin/test` ran 510 tests containing 2,080 assertions
with zero failures and zero errors.
