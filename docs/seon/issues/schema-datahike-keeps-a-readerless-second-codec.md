---
type: issue
status: resolved
severity: friction
tags: [issue, schema, database, class/n11, wave/schema-codec-deletion]
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

## Closure verification — 2026-08-13

**CONFIRMED-STALE at `06e654c76`; resolved by `8500755d6` and extended by
`f51810f25`.** There is now one recursive codec:

- `src/seon/schema/datahike.clj:439-492` owns the sole recursive transaction
  walk in `encode-transaction-data-in`; `encode-transaction-in` applies it
  against an explicit projection.
- `src/seon/schema/datahike.clj:494-507` is only a thin convenience wrapper that
  resolves one declaration projection and delegates to that same encoder; it
  is not a second recursive implementation.
- Production calls now use the explicit seam at `src/seon/db.clj:1456-1458`
  and `src/seon/cluster/run.clj:1514-1518`.

The readerless-duplicate mechanism described by this note no longer exists.
