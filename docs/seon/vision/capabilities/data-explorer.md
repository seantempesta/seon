---
type: capability
status: not-started
tags: [vision, database]
---
# Interactive Data Explorer

Browse and drill into live data structures in the UI. Collections expand and collapse, large values truncate with expand-on-demand, and entity references are navigable links. Currently both renderers render collections fully expanded with no interactivity.

## What Exists

Nothing. Both existing renderers output fully expanded collections with no interactive affordances. The PRD is at "Phase 0 Ready" (designed, not built).

## Gaps

- No expand/collapse for nested data
- No truncation or pagination for large collections
- No clickable entity reference navigation
- No lazy loading for deep structures

## Related

- Components: [[components/web-layer]]
- PRDs: [[prds/data-viewer/prd]]
