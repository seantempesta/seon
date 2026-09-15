---
type: issue
status: resolved
severity: friction
tags: [issue, render, database, wave/print-path]
---

# Nested database rendering reaches a map-entry walk over datoms

## Evidence — 2026-09-15

P1's isolated HEAD-plus-owned-paths gate at `ff60a3cf7` ran
`seon.render-simplification-test/nested-values-render-their-declared-faces`
with armed contracts. Its nested database rendered a ClassCastException:
`datahike.datom.Datom cannot be cast to java.util.Map$Entry`. The expected
database identity face was absent. Fresh-worker confirmation reproduced it.
The transaction report also rendered raw data instead of its expected face.

This is distinct from the former impossible producer-input contract:
`551d8353c` fixed that contract, and the direct producer succeeds on default.
The [P1 landing note](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md)
records the gate and live producer evidence. The isolated gate excludes
bisect's concurrent edits to `seon.render.value` and `seon.sci.admit`;
there is no claim those in-flight edits retain this failure.

## Owner and acceptance

The structural admission/render selection boundary must select the declared
database face before walking its datoms as map entries. Verify the existing
canonical nested-values regression after the protected owners land. No new
test or alternate renderer is needed.

## Resolution — 2026-09-15

`28e955327` projects registered reference identities before generic structural traversal. Default verifies map? true and sequence element Datom for a database; the corrected public renderer prints its database identity with basis transaction in 125 ms. The existing nested-values regression is included in the orchestrator re-gate request; no final gate result is claimed. Transaction-report face expectations remain a separate semantic boundary in the P1 landing note.
