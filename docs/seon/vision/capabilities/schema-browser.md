---
type: capability
status: not-started
tags: [vision, schema, web]
---
# Schema Browser

Browse all registered schemas in the web UI -- grouped by namespace, with type details, usage references, and entity relationships. The data API exists; the web layer does not.

## What Exists

Data API only: `registered-schemas`, `schema-definition`, `schemas-in-namespace` functions exist in the schema system. No web UI, no routes, no handlers.

## Gaps

- No web routes or handlers
- No UI for browsing schemas by namespace
- No visualization of entity relationships or schema dependencies
- No search or filtering

## Related

- Components: [[components/web-layer]], [[components/schema-system]]
- PRDs: `prds/schema-viewer/prd`
