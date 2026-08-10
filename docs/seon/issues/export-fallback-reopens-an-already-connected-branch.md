---
type: issue
status: open
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
