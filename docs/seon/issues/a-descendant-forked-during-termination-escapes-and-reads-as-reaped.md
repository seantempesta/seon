---
type: issue
status: resolved
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

## Resolution (root ruling 2026-09-25: freeze, then kill)

`terminate-subprocess!` now freezes the tree to a fixpoint before any signal can kill
a member. It sends SIGSTOP to each live member through the OS `kill` and walks
`ProcessHandle.descendants` again until a walk finds no new pid. Because a stopped
process cannot fork, the frozen set is complete.

The owner then sends TERM to every frozen member and CONT to them, which keeps the
graceful TERM handling that `seon.cluster.process-test` asserts. It waits for the
grace, freezes again (a TERM handler may fork), and SIGKILLs the frozen set. Finally
it awaits every exit, and any survivor is returned by `(pid, start-instant)`.

Regressions in `seon.cluster.subprocess-test`:
- `a-child-forked-as-termination-begins-never-escapes` sends the spawn signal at 490,
  500, 505 and 510 ms against a 500 ms deadline;
- `a-tree-forking-through-termination-leaves-no-live-member` uses a TERM-ignoring
  root that forks every 10 ms. Under the old owner about 70 members survived while the
  refusal said `reaped? true`; under the new owner none survive, in 1.4 s.

What remains: a member that forks and then exits on its own before it is stopped
leaves an orphan that no walk can reach. Closing that needs process groups.

