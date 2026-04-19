---
type: capability
status: complete
tags: [vision, web]
---
# Reactive Namespace UI

Namespace pages update in real time as state changes. SSE push on context atom mutations, client-targeted updates, and cache invalidation on code changes ensure the UI always reflects the live system. Agents and humans see the same current truth.

## What Exists

- SSE push on ctx atom changes via `::sse-push` and `::client-push` watches
- Cache invalidation on code changes triggers re-render
- Per-client targeting for SSE updates

## Gaps

None.

## Related

- Components: [[components/web-layer]], [[components/context]]
- PRDs: [[prds/render-pipeline/prd]]
