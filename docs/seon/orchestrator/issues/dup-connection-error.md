---
type: issue
status: open
severity: cleanup
milestone: M2
tags: [issue, database, architecture]
---
# Duplication: connection-error? in db.clj and conn.clj

## Problem

`connection-error?` is defined in both `db.clj:328` and `conn.clj:194`. The `conn.clj` version is public; the `db.clj` version is a private duplicate. One should be deleted.

## Where

- `src/seon/db.clj:328` — private duplicate
- `src/seon/db/datalevin/conn.clj:194` — public version

## Acceptance Criteria

- Only one `connection-error?` implementation exists (in `conn.clj`)
- `db.clj` callers use the `conn.clj` version
- Tests pass

## Related

- [[components/database]]
