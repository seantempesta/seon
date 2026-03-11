---
type: issue
status: open
severity: architectural
---
# Overlap: Three Rendering Systems

## Problem
Two live rendering systems exist for the same job, using different dispatch mechanisms:

1. **`seon.render`** -- Datalevin-backed key-shape discovery, specificity algorithm. Production system.
2. **`seon.ns.view`** -- Multimethod-based rendering for namespace views. Separate discovery mechanism.
3. **`seon.ui.viewer`** -- Dead (no callers, covered by dead-web-namespace-viewer issue).

Having two live systems with different dispatch (specificity vs multimethods) means inconsistent extension patterns and duplicated rendering infrastructure.

## Where
- `src/seon/render.clj` — specificity-based production renderer
- `src/seon/ns/view.clj` — multimethod-based namespace views

## Acceptance Criteria
- Single rendering system with one dispatch mechanism
- All namespace views use the unified renderer
- Dead `ui/viewer.clj` removed (separate issue)
- Extension pattern is documented and consistent

## Related
- [[components/renderer]]
- [[components/namespace-lifecycle]]
