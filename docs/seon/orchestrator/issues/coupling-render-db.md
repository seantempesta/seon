---
type: issue
status: open
severity: architectural
---
# Coupling: render.clj Reaches Into db.datalevin.conn Directly

## Problem
`render.clj` bypasses the `seon.db` API and reaches directly into `db.datalevin.conn`. Every other namespace uses `seon.db` as the sole database API. This coupling means render depends on the database implementation layer, not just the public API.

## Where
- `src/seon/render.clj` — direct dependency on `seon.db.datalevin.conn`

## Acceptance Criteria
- `render.clj` uses only `seon.db` public API
- No direct imports of `seon.db.datalevin.conn` outside `src/seon/db/`
- Render functionality unchanged
- Tests pass

## Related
- [[components/renderer]]
- [[components/database]]
