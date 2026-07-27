---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database, concurrency]
---

# Keep a failed cluster stop addressable

## Problem

`seon.cluster/stop!` claims the exact instance with an opaque registry marker,
then drops that marker in `finally` even when releasing the cluster connection
or process-root store throws. The REPL socket and advertisement remain live,
but the instance value needed to retry `stop!` is no longer registered.

A failed resource release must leave the resource addressable. Removing the
instance is a false assertion that teardown completed.

## Evidence

- `src/seon/cluster.clj:475-485` replaces the registered instance with the
  stop marker.
- `src/seon/cluster.clj:507-521` performs database release before closing the
  REPL and deleting its advertisement.
- `src/seon/cluster.clj:522-527` removes the marker unconditionally from
  `finally`, including when `d/release` or `release-root-store!` throws.
- `test/seon/cluster/boot_test.clj:299-328` proves addressability after a
  startup failure, but no recurring test injects a stop-time release failure.

## Owner

The `seon.cluster/stop!` lifecycle transition. A failed stop must publish the
still-standing instance again, with the exact remaining resources, so a retry
can finish teardown without weakening the instance-generation fence.

## Acceptance

- An injected cluster-connection or root-store release failure propagates
  loudly while the exact instance remains registered and its REPL remains
  reachable.
- A later `stop!` of that instance retries the remaining teardown, removes its
  advertisement, and releases the registry entry.
- A replacement cannot start until the failed generation is fully stopped.
- One deterministic test covers the failure and retry without sleeps.

## Triage 2026-07-27

- **OPEN-CURRENT.** The unconditional marker removal at
  `src/seon/cluster.clj:522-527` still makes a stop-time release failure
  unaddressable; this root cause was separated from the resolved flock-release
  issue during second-pass triage.
