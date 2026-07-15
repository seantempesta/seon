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

The 2026-07-15 exact-source audit fetched official ClojureScript tag
`r1.12.145` at `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` and read Shadow
`3.4.10` at `d3c04691952aa9ea33f7287ffe9a2b3109c1e510`. ClojureScript emits
native async outer functions and async fixed/variadic accessors with the normal
callable-shape properties Malli already edits. Shadow does not replace those
parser/emitter paths. An isolated Malli `0.20.0` probe reproduced a valid async
multi-arity call rejecting as `:malli.core/invalid-output` because stock
instrumentation validates the Promise object synchronously.

## Owner

`seon.instrument` and the one Promise-resolution boundary in `seon.eval`.
Implementation must remain in `seon.instrument`; `seon.eval` consumes the
resulting Promise but does not become a second contract validator.

## Acceptance

Make the census account for every async public contract and prove one
Promise-aware validation mechanism that preserves async identity/arity,
validates resolved values once, returns structured failures, and does not
reintroduce global reinstrumentation or a giant context warning. Any remaining
exception must be explicit, source-grounded, and measured rather than silently
excluded from coverage.

Plan and dependency evidence:
[[../prds/agent-runtime-correctness/research/async-contract-exact-source-implementation-audit-2026-07-15]].
