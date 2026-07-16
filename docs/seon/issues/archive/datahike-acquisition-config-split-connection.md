---
type: issue
status: resolved
severity: blocker
tags: [issue, database]
---

# Datahike acquisition config could split one database connection

## Problem

Reconstructing a database config from only backend and attachment omitted
Seon's serialized allocation-writer setting. Datahike correctly treats writer
configuration as part of connection identity, so a later `connect` opened a
second physical connection instead of adding a reference to the registered
one.

## Resolution

Connection acquisition rebuilds the exact allocation connect config through
`seon.db.id/allocation-connect-config`. Repeated acquisition now returns the
registered Datahike connection and uses Datahike's existing reference count.

## Proof

The focused registry gate passes 18 tests/118 assertions. The broader registry,
routing, executor, writer-integration, and server gate passes 61 tests/430
assertions, including sibling isolation and final release.
