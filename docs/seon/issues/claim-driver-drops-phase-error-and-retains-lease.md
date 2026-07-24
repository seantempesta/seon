---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Settle a claimed run when a phase returns an error value

## Problem

When a claimant phase returns a flat error value, the portable driver returns
that value to its process-local dispatch thread without recording it, closing
or releasing the run, or scheduling a lease-aware retry. The dispatch thread
then discards the return value. The database retains an open run, a running
turn, and the claimant lease even though no process-local driver thread remains.

## Evidence

During the default-cluster graduation drive, JVM claimant
`82301@2026-07-24T06:34:12.318333Z` acquired run `pbnfs9xudihn` at epoch `2`
after pod rendering. Its LLM phase returned the configuration error documented
in [[jvm-claimant-rejects-inherited-attempt-timeout]] before opening an attempt
receipt.

The run remained open and turn `vdwttk9tkndz` remained
`:running`/`:rendered`. Repeated database-interest scans reacquired the same
claimant and renewed `:seon.agent.run/last-beat-at`, advancing the database from
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
- The claimant does not retain a live lease without a corresponding active
  driver thread.
- The error is queryable through the existing fault/turn evidence path, and a
  later claimant can make progress without a restart or operator action.
- A regression covers an error before attempt admission and an error after a
  durable receipt opens.
