---
type: issue
status: resolved
severity: cleanup
milestone: M2
tags: [issue, database, architecture, archive]
---
# Duplication: connection-error? in db.clj and conn.clj (Archived)

## Resolution

Resolved by the datahike migration. The datalevin `conn.clj` (`src/seon/db/datalevin/conn.clj`) no longer exists. `connection-error?` in `db.clj` is now the only implementation, and the datahike error surface differs enough that the predicate's shape was rewritten during migration. No duplication remains.

## Related

- [[components/database]]
