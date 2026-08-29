---
type: issue
status: open
severity: blocker
tags: [issue, test, runner, wave/parallel-stress-triage]
---

# Bulk tier: the coordinator wedges on a worker that never answers

## Problem

Two consecutive bare `bin/test` runs (2026-08-28, the second on an
otherwise quiet machine) ran the platform tier green, progressed
through the bulk tier for ~16 minutes, then made no reporter progress
for 300 s and were killed by the liveness watchdog (exit 124). The
watchdog worked as designed; the defect is beneath it: the coordinator
holds a pool future blocked in `worker_rpc_BANG_` →
`read_worker_protocol_BANG_` → `BufferedReader.readLine` on a worker
process pipe, while EVERY dumped worker JVM sits idle in
`worker_command_loop_BANG_` with no task frames. A command was issued
that no live worker is executing, and the reader waits forever.

The first wedge also logged, just before stalling:

```text
bin/test: confirmation unconfirmed seon.example-test/unlaunchable worker= confirmation-7 kind= :seon.test.runner/confirmation-worker-launch-failure
```

so the suspected shape is: a (confirmation) worker fails to LAUNCH,
the failure is typed and logged, but the RPC path attached to that
worker keeps reading a pipe that will never carry a reply — reading
absence of a worker as a pending answer, the house failure class,
inside the runner whose tally is supposed to be total.

## Masking

This is almost certainly NOT new today: every bare run since the
2026-08-17 platform reds stopped at the platform tier before reaching
the bulk phase, so the bulk path had not executed in 11 days. The
platform repair (2026-08-28) unmasked it.

## Evidence

- Retained roots: `tmp/test-runs/run.cYB3zX` (first wedge, concurrent
  suite load) and `tmp/test-runs/run.ylfash` (second wedge, quiet
  machine).
- `run.ylfash/tmp/test-liveness/37240-1787958955570.log`: last
  progress `END worker=pool-9 … seon.sci.eval-test/generated-sources-
  compose-fork-guard-and-admission` at 23:10:55Z; watchdog at +300 s;
  `deadlocked-thread-ids nil`; ten descendant JVMs alive.
- Thread dumps (same directory): coordinator `main` parked on a pool
  FutureTask; one pool thread RUNNABLE in `readLine` inside
  `worker_rpc_BANG_` (runner.clj:1107/1174); all worker dumps show
  only `worker_command_loop_BANG_`.

## Owner

`seon.test.runner`'s worker RPC seam: a worker launch failure (or
death) must terminate every read attached to it with a typed result —
the read should key on process exit as well as the pipe, and
`execute_worker_task_BANG_` must convert a dead or unlaunchable worker
into the typed unconfirmed outcome it already knows how to report.

## Acceptance

A planted worker-launch failure (or kill of a worker mid-task) ends
the run with a typed per-task outcome and a total tally, no watchdog;
two consecutive bare `bin/test` runs at HEAD complete with a tally.

## Root cause (2026-08-29)

The retained dumps falsify the launch-failure hypothesis: nine workers were idle, but `pool-8` was alive and executing `seon.render-simplification-test/distance-spends-only-real-ref-hops-and-caps-win` in both wedges.
The coordinator had dispatched that ordinary bulk task and waited unboundedly in `read_worker_protocol_BANG_`; worker `pool-8` had not returned from `walk/neighborhood`, so no task-complete frame yet existed.
The `confirmation-7` message came from the injected regression at `test/seon/test_runner_test.clj:158-209`; no retained `confirmation-launch.edn` exists, so production confirmation never started.
The constructibility defect is one raw pipe read with neither `Process.onExit` nor a declared task deadline, followed by an unbounded pool-future `.get`.
Require one total exchange seam that journals identity before a checked write and races reply versus exact process exit versus deadline, converting either non-reply terminal event into an attributed typed tally result.
