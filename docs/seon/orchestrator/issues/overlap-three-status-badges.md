---
type: issue
status: open
severity: cleanup
---
# Overlap: Three Status Badge Implementations

## Problem
The same visual element (status badge) is implemented three times with slightly different APIs:

1. `web/html/status-badge` -- generic HTML helper
2. `web/components/status-dot` -- design-system component (dot + text)
3. `web/agents/agent-status-badge` -- agent-specific variant

## Where
- `src/seon/web/html.clj` — `status-badge`
- `src/seon/web/components.clj` — `status-dot`
- `src/seon/web/agents.clj` — `agent-status-badge`

## Acceptance Criteria
- Single status badge component in `web/components.clj` (the design system)
- All callers use the unified component
- Visual appearance unchanged
- Agent-specific styling handled via parameters, not a separate implementation

## Related
- [[components/web-layer]]
