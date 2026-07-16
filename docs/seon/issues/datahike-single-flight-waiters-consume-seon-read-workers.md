---
type: issue
status: open
severity: high
tags: [issue, database, flow]
---

# Admit one Seon read worker per Datahike single-flight owner

## Problem

Identical cacheable queries enter Seon's scarce read workers before they join
Datahike's single-flight computation. Enough callers for one cold query can
therefore occupy every read worker while only one computes, delaying unrelated
database work.

## Evidence

The retained executor proof uses three read workers: one owner and two joined
callers for database A occupy all three, while database B remains queued until
A releases. Datahike reports one miss owner, two joined misses, and one actual
predicate execution.

## Owner

The owned Datahike fork must expose a two-phase acquisition that preserves its
exact cache key, request cancellation, release fencing, and nested-query
reentrancy. Seon must not recreate Datahike's cache or coordination key.

## Acceptance

- Only a single-flight owner consumes Seon read execution capacity.
- Joined callers complete and cancel independently outside that capacity.
- An unrelated database progresses while duplicate callers wait.
- Nested queries, owner-thread transfer, release, cancellation, and shutdown
  retain no in-flight state or deadlock.
