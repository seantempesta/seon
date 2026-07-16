---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Preserve the captured database value in transaction reports

## Symptom

The Datahike transaction report's raw `:db-before` retained the correct basis
transaction, but after durable commit its attached host identity was not always
the same commit identity as the database value captured by the caller before
submission. Encoding that raw value made this ordinary remote contract false:

```clojure
(= captured-db (:db-before transaction-report))
```

## Resolution

`seon.db.writer/prepare-transaction!` captures the ordinary database value
while it holds the connection's transaction-preparation lock. The completed
report returns that exact value as `:db-before` and encodes Datahike's committed
`:db-after` normally. This retains one immutable snapshot identity without
copying the database, rebuilding indexes, or adding a client cache.

The real UDS authority contract passes nine tests and 62 assertions, including
exact captured-before equality, a reusable committed `:db-after`, transaction
metadata, listener reports, multi-database reads, and exact session release.
