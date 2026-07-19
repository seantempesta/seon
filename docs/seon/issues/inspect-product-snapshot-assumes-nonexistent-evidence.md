---
type: issue
status: active
tags: [issue, database, agent, health]
---

# Inspect product snapshot assumes nonexistent evidence

## Evidence

The offline `product_scenarios` scorer is discriminating over its fixtures, but
two fixture fields do not yet name evidence available from their real owners:

- function repair requires both assertions of `:seon.fn/source`. The current
  database value contains only the latest assertion; the versions must be read
  from a Datahike history database value and retain their transaction and
  `added` fields;
- recovery persists the failed child's PID, execution digest, resource usage,
  interrupted eval ref, and diagnostic blob. It does not persist a generic
  `failed_child_id` or a durable list of replacement children. Healthy current
  process identity remains transient in `seon.execution.host/processes` by
  design.

The typed product reader now accepts `:seon.db/history? true`, derives
`seon.db/history` from the one acquired database value, and returns that
history database value with the query result. This closes the first evidence
gap without teaching the harness another history implementation. Focused
Python proof passes 18 tests; the complete current CLJS gate passes 1,186
tests/5,297 assertions.

The parent host now exposes its existing demanded `processes` value through a
loopback-only read. It samples Bun-owned process handles synchronously, writes
no healthy telemetry to the database, and asks no child event loop to
cooperate. This is the real second owner the live recovery scorer must join;
it is not another durable process registry.

The child-recovery scorer and fixture no longer use generic child IDs. They now
join the recovery anchor's real eval ID, failed PID, execution digest, and blob
hash to the current parent-host process for the same agent, requiring a new PID,
the same artifact digest, and ready state. The first native live task owns a
retained branch in `finally`, drives both real `/agents/run` phases, queries the
history evidence, samples the host, validates infrastructure admission for both
pod calls, and releases the exact branch. Its offline native lifecycle/scorer
proof passes in the 21-test product slice. Exact live execution remains the
acceptance gate.

## Acceptance

- The live repair scorer consumes real history datoms and their transaction
  IDs; no synthetic `source_transaction` list is supplied by the solver.
- The live recovery scorer joins the recovery anchor to its actual interrupted
  eval and diagnostic blob, and compares its persisted PID/digest with one
  demanded parent-host process snapshot.
- Replacement count is either proven at the parent host boundary or removed
  from the scorer. It is never inferred from model narration or invented as a
  database field.
- One native Inspect task drives the branch lease, product doors, and scorer;
  fixture tasks remain only deterministic scorer discrimination.
