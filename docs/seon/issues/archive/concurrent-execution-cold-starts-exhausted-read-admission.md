---
type: issue
status: resolved
severity: blocker
tags: [issue, database, agent, flow]
---

# Concurrent execution cold starts exhausted read admission

## Problem

One execution child reconstructs the current program with a six-member grouped
read. The database executor admitted only eight queued reads per database, so
two children starting together could reject one another even while the global
read queue retained capacity. Serializing child startup would have hidden the
fault and defeated process parallelism.

## Resolution

The existing bounded executor now derives the per-database read limit from its
selected CPU capacity, with a minimum of sixteen jobs and the existing global
class limit as its ceiling. The scheduler, byte bound, and cross-database
fairness mechanism remain unchanged; one database can now admit at least two
complete cold program reads concurrently.

## Evidence

The focused executor capacity test fixes the derived bounds at two, four, and
eight selected processors. The real two-child execution proof starts two Bun
processes concurrently against one database and requires both initial
invocations to complete.

## Owner

`seon.db.executor/capacity` owns the one bounded read admission policy.

## Acceptance

- Two concurrent six-member program reads fit within one database's bounded
  queue on modest hardware.
- Global byte and work-class bounds remain enforced.
- Both real Bun children complete their cold initial invocation.
