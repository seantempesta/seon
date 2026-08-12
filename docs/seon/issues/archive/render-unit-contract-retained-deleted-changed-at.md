---
type: issue
status: resolved
severity: blocker
tags: [issue, render, schema, agent-runtime]
---

# Remove the retired changed-at field from render units

## Problem

W2 replaced per-entity `:seon.render.walk/changed-at` scans with root-pull read
evidence and membership diffs, but the render-unit schema still required that
deleted field. Once root acquisition accepted the live projection-neutral
request, whole-image instrumentation stopped `neighborhood` on its first
otherwise valid output.

## Evidence

The first scratch-root attempt after commit `66bf3fca3` passed
`root-acquisition`, then recorded a durable
`:seon.instrument/contract-violated` fault naming `neighborhood`'s output and a
missing `:seon.render.walk/changed-at`. Current `neighborhood` returns stable
lookup, path, depth, distance, and rendered output from the root acquisition;
it no longer derives an entity transaction maximum. The W2 design explicitly
states that root read evidence replaces `changed-at` as the invalidation
oracle.

## Owner

The render-unit declaration and the one synthetic fleet unit in
`seon.render.web`.

## Acceptance

- A root-acquired neighborhood validates without a `changed-at` field.
- No live or synthetic render unit manufactures a zero value for that retired
  field.
- One instrumented scratch-root message reaches an attempt-ready, non-empty
  prompt with zero contract-violation facts and no provider call.

## Resolved 2026-08-12

Commit `d4e1ec9c6` removes the retired declaration and synthetic zero, extends
the focused root-acquisition proof through neighborhood validation, and
narrows the whole-image live regression to the pre-provider acceptance seam.
The scratch-root proof passed 1 test and 76 assertions with a captured non-empty
prompt and zero contract faults.
