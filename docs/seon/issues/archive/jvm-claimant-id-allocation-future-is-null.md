---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database]
---

# Prevent JVM claimant identity allocation from dereferencing a null Future

## Problem

The JVM claimant can fail before opening an LLM attempt because identity
allocation returns the core error
`Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null`.
The flat error data classifies the failure as
`:seon.db.id.error/allocation-failed`. Phase-error settlement now closes and
releases the run, so this no longer wedges, but it still prevents a real
provider attempt and reply.

## Evidence

The isolated `claimantpath` live drive created agent
`tricky-bottles-smash`. Pod workload PID `18749` rendered run
`t2we0v3ww65y` at epoch `1`; after narrow host reconciliation, JVM host
workload PID `23197` acquired it at epoch `2`.

The claimant produced no `:seon.ai.attempt/id`. Fault entity `6534` persisted
`:seon.error/fault :core`, `:seon.error/kind :core-bug`, the null-Future
message, and data
`#:seon.db.id{:error :seon.db.id.error/allocation-failed}`. The stack starts at
the new phase-error settlement because that boundary records the returned flat
error; the originating allocation catch has already converted the Throwable
to data. The run and turn then settled visibly and released custody as
designed. Exact database values, claim history, and process generations are in
`tmp/orchestrator/claimantpath-gate.log`.

Commits `62cd2348b` and `356519dd0` landed after that source-frozen drive.
They route JVM database-value allocation through the portable `seon.db`
facade, retain the local-connection path for the writer, add a focused remote
allocation regression, and keep the facade require off the Babashka operator
path.

## Resolution

Commits `62cd2348b` and `356519dd0` route database-value allocation through
the `seon.db` facade while retaining the local Datahike connection path inside
the writer and keeping that facade off the Babashka operator path. Focused CLJ
proof is 14 tests / 83 assertions and focused CLJS proof is 11 / 56.

In the source-frozen claimant2 gate, JVM host workload PID `50645` allocated
attempt identity `sj29e811vgsg`, transitioned it `:open → :success`, and
persisted the provider response and reply blob. No null Future or allocation
fault exists on the run. Evidence:
`tmp/orchestrator/claimant2-gate.log`.

## Owner

`seon.db.id/allocate!` and the JVM claimant's configured serialized-writer
allocation leaf own the allocation result. Diagnosis must preserve the
originating Throwable stack before the allocation catch flattens it and find
which Future-producing writer call can return nil; the phase driver is only
the consumer.

## Acceptance

- The claimant allocates every identity needed to open an attempt without a
  null Future.
- A failed allocation retains a bounded originating cause and owner frame in
  its flat error value.
- The same isolated live turn opens a durable attempt receipt and either
  completes with a provider success and reply blob or settles a later failure
  visibly without retaining custody.
- Focused JVM tests exercise the real configured serialized-writer allocation
  leaf, not a mocked Future.
