---
type: capability
status: proof-of-concept
tags: [vision, web]
---
# System Dashboard

A single-page overview of the running system: agents, namespaces, and health status. The dashboard exists and updates via SSE, but does not yet match the design system or provide the information density needed for effective system monitoring.

## What Exists

- Dashboard at `/` shows agent count, grouped namespace list, link to Observatory
- SSE-driven live updates

## Gaps

- Design system violations (wrong text sizes, gaps, spacing)
- No namespace tree view
- No inline agent status indicators
- No system liveness indicator
- Stale "ML Options Trading" reference in UI
- Full PRD spec exists but is largely unimplemented

## Related

- Components: [[components/web-layer]]
- PRDs: `prds/dashboard-polish/prd`
