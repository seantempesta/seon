---
type: issue
status: archived
tags: [issue, schema, archive]
---
# routes.clj passes conn where db-name expected (Archived)

## Resolution

Resolved by the datahike migration. In `src/seon/ns/routes.clj` the call site at line ~482 now hardcodes `conn nil` (M-1: `get-conn` deleted), and `render/resolve-renderer` is only invoked when `conn` is non-nil. The conn-vs-db-name mismatch is gone — the boot path passes nothing, and the renderer-resolution flow needs to be wired afresh against datahike (tracked as Phase 3 step 7 in [[orchestrator/active]]).

## Severity

friction

## Milestone

[[vision/m3-convention-uniformity]]
