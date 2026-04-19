---
type: capability
status: complete
tags: [vision, database]
---
# Namespace State Persistence

Namespace runtime state survives restarts. Atoms are backed up to Datalevin and restored on reload, with validation against current Malli specs. Invalid state from schema evolution is discarded cleanly rather than crashing.

## What Exists

- `ns/lifecycle.clj` implements backup/restore with Datalevin
- Restored state validated against current Malli spec
- Invalid state discarded with warning log
- Prevents duplicate atoms on SSE reconnect

## Gaps

None.

## Related

- Components: [[components/namespace-lifecycle]]
- PRDs: [[prds/namespace-ui/prd]]
