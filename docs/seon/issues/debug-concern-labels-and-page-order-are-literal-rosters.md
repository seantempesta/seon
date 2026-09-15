---
type: issue
status: open
severity: friction
tags: [issue, render, schema, web, wave/verification-audit]
---

# Block ordering and concern labels are literal rosters again

## Problem

`page-order` contains eight explicit relationship/identity cases. `emission-label` contains six renderer-symbol labels plus six attribute/name branches; it walks source forms to classify the concern. Its renderer list includes `seon.plan/render-plan-ai`, while compact plan results now use `seon.plan/format-plan-ai`, and its settings fallback still names `seon.agent/effective-settings`. This repeats the hand-labelled-block class previously resolved in the linked archived issue. The old resolved observation stays archived; this is a new recurrence at different owners.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/web.clj:355–371`
- `src/seon/render/transcript.clj:1507–1527`
- `docs/seon/issues/archive/debug-page-blocks-are-hand-labelled-and-squeezed.md:16–48`

## Owner and deletion

Remove the renderer-symbol map, form-scanning classification, and code-literal page ranks. Read titles/order from the existing schema/relationship declaration seam, extending that seam only if the required fact is actually absent.

Estimated change: 35–55 lines merged into declarations. Audit classes: 3. No production edits for this finding were made by the audit lane.

## Acceptance

A newly declared concern obtains its title and order without changes to web/transcript code. Renaming a renderer while retaining the declaration does not change its label. No source-token search is needed to identify a block.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
