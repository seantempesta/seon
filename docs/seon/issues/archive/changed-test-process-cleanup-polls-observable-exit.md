---
type: issue
status: resolved
severity: friction
tags: [issue, testing, tooling, clocks]
---

# Await changed-test process exits instead of polling clocks

## Problem

Changed-test cleanup samples a process tree on 10 ms sleeps and treats twenty
stable samples as discovery completion. It then polls the same observable
process-exit state until a wall-clock deadline.

## Evidence

`script/seon/dev/changed_test.clj:243-260` repeats descendant discovery until
two PID sets match or twenty attempts have elapsed, sleeping 10 ms between
samples. `await-process-absence` at lines 274-281 uses
`System/currentTimeMillis` plus another 10 ms polling loop even though each
captured `ProcessHandle` publishes `onExit`.

The external child may legitimately need a deadline as a loud backstop. Its
exact exit is not external or unobservable once the handle has been captured.

The disk emergency survivor census found four `tmp/test-runs/run.*` roots,
each 6,200 allocated KiB, created between 19:12 and 19:14 on 2026-08-04.
`bin/test:261-270` exits from its signal trap while retaining the root; normal
successful cleanup occurs only after the child command returns at lines
275-298. The missing ordering is explicit: publish/capture every child, reap
and await those children, then decide whether the root is retained evidence or
released and deleted.

## Owner

The changed-test subprocess lifecycle, expressed through exact
`ProcessHandle` identities and their completion stages.

## Acceptance

- Every captured process exit is awaited through `ProcessHandle.onExit` or the
  owning process completion, with the clock retained only as a loud external
  backstop.
- Descendant ownership is published or captured through an explicit readiness
  boundary; no attempt count stands in for a complete process tree.
- A regression starts a late descendant and proves it is reaped without tuned
  sleeps.
- Signal, failure, and success teardown reap exact children before retaining or
  deleting the claimed `run.*` root; a retained root records why it is evidence.

## Resolution

Resolved by commit `7eeff3e70` on 2026-08-04. `bin/test` publishes its exact
runner PID, forwards termination, awaits the runner's completion, records its
exit and reap instant, and only then records a retention reason or deletes a
successful root. Its EXIT trap closes abnormal launcher exits. Changed-test
deleted descendant sampling and 10 ms polling; it signals that owning
`bin/test` process and awaits `ProcessHandle.onExit`, retaining only a loud
foreign-process backstop.

The recurring proof creates a descendant after termination begins and proves
the owner reaps it before exit. A real interrupted `bin/test` launcher then
proves the child is dead and `runner-reaped-at` precedes `retained-reason`.
The focused gate passed 9 tests / 30 assertions with zero failures or errors;
`bash -n bin/test` and `git diff --check` also passed.
