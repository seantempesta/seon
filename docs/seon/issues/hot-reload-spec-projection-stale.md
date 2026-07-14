---
type: issue
status: open
severity: blocker
tags: [issue, schema, agent]
---

# Hot reload may instrument stale database schema facts

## Problem

A source edit that changes function schema metadata can re-instrument the live
var before the database program projection reflects the new contract.

## Evidence

The archived dual-path audit's C61 row records the mismatch. The active runtime
still restores and instruments from database-backed program facts, so update
ordering must be falsified without relying on a cluster reset.

## Owner

The one analyzer-to-program-facts publication path and incremental
instrumentation path.

## Acceptance

Changing a function's schema in a running pod publishes the new program fact
before that definition is instrumented, and a live probe observes the new
contract without restart, global reinstallation, or a second registry.
