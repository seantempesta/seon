---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, sci, wave/schema-codec-deletion]
---

# Make committed declaration deletion install exactly

## Problem

Successful terminal transactions which delete a function, schema, or test are
followed by an SCI installation refusal claiming the deleted declaration is
still present.

## Evidence

At HEAD on 2026-08-29, explicit `bin/test seon.cluster.turn-test` reproducibly
errored with `Deleted declaration is still present after commit.` from
`seon.sci.eval/install-row!` in four tests:
`ns-unmap-retracts-the-owned-function-after-the-terminal-commit`,
`qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`,
`runtime-schema-unregister-removes-one-unused-global-schema`, and
`runtime-tests-install-run-redefine-and-delete-exactly`. None exercises the
launcher or caps fixtures changed by the effective-config census sweep.

## Owner

The post-commit declaration installation path in `seon.sci.eval`.

## Acceptance

Committed declaration deletions disappear from the fresh SCI context and all
four named tests pass without a post-commit refusal.

## Resolution (2026-08-29)

Fixed by `d16466b1d` (graph-consequences lane): `install-row!` threw
whenever a stable identity row survived deletion — directly
contradicting ruling 47 (identity rows never retract; deletion
retracts definition facts). It now verifies definition-fact absence.
All four named turn tests green in the lane's five-namespace gate
(149 tests / 901 assertions).
