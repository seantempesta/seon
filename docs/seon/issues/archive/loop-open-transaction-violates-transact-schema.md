---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema, agent-runtime]
---

# Make the loop's open transaction satisfy the transact contract

## Problem

The run loop passes a Datahike transaction argument map to
`seon.cluster.store/transact!`, whose public Malli contract accepts only a
vector. The call currently works only because the downstream Datahike
transaction spec admits the map as a collection of map entries.

## Evidence

`src/seon/cluster/loop.cljc:342-358` passes a map containing `:tx-data` and
`:tx-meta`. `src/seon/cluster/store.clj:430-434` declares the second argument
as `[:vector :any]` and forwards it to Datahike. The dependency behavior that
makes the mismatch accidentally executable remains at
`reference-code/datahike/src/datahike/spec.cljc:66-67`.

This was reverified after commits `21215ce28`, `ba723b2d1`, and `a6d426983`;
the original research citation at `loop.cljc:292` moved, but the mismatch
remains.

## Owner

The run-loop transaction boundary jointly owned by
`seon.cluster.loop/turn` and `seon.cluster.store/transact!`.

## Acceptance

- The argument passed by the open branch conforms to the declared
  `store/transact!` schema, including transaction metadata.
- The contract represents the actual supported transaction shape without
  relying on Datahike's permissive collection spec.
- Boot-time instrumentation can instrument this boundary without rejecting
  the live open-and-claim path.

## Resolved 2026-07-27 — the contract now says what the callers do

`:seon.store/transaction` is registered in `src/seon/schema/store.edn` as
`[:or [:vector :any] {:tx-data … :tx-meta …}]`, using Datahike's own
unqualified key names because they are Datahike's vocabulary, and
`seon.cluster.store/transact!` declares that form. The map arm was
already the live critical path; the mismatch is now unrepresentable
rather than accidentally executable.

Behavioural proof: the full gate (203 tests / 911 assertions / 0 / 0)
includes `seon.cluster.turn-test`, which drives `:open` through the map
arm, and `seon.cluster.armed-test`, which boots a real cluster whose
loop transacts through the same transaction function.
