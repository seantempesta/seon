---
type: issue
status: superseded
severity: friction
tags: [issue, testing, tooling]
---

# test-cljs compile failure retains a live lock owner

## Problem

A focused `bin/test-cljs` holder remained alive and retained
`tmp/test-cljs.lock` after Shadow had already reported a compile failure. The
shell process had parent PID `1`, no children, and continued consuming CPU for
more than 70 minutes. A later `SEON_TEST_WAIT=1` run therefore waited on a
process that could never publish a test result or release the canonical test
artifact.

This is distinct from the resolved lock-acquisition race in
[[archive/test-cljs-lock-no-wait-dial]]: the lock correctly recognized a live
`bin/test-cljs` owner, but the owner failed to terminate after its compiler
child had settled.

## Evidence

- The retained lock named PID `73374`; process inspection showed parent PID
  `1`, no children, and elapsed time above 70 minutes.
- `tmp/test-cljs-20260724-144548-69558.log` contains the settled Shadow failure:
  `await can only be used in async contexts` at
  `src/seon/runtime/admission.cljs:533`.
- Sending `TERM` to that exact verified holder released the lock; the queued
  focused suite acquired it and compiled normally.

## Acceptance

- A Shadow compile success or failure settles the owning `bin/test-cljs`
  process and releases its lock without operator intervention.
- A regression proves the failure path with a short-lived synthetic compiler
  error and observes both process exit and lock removal.
- The existing single-owner and queued-wait behavior remains unchanged.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
