---
type: issue
status: resolved
severity: cleanup
tags: [issue, render, config, schema, wave/verification-audit]
---

# Settings HTML infers units from the attribute suffix

## Problem

The new settings renderer strips `-ms` from the attribute name and divides every numeric value with that suffix by 1000, appending `s`. The name is now a semantic unit declaration hidden in renderer code. It separately invents display labels by rewriting namespace and key spelling.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/agent.clj:138–147`
- `commit 20193870c`

## Owner and deletion

Use the schema's declared description and unit/display metadata. If units have no existing declared field, expose raw values with their actual attribute until the owner adds one declaration seam. Delete suffix-based conversion.

Estimated change: 10–20 lines merged with declaration metadata. Audit classes: 3. No production edits for this finding were made by the audit lane.

## Acceptance

Two settings with identical declared units format consistently regardless of spelling. An attribute rename alone cannot change a numeric value's unit or magnitude.

See [the audit](../../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.

## Resolution — 2026-09-15

Commit subject: `Retire audit misc mechanisms and derive settings and API checks` (this commit).

Added one settings-schema display metadata seam with declared labels, divisors, and units. The renderer reads these declarations and uses raw attribute names and values otherwise. The canonical regression gives differently named attributes identical units and confirms an undeclared -ms suffix cannot alter magnitude.

See the [backstop-and-misc landing](../../../prds/context-generation/research/backstop-and-misc-landing-2026-09-15.md) for exact changes and verification boundaries.
