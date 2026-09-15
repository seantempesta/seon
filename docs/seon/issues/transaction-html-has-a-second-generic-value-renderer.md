---
type: issue
status: open
severity: friction
tags: [issue, render, database, wave/verification-audit]
---

# Transaction HTML adds a second generic value renderer and erases distinctions

## Problem

The new `transaction-value-html` recursively prints arbitrary maps and collections instead of using the value renderer. It drops `:db/id`, strips keyword namespaces, and treats any two-element vector beginning with a qualified keyword as a lookup ref, showing only its second element. Thus the ordinary tuple `[:sample/unit 5]` becomes `5`, and `:left/id` and `:right/id` become identical labels. The datom renderer also never displays the subject `:e`, so equal attribute/value changes on different entities cannot be distinguished in the detail list. These transformations follow directly from the source; no live transaction page was available.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/db.clj:2614–2627, 2650–2661 at 0c70a1cb4`
- `src/seon/render/value.clj:524`

## Owner and deletion

Delete the generic recursive formatter. Give scalar/collection payloads to the existing HTML value projection with the actual database and reference metadata. Keep domain-specific transaction labels and expose each changed subject's identity.

Estimated change: 14–30 lines deleted/merged. Audit classes: 1. No production edits for this finding were made by the audit lane.

## Acceptance

Render ordinary two-element tuples, refs, namespaced-key collisions, retracted refs, and identical changes on two entities through canonical transaction reports. Each remains distinguishable without introducing another lookup-vector heuristic.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
