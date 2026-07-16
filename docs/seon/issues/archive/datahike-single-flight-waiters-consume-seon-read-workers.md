---
type: issue
status: resolved
tags: [issue, database, flow]
---

# Admit one Seon read worker per Datahike single-flight owner

## Problem

Identical cacheable queries entered Seon's scarce read workers before they
joined Datahike's single-flight computation. Enough callers for one cold query
could therefore occupy every read worker while only one computed, delaying
unrelated database work.

## Evidence

The original executor proof used three read workers: one owner and two joined
callers for database A occupied all three, while database B remained queued
until A released. Datahike reported one miss owner, two joined misses, and one
actual predicate execution.

## Owner

Datahike now owns opaque two-phase query calls and their exact cache,
single-flight, cancellation, and generation identity. Seon's writer resolves
the immutable database under fair read admission, then continues only the
`:run` owner in place. Logical callers stay in the writer's existing active
request ownership without retaining executor jobs.

## Acceptance

- Only a single-flight owner consumes Seon read execution capacity.
- Joined callers complete and cancel independently outside that capacity.
- An unrelated database progresses while duplicate callers wait.
- Nested queries, owner-thread transfer, release, cancellation, and shutdown
  retain no in-flight state or deadlock.

## Resolution

The focused integrated proof blocks one database-A owner under three read
workers, joins 32 additional standalone callers, and shows database B completes
before A is released with one running read job and no queued joiner jobs. The
same proof covers 33 query members inside `execute-many`. A guarded
acquire-to-continue race proves final unstarted cancellation removes the exact
owner job before computation. Every case finishes with zero active writer
requests, executor identities, query-job mappings, Datahike flights, and
Datahike callers.
