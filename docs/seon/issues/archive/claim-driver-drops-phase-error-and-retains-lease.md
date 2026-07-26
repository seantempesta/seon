---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Settle a claimed run when a phase returns an error value

## Problem

When a driver phase returns a flat error value, the portable driver returns
that value to its process-local dispatch thread without recording it, closing
or releasing the run, or scheduling a lease-aware retry. The dispatch thread
then discards the return value. The database retains an open run, a running
turn, and the run-holding process lease even though no process-local driver thread remains.

## Evidence

During the default-cluster graduation drive, cluster JVM
`82301@2026-07-24T06:34:12.318333Z` acquired run `pbnfs9xudihn` at epoch `2`
after pod rendering. Its LLM phase returned the configuration error documented
in [[jvm-claimant-rejects-inherited-attempt-timeout]] before opening an attempt
receipt.

The run remained open and turn `vdwttk9tkndz` remained
`:running`/`:rendered`. Repeated database-interest scans reacquired the same
run-holding process and renewed `:seon.agent.run/last-beat-at`, advancing the database from
basis transaction `536872198` to `536873617` without advancing the turn. The
history contains 711 added heartbeat values from
`2026-07-24T06:44:16.066Z` through `2026-07-24T06:48:37.620Z`. A complete JVM thread dump at
`2026-07-24T06:49:09Z` contained no
`seon-driver-yummy-mirrors-hang-pbnfs9xudihn` virtual thread. No new
`:seon.error/fault :core` datom was recorded.

The run did not settle itself. The external `/agents/run` observation timed out
after 600,004 ms and closed it as `:superseded` at basis transaction
`536873618`; the turn remained `:running`/`:rendered`. The HTTP response
truthfully reported one turn, zero evals, absent model-transport evidence, and
an empty reply.

`src/seon/agent/driver.cljc:401-428` directly returns any
`run.core/error-value?` from `execute-step!`. The JVM dispatch wrapper in
`src/seon/agent/driver/host.clj:487-521` calls `driver/drive-run!` and ignores
its returned value; its `finally` only removes the thread handle. This behavior
is independent of which phase produced the error.

Exact claim/phase history, process generations, and the thread-dump path are in
`tmp/orchestrator/finaldrive-gate.log`.

## Owner

`seon.agent.driver/drive-claim!` owns the portable post-phase transition.
`seon.agent.driver.host/dispatch!` owns only process-local virtual-thread
dispatch and must not become a second lifecycle mechanism.

## Acceptance

- Every phase error has one durable terminal or recoverable database
  transition under the held run and epoch fence; no error value is discarded
  only because a dispatch thread returned.
- The the process does not retain a live lease without a corresponding active
  driver thread.
- The error is queryable through the existing fault/turn evidence path, and a
  later the process can make progress without a restart or operator action.
- A regression covers an error before attempt admission and an error after a
  durable receipt opens.

## Resolution

Commit `094e7a7e6` routes every direct phase error and malformed leaf result
through one portable settlement path. Under the held run/epoch/phase fences,
one transaction marks each open attempt `:crashed`, clears partial text,
publishes the turn as `:error`, closes the run with reason `:error`, retracts
process custody, and retracts the agent's current-run connection. The same
path records the flat error through `seon.error/record!`.

The real-writer regression covers both a `:rendered` failure before attempt
admission and an `:attempt-open` failure with a durable open receipt. It
asserts the persisted fault, crashed receipt, terminal turn and run, retracted
lease, and retracted current-run connection. The isolated `claimantpath` live
drive supplied an independent production-path proof: host workload PID `23197`
acquired run `t2we0v3ww65y` at epoch `2`; a later phase error produced fault
entity `6534`, turn `np3u8fp3vek5` became `:published`/`:error`, the run became
`:closed`/`:error`, and current process custody was absent. No heartbeat-only
wedge remained. Evidence is in `tmp/orchestrator/claimantpath-gate.log`.
