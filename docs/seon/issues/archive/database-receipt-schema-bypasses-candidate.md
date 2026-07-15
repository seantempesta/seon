---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema]
---

# Database receipts bypass the canonical schema candidate

## Problem

The database protocol registered Malli declarations for durable receipt
attributes but separately hand-wrote their native Datahike schema and installed
it through a receipt-specific pre-initializer transaction. Those two
declaration populations could drift independently.

## Evidence

`seon.db.protocol` now declares every receipt attribute only through
`seon.schema/register!`; `request-id` carries its identity property in that
canonical form. `seon.db.writer` derives the five native signatures through
`seon.db.datahike.schema/malli-map->datahike-schema`. The raw `receipt-schema`
and `seed-receipt-schema!` paths are deleted.

The maintained Datahike source and two live writer probes established the
required transaction boundary. Ordinary entity data can use schema introduced
earlier in the same transaction. Transaction metadata is validated against the
pre-transaction schema and rejects an attribute introduced in that
transaction. Because receipts are transaction metadata, fresh databases install
their canonical derived declarations through Datahike `:initial-tx`; existing
databases validate the signatures before registry publication and do not run a
compatibility initializer.

Focused missing/incompatible reopen tests reject publication without leaving a
registry entry. The complete writer gate passes 57 tests/337 assertions and the
relevant CLJS schema/replica gate passes 24/140. After a complete rebuild and
restart, JVM and pod both reported default coordinate
`54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
`6a56e85d-cd67-5c5a-a2cb-5f1aeb6ef905`/`536870974`; JVM schema read-back
showed all five canonical signatures and unique identity on `request-id`.

Resolved by the database-protocol genesis-schema commit recorded in this
issue's merge history.

## Owner

The database lifecycle candidate owned by `seon.schema`,
`seon.db.datahike.schema`, `seon.db.writer`, and the database registry.

## Acceptance

- Receipt attribute forms are the only declaration authority.
- Native signatures derive from those exact forms.
- Fresh database creation installs the signatures before any receipt metadata.
- Missing or incompatible reopen publishes no connection.
- No receipt-specific compatibility or seed transaction remains.
- Writer, request recovery, config-free restart, and live read-back prove the
  boundary.
