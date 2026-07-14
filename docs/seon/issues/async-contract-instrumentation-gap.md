---
type: issue
status: open
severity: friction
tags: [issue, schema, agent]
---

# Async structural functions bypass contract validation

## Problem

Structural async functions intentionally opt out of ordinary Malli wrapping,
so their resolved values are not validated through the same contract boundary.

## Evidence

The archived dual-path audit's C40 row retains this measured gap. The removed
root instrumentation-gaps context listed the symptom but did not fix the
Promise-aware validation problem.

A safe read-only default-pod census on 2026-07-14 found 747 program contracts
and 95 structurally async contracts excluded by
`seon.instrument/async-unwrappable?`, including database, lifecycle, run, turn,
eval, provider, and `my.plan` functions. `coverage-gaps` returned zero because
those functions are excluded from its denominator. The existing
`injecting-fschema` validates resolved output for a simple fixed-arity `:=>`,
while variadic, multi-arity, and `:function` shapes remain outside that owner.

## Owner

`seon.instrument` and the one Promise-resolution boundary in `seon.eval`.

## Acceptance

Make the census account for every async public contract and prove one
Promise-aware validation mechanism that preserves async identity/arity,
validates resolved values once, returns structured failures, and does not
reintroduce global reinstrumentation or a giant context warning. Any remaining
exception must be explicit, source-grounded, and measured rather than silently
excluded from coverage.
