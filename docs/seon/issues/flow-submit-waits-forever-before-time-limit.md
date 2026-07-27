---
type: issue
status: open
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
