---
type: issue
status: open
severity: friction
tags: [issue, schema, datahike, deletion]
---

# Delete the readerless second Datahike transaction codec

## Problem

`seon.schema.datahike` contains two full recursive transaction encoders. The
explicit-projection implementation is public but has no reader; production
uses the ambient projection implementation.

## Evidence

- `src/seon/schema/datahike.cljc:320-382` implements projection-parameterized
  value, entity, transaction-data, and transaction encoding.
- `src/seon/schema/datahike.cljc:384-434` repeats the same recursion against the
  ambient projection.
- `src/seon/cluster/store.clj:460-475` uses `encode-transaction`, not
  `encode-transaction-in`.
- Repository-wide source/test search found no reader of
  `encode-transaction-in` or its matching explicit decode surface.

## Owner

The one transaction codec called by `seon.cluster.store/transact!`.

## Acceptance

One encoder remains. If explicit projection is required, the live seam takes
it and the ambient duplicate is deleted; otherwise the readerless explicit
codec and its public contracts are deleted. Round-trip properties exercise the
surviving production path.
