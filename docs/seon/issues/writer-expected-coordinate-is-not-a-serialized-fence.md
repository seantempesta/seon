---
type: issue
status: open
severity: blocker
tags: [issue, database, flow, architecture]
---

# Enforce expected coordinates inside the serialized Datahike writer

## Problem

The JVM writer checks a transaction's expected coordinate before asynchronous
Datahike dispatch, not inside Datahike's serialized writer operation.

## Evidence

`seon.db.writer/prepare-transaction!` compares the request coordinate with
`d/db` while holding a JVM monitor, then builds a Datahike transaction map with
only transaction data, metadata, and optional generated candidates. It does
not pass `:datahike/expected-basis-t`. The monitor is released as soon as
`d/transact!` returns its asynchronous result, before durable completion, so
two concurrent requests can observe the same pre-dispatch head. The maintained
Datahike fork already provides the correct primitive in
`datahike.writing/transact!`; its direct isolated probe rejects a stale basis
inside the serialized operation. Existing Seon coverage exercises sequential
staleness only.

## Owner

`seon.db.writer/prepare-transaction!` owns translation from the complete Seon
coordinate to Datahike's transaction map. Datahike's serialized expected-basis
check remains the commit fence.

## Acceptance

Two concurrently dispatched requests carrying the same complete expected
coordinate produce exactly one committed response and one stale-coordinate
response. The rejected request writes no domain datom or durable receipt and
does not advance the database coordinate. Branch/attachment mismatch still
fails before dispatch with the complete Seon coordinate evidence.
