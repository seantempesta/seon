---
type: issue
status: active
severity: blocking
tags: [issue, database, cljs]
---

# Bun Transit query list is not ordinary on the JVM

## Evidence

The real Bun authority-density client constructed a query request containing
`[:find (count ?right) . ...]`. In Bun, `ordinary-wire-value?` returned true
for the complete query, coordinate, and complete protocol request. After the
canonical Transit JSON frame was decoded by the JVM authority, request
validation returned `Protocol requests contain only eager ordinary wire
values`.

An all-vector query over the same connection and coordinate is the shortest
falsifier for whether list representation is the mismatch. This blocks honest
remote aggregate queries even though the existing same-runtime protocol tests
accept them.

## Owner

`seon.db.protocol` owns ordinary values and `seon.db.transport.uds` owns Transit
encoding and decoding. Datahike query semantics are not the failing boundary.

## Acceptance

A Bun-produced aggregate query round-trips through the JVM decoder as the same
ordinary query form; request validation succeeds on both sides; direct and
`execute-many` aggregate queries return the expected result; and no transport
adapter or query-specific rewrite is introduced.
