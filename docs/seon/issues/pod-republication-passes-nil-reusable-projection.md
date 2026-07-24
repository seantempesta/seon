---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime]
---

# Keep pod republication's reusable projection inside its contract

## Problem

A source rebuild against an existing cluster can start publication with no
reusable projection, then call the instrumented
`seon.runtime.admission/committed-projection` function with `nil` even though
its input contract requires a map. Development core-fault policy drains the
pod.

## Evidence

The claimant2 restart at source `fdba88aad` reached publication `2`. Two
concurrent acquisition traces each completed 2,389 schema rows and 920
function-contract rows, then both faulted with
`:malli.core/invalid-input`; explain path
`:seon.runtime.admission/reusable-projection`, got `nil`, expected `:map`.
The pod recorded the fault and exited. A clean cluster reset at the same source
completed publication `1`, proving the defect is the reuse/republication path,
not the committed corpus. Evidence is in
`tmp/orchestrator/claimant2-gate.log`.

## Owner

`seon.runtime.admission` owns construction and reuse of the one committed
projection. Align the optional reuse input and function contract at that
boundary; do not create a second projection builder.

## Acceptance

- Restarting a ready retained cluster performs exactly one publication.
- Absence of a reusable projection is represented inside the real contract.
- The pod returns to `:available` without a core fault or duplicate acquisition.
