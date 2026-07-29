---
type: issue
status: resolved
severity: blocker
tags: [issue, flow]
---

# Bound submission startup by the declared time limit

## Problem

`seon.flow/submit!!` dereferences the untimed `started` promise before it
performs the timed dereference of the result. A paused launcher or saturated
executor can therefore leave a queued submission blocked forever without
starting its declared time limit.

## Evidence

The dereference at `src/seon/flow.clj` in `submit!!` is `started-at @started`.
Only `execute-work!` delivers that promise, after an executor begins the task.
No recurring test currently parks a queued submission before executor start.

## Owner

`seon.flow/submit!!` and the work-launcher lifecycle.

## Acceptance

A latch-driven test holds a submission before executor start and proves the
declared time limit settles it without a sleep or an unbounded wait.

## Triage 2026-07-27

- **OPEN-CURRENT.** `src/seon/flow.clj:387-407` still performs the unbounded
  `@started` dereference before the timed result dereference, so the declared
  time limit does not cover executor-queue delay.

## Triage 2026-07-29

**PRESSING — fold into [[agent-turns-bypass-the-bounded-compute-door]].** This
is the same bounded-compute fix wave: production agent eval cannot safely adopt
`submit!!` while submission can wait forever before its declared time limit.

## Resolution

Resolved by commits `f14a6cf7a` and `24544c1d1`.

`submit!!` no longer owns or dereferences a separate `started` promise. Each
accepted submission publishes one terminal result event carrying its start
time and value or throwable. The same bounded dereference now covers the
entire accepted pre-start and execution interval. A timed-out queued
submission atomically becomes cancelled and is skipped if capacity later
opens.

`submission-time-limit-covers-the-pre-start-wait` proves both a paused launcher
and a submission queued behind a fully occupied owner settle within the
declared limit, without sleeps or an unbounded startup dereference. The
complete focused flow suite passes with zero failures or errors.
