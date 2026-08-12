---
type: issue
status: open
severity: blocker
tags: [issue, render, context, performance]
---

# Root pull is not yet the walk membership oracle

## Problem

W2 now has the schema-derived selector, one-read root acquisition, and stable
membership diff in `src/seon/render/walk.clj`, but the surviving
`neighborhood` and `history` call paths do not consume
`:seon.render.walk/root-acquisition`. Supplying a root acquisition therefore
does not replace the existing per-entity discovery: the old walk still calls
`eid-of`, `concrete-entity`, `refs`, `entity-last-changed`, and the related
queries/scans.

The in-flight exact-staleness integration exposed this by passing the root
acquisition into `render.walk/history` while no reader of that key existed.
Committing that integration would have paid for the new pull and then repeated
the old walk, defeating the W2 membership and cost contract.

## Evidence

- `c98535249` adds `root-selector`, `root-acquisition`, and `membership-diff`
  in `src/seon/render/walk.clj`.
- `rg ':seon.render.walk/root-acquisition' src/seon/render/walk.clj` finds no
  consumer in the walk owner.
- The blocked exact-staleness diff supplied the key from
  `src/seon/render/web.clj`, but `neighborhood` continued deriving membership
  from individual database reads.

## Acceptance

- `neighborhood` and `history` derive membership, stable order, arrivals, and
  removals from the supplied root acquisition.
- The per-entity discovery path is deleted rather than retained as a fallback.
- One event-driven regression counts the database door and proves a cold pass
  uses one root pull, while unchanged context acquisition uses zero reads.
- Forward, reverse, and component changes produce the exact changed, added,
  and removed logical calls without a second discovery pass.

## Owner

W2 change-flow integration under the
[self-generating-context PRD](../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md).
