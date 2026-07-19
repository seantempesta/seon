---
type: issue
status: open
severity: blocker
tags: [issue, flow, pod, architecture]
---

# Keep a stable owner until the pod execution subtree drains

## Problem

The operator records the pod workload as its process-group leader. If that
leader dies while an execution child remains in the group,
`seon.dev.process/stop!` correctly refuses to signal the numeric PGID because
it cannot distinguish the old group from a later unrelated group that reused
the number. The retained record then prevents replacement readiness.

This blocks the pod-crash row of database lifecycle recovery and the parent-
death prerequisite for disposable eval children.

## Evidence

`script/seon/dev/detach.py` creates a new session for the workload, prints its
PID, and exits. `spawn-detached!` records that PID as both process and process
group. `stop!` signals the group only while the recorded PID/start identity is
alive; its dead-leader/live-group branch throws rather than risk an unrelated
group.

A project-local real-process fixture killed the recorded Bash leader while its
`sleep` child remained. The child was reparented to PID 1, retained the dead
leader's PGID, and kept the group probe alive. POSIX permits PGID reuse after
the last member leaves, so a later probe and signal cannot establish ownership.
Java descendant enumeration and `ps` are snapshots, not stable handles.

The complete source audit, rejected alternatives, and ordered implementation
slice are in [[dead-leader-process-subtree-containment-2026-07-15]].

The generic operator mechanism is now implemented in the existing process
owner. `detach.py` retains an outside owner plus a session-leading anchor;
Clojure atomically publishes the complete generation before acknowledging its
adoption. TERM is ignored only across the owner's anchor-spawn/cleanup-owner
registration cut, and the anchor resets that inherited disposition before it
starts the workload. A real workload handler proves it receives TERM before
the anchor escalates. The anchor alone sends TERM and the final KILL to its
pinned group, and the owner reaps it before publishing the matching drained
result. Focused
real-process proof covers pre-record and post-record/pre-adopt failure, owner
TERM before adoption, ordinary stop, workload death with a TERM-ignoring child,
missing owner result, individually killed anchor without false drained evidence,
startup SIGINT, converged reuse, and one-time retirement of a live legacy leader
plus child. The combined process/branch/CLI gate passes 48 tests and 282
assertions.

The issue remains open until the coordinated default crash/restart checkpoint
proves the new generation on the real pod and the database recovery consumer
lands. The current pre-containment default PIDs are intentionally untouched by
this isolated lane; the next operator restart retires each exact live legacy
leader with one immediate group KILL, observes group absence without another
signal, and only then starts the anchored replacement.

Two live Inspect cancellations on 2026-07-19 falsified the stronger claim that
the implemented group boundary already contains demanded Bun execution
children. After the isolated branch pod drained, execution child PID 82454
remained alive with parent PID 1 and retained the `default-inspect-fixed`
database acquisition. `bin/seon branch close` correctly refused deletion with
`:seon.db.protocol.error/database-in-use`. A normal full `bin/seon down` reaped
the child; no process was killed directly. The child had already opted out of
database-advanced events, so this is independent of database delivery pressure.
The next source audit must determine whether Bun creates a separate process
group or whether the pod exits before its child-shutdown path completes.

## Owner

The one managed-process transition in `script/seon/dev/process.clj` and the
existing `script/seon/dev/detach.py` boundary. It must retain an exact
containment owner and live execution-group anchor through the anchor's final
self-issued group signal, then publish one matching terminal drained result.
The operator addresses the owner through a generation-matched private control
socket, never by reconstructing signaling authority from PID/PGID numbers.
Database crash recovery and eval-child lifecycle consume that result; they do
not add another process registry or supervisor.

## Acceptance

- Pod workload death with an ordinary or TERM-ignoring child drains the old
  execution group before replacement readiness.
- Cancelling one of several concurrent `/agents/run` requests and closing its
  retained branch leaves no execution child reparented to PID 1; branch close
  releases every database acquisition without stopping the shared writer.
- Every group signal is issued by the live anchor against its own current
  group. The operator never probes or signals the PGID.
- The containment owner is addressed by PID plus start instant and publishes a
  generation-matched terminal result only after the anchor is reaped.
- Missing owner, missing anchor, partial descriptor, or missing terminal result
  retains the exact record and reports degraded `containment-uncertain`; it
  never starts a replacement or releases/deletes a retained branch.
- Real fixtures prove stale/reused PID and PGID values never signal innocent
  processes, startup SIGINT retains its current inverse guarantees, and a
  converged process remains unclaimed.
- A coordinated default crash/restart checkpoint proves the old subtree absent,
  one idempotent database recovery transaction, replacement readiness, and a
  subsequent normal CLJS eval plus database write/read.
