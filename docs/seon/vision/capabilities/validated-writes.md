---
type: capability
status: complete
tags: [vision, database, schema]
---
# Validated Data Writes

Every write to the database is validated against Malli schemas before it reaches storage. Invalid data is rejected at the boundary, not discovered later. Combined with per-DB locking and connection management, writes are both correct and safe under concurrency.

## What Exists

- `db/transact!` validates every attribute and value via Malli before Datahike
- Per-DB locking (ConcurrentHashMap) prevents concurrent-open LMDB corruption race
- Writer step-fn retries on transient connection errors
- Nippy wire protocol for inter-JVM TCP (replaced EDN)

## Gaps

None.

## Related

- Components: [[components/database]], [[components/schema-system]]
- PRDs: [[prds/schema-unification/design]], [[prds/flow-datalevin-writer/prd]]
