---
type: issue
status: archived
severity: architectural
milestone: M2
tags: [issue, web, database, architecture, archive]
---
# Coupling: render.clj Reaches Into db.datalevin.conn Directly (Archived)

## Resolution

Resolved by the datahike migration. `src/seon/render.clj` no longer imports anything from `seon.db.datalevin.conn` (that namespace doesn't exist). Render now goes through the `seon.db` public API or receives a `db-name` keyword.

## Related

- [[components/renderer]]
- [[components/database]]
