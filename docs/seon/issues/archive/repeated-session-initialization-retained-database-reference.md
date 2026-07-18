---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Repeated session initialization retained database reference

## Problem

A pod initializes its already-acquired database session again when publishing
the compiled program. The repeated ensure operation created a new
administrative Datahike reference even though the same transport connection
already owned the database. Its following acquire was correctly idempotent and
therefore did not consume that reference. Closing the pod removed its transport
connection but left the database registered in the sole writer.

## Resolution

`seon.db.registry/ensure-database!` now accepts the process-local transport
connection. When that exact connection already owns the validated database
route, ensure validates the route without acquiring another Datahike reference.
Administrative ensure calls without a transport connection retain their
independent lifecycle semantics.

## Evidence

- The focused registry and writer-initialization gate passes 26 tests and 159
  assertions.
- The affected writer selection passes 79 tests and 538 assertions.
- The autonomous-cluster lifecycle proof must close with the cluster database
  absent from `seon.db.registry/list-databases`; that live evidence is recorded
  in the runtime-reliability roadmap.
