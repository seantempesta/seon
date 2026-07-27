---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, concurrency]
---

# Fence cluster stop against a replacement instance

## Problem

`seon.cluster/stop!` checks the registered instance, then stops the prepl and
deletes its advertisement before conditionally removing the registry entry.
Those effects are addressed only by cluster name. Two concurrent stops of the
same instance can therefore let one stop finish, allow a replacement to start,
and then let the delayed stop kill the replacement and delete its
advertisement.

## Evidence

- `src/seon/cluster.clj:342-355` reads `running-instances`, calls the
  name-addressed `clojure.core.server/stop-server`, deletes the name-addressed
  advertisement, and only then identity-fences the registry removal.
- A deterministic probe paused the second of two `stop!` calls after both had
  accepted the old instance. The first stop completed, a replacement started
  on port 57236, and the delayed stop resumed. The replacement remained in the
  registry but `read-advertisement` returned nil because the delayed stop had
  stopped its server and deleted its file.
- `test/seon/cluster/boot_test.clj:175-206` proves sequential idempotence and
  isolation, but generates no concurrent start/stop interleaving.

## Owner

The `seon.cluster` instance registry and lifecycle transition. The registry
must identify the exact generation whose external resources a stop may touch.

## Acceptance

- Two concurrent stops of one instance are linearizable and stop that
  generation exactly once.
- A replacement may start after the old generation is fully stopped, and no
  delayed operation from the old generation can stop its server, delete its
  advertisement, or remove its registry entry.
- A recurring concurrency test controls the interleaving rather than relying
  on a timing stress loop.

## Closed 2026-07-27

Resolved by `5c95e259c`: `src/seon/cluster.clj:469-527` now atomically claims
the exact instance with an identity marker before touching its resources, and
conditionally removes only that marker. Behavioral evidence is
`test/seon/cluster/boot_test.clj:235-257`: a delayed stop of the old instance
leaves the replacement REPL and advertisement alive.
