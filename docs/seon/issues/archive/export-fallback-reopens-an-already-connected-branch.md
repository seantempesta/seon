---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, database]
---

# Rebuild an export without reopening an already-connected branch

## Problem

When filesystem cloning is unavailable, export falls back to rebuilding the
database but tries to open the source branch a second time in the same process.
The fallback therefore cannot serve exactly the host where it is needed.

## Evidence

At clean commit `48eb25ab7`, `/bin/cp -cR` failed and
`seon.dev.fresh-operator-export-test/export-verb-produces-an-openable-queryable-store`
reported `:seon.cluster.export/clone-unsupported` with fallback cause `branch
:cluster-export-verb already has a connection in this process`. The failed
first export left the destination empty, so the later occupied-destination
assertions inverted. Four failures are one class at
`tmp/full-gate-2026-08-10b.log:2566-2592`.

## Owner

Suspected owner: the fresh operator export fallback and database branch
custody. `suite-speed-tail` already owns operator composition and should fold
this only if its prepared export work reaches the same connection owner.

## Acceptance

- A clone-command refusal falls back using the existing database value or one
  explicitly transferred custody interval, never a second connection to the
  same branch.
- The exported database opens and answers the test query.
- A genuinely occupied destination still refuses before mutation.

## Resolution

Commit `302580cc9` adds `seon.cluster.registry/active-branch-connection`,
which borrows Datahike's registered `[store-id branch]` connection without
acquiring or releasing an owning reference. The export fallback uses that
connection when present and releases only branch readers it opened itself.

The pre-fix forced-fallback probe reproduced
`:seon.cluster.export/clone-unsupported` with fallback cause `branch
:cluster-live already has a connection in this process`; the held connection
remained identical at registry reference count one. The class regression then
forced the same fallback while watching `datahike.connections/*connections*`,
proved the count never exceeded one, and reopened and queried the exported
branch.

Verification on 2026-08-11:

- `bin/test seon.cluster.export-test`: 6 tests, 27 assertions, green.
- `bin/test seon.dev.fresh-operator-export-test`: 1 test, 13 assertions,
  green; this retains the event-driven conversion from `b5846ea65`.
- `bin/test --changed src/seon/cluster/export.clj --changed
  src/seon/cluster/registry.clj --changed test/seon/cluster/export_test.clj`:
  108 tests, 604 assertions, green.

The 2026-08-13 complete run found that the standing exact custody census had
not recorded this newly declared return. The function is now explicitly
reviewed in `seon.custody-stability-test`: it returns only the connection
already registered for the exact store ID and branch, without acquiring or
releasing a reference, so it introduces no new custody. The production
contract and the derived census query remain unchanged.
