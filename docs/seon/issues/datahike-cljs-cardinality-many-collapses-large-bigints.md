---
type: issue
status: open
severity: friction
tags: [issue, database, cljs]
---

# Preserve distinct large BigInts in cardinality-many attributes

## Problem

Datahike's ClojureScript transaction/index path collapses two distinct native
JavaScript BigInts above `Number.MAX_SAFE_INTEGER` when they are supplied as
the values of one cardinality-many attribute. The resulting AEVT slice retains
only one assertion.

## Evidence

During the coordinate-bound database-browser cursor checkpoint on 2026-07-15,
a fresh in-memory database installed a `:db.type/bigint`,
`:db.cardinality/many` attribute and transacted native values
`9007199254740993n` and `9007199254740995n` on one entity. A bounded AEVT read
returned only the `9007199254740993n` datom and reported no continuation. The
same cursor test passed for double, UUID, instant, and bytes values. Moving the
two BigInts to cardinality-one assertions on distinct entities preserved both
datoms and isolated cursor serialization from this dependency defect.

Selected Datahike commit `6f90b339768b1a02066dce3b6fcc93a200758fcc`
explicitly accepts native BigInt in its `:db.type/bigint` schema predicate at
`reference-code/datahike/src/datahike/schema.cljc`, so silently losing one
accepted value is not a valid schema rejection.

## Owner

The maintained Datahike ClojureScript cardinality-many transaction
normalization and value equality/hash/index path. Determine where distinct
native BigInts are coerced or considered equal; do not work around it in
`seon.db.browser` or add a second BigInt representation to domain data.

## Acceptance

- One cardinality-many `:db.type/bigint` attribute retains both native values
  `9007199254740993n` and `9007199254740995n` on the same entity.
- EAVT and AEVT return two distinct datoms with exact native BigInt values in
  both ClojureScript memory and durable-store coverage.
- Retraction of either value leaves the other value present.
- Add a cross-platform regression that distinguishes native ClojureScript
  BigInt behavior from JVM bigint behavior without coercing through Number.
