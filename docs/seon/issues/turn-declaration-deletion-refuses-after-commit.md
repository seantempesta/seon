---
type: issue
status: open
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
