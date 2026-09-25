---
type: issue
status: open
severity: defect
tags: [issue, process, termination]
---

# A descendant forked during termination escapes, and the refusal still reads `reaped? true`

`seon.cluster.process/terminate-subprocess!` walks the tree once, at the start of
termination, and then signals only the identities it captured. A member that forks
between that walk and its own death has a child that the walk never saw. Once the
parent dies, the child is reparented away from the tree. It is never signalled, and
`run-process!` still reports `:seon.operator.subprocess/reaped? true`.

Probe (default, 2026-09-25). The python parent from
`seon.cluster.subprocess-test/foreign-process-deadline-reaps-a-late-descendant` was run
with a 2000 ms deadline, and its spawn signal was written at a chosen time after launch:

| Signal at | Child alive after refusal | Refusal |
|---|---|---|
| 1990 ms | yes | `reaped? true` |
| 2000, 2005 or 2010 ms | no child spawned | `reaped? true` |

The test was red on every recorded run of 2026-09-25 (`tmp/orchestrator/verify-*.out.d`),
because macOS's JDK `WatchService` is a `sun.nio.fs.PollingWatchService`: a create
event took 2009 ms to arrive, which put the spawn signal exactly at the deadline. The
test now polls for the file (lane impl-seams-s-process), so the test no longer hits
this race. The owner race itself remains.

The JDK has no process-group or stop signal. Closing the race needs one of:
- freezing the tree before the walk (`kill -STOP`);
- signalling a process group;
- declaring that a member forking during termination is outside the guarantee, with
  the refusal saying so.

This needs an owner ruling.
