---
type: issue
status: open
severity: blocker
tags: [issue, database, schema]
---

# Database receipts bypass the canonical schema candidate

## Problem

The database protocol registers Malli declarations for durable receipt
attributes but separately hand-writes their native Datahike schema and installs
it before the composed database initializer. A fresh or reopened database can
therefore validate one declaration population while installing or accepting a
second shape outside the canonical candidate.

This violates the one `seon.schema/register!` authority and prevents database
boot from proving that native schema, Malli registry, program declarations,
config, and first facts belong to one accepted generation.

## Evidence

- `src/seon/db/protocol.cljc` registers `::request-id`, `::request-hash`,
  `::version`, and the two tempid receipt attributes through `schema/register!`.
- The same namespace also defines `receipt-schema`, a raw vector of five
  Datahike declaration maps.
- `src/seon/db/writer.clj` has `seed-receipt-schema!`, which compares that raw
  vector against installed schema and transacts missing declarations.
- `initialize-connection!` runs `seed-receipt-schema!` before the writer's
  composed `database-initializer`.
- A Malli/native declaration edit can drift independently because no function
  derives the raw vector from the registered forms.

The complete dependency and lifecycle evidence is in
[[../../prds/database-lifecycle-recovery/research/database-lifecycle-source-audit-2026-07-14]].

## Owner

The database lifecycle candidate owned by `seon.schema`,
`seon.db.datahike.schema`, `seon.db.writer`, and the boot reconciler. The fix
strengthens that one path; it must not create another receipt initializer or a
receipt-specific compatibility schema.

## Acceptance

- Receipt attribute forms enter the complete canonical declaration candidate.
- Native Datahike signatures are derived from that exact candidate and checked
  for compatibility before any post-genesis write.
- One accepted transition commits missing compatible native declarations and
  matching program/config facts, then publishes the exact validated Malli
  projection.
- A receipt schema incompatibility writes no partial candidate facts, records
  the bounded owning failure, and fails admission/readiness.
- `receipt-schema` and `seed-receipt-schema!` are deleted with no compatibility
  initializer.
- Fresh, compatible reopen, incompatible reopen, request recovery, and
  config-free restart tests plus default-cluster read-back prove the boundary.
