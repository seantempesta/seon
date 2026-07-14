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

## Owner

`seon.instrument` and the one Promise-resolution boundary in `seon.eval`.

## Acceptance

Define a measured threshold and prove one Promise-aware validation mechanism
that preserves async identity/arity, validates resolved values once, and does
not reintroduce global reinstrumentation or a giant context warning.
