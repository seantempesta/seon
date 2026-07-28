---
type: issue
status: open
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
