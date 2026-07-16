---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow, architecture]
---

# Enforce expected coordinates inside the serialized Datahike writer

## Problem

The JVM writer checked a transaction's expected coordinate before asynchronous
Datahike dispatch, not inside Datahike's serialized writer operation.

## Evidence

`seon.db.writer/prepare-transaction!` compared the request coordinate with
`d/db` while holding a JVM monitor, then built a Datahike transaction map with
only transaction data, metadata, and optional generated candidates. It did
not pass `:datahike/expected-basis-t`. The monitor was released as soon as
`d/transact!` returned its asynchronous result, before durable completion, so
two concurrent requests could observe the same pre-dispatch head. The
maintained Datahike fork already provided the correct primitive in
`datahike.writing/transact!`.

## Owner

`seon.db.writer/prepare-transaction!` owns translation from the complete Seon
coordinate to Datahike's transaction map. Datahike's serialized expected-basis
check remains the commit fence.

## Acceptance

Two concurrently dispatched requests carrying the same complete expected
coordinate produce exactly one committed response and one stale-coordinate
response. The rejected request writes no domain datom or durable receipt and
does not advance the database coordinate. Branch and commit mismatch still
fail before dispatch with the complete Seon coordinate evidence.

## Resolution

Commit `059d6fd9` passes the expected transaction `t` through
`:datahike/expected-basis-t`, translates Datahike's serialized stale-basis
rejection back into Seon's complete stale-coordinate response, and retains the
full coordinate precheck for database, branch, and commit identity.

`bin/test-writer seon.db.writer-integration-test` passes 18 tests and 189
assertions. Its concurrent two-socket proof releases two transactions from one
head, observes exactly one success and one stale-coordinate response, and
confirms that only the winner's domain datom and durable receipt advance the
database. Equal-`t` forged commit and branch identities remain rejected before
dispatch.
