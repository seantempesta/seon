---
type: issue
status: resolved
severity: friction
tags: [issue, agent, database, render]
---

# Return bounded readable database results to agents

## Problem

The ambient `seon.db/transact!` return exposed Datahike's complete transaction
report, so rendering serialized `db-before` and `db-after`. A seven-datom write
produced about 2 MB of agent-context output. Unique-conflict refusals also
preserved only Datahike's exception prose and raw entity IDs, so the agent could
not identify the existing owner.

## Evidence

The independent probes are recorded in
[[docs/prds/sci-execution-runtime/research/session-curation-effect-visibility-opus-2026-08-04]]
and
[[docs/prds/sci-execution-runtime/research/session-curation-replay-mechanics-2026-08-04]].

Before the fix, scratch cluster `dbfaces0804` returned a 1,990,355-byte value
for one seven-datom write. Its namespace-conflict face named only raw entity
IDs `14066` and `13995`.

Commit `59edb37fa` projects the ambient return to transaction ID, commit ID,
total datom count, configured bounded committed datoms, and tempids. The
explicit connection arity retains Datahike's exact transaction report and its
database values. The same commit derives unique-conflict data from Datahike's
ex-data and resolves the existing entity through database identity attributes.
It also declares named AI and HTML producers for both important shapes.

The loaded-Var proof on `dbfaces0804` returned the seven-datom projection in
803 bytes and rendered `Committed transaction 536870971 ... with 7 datoms.`
The conflict value contained
`[:seon.cluster.agent/id "root"]`, and both renderers named that owner.
`bin/test seon.db-test` passed 18 tests and 98 assertions.

The required changed-test selector was invoked for the changed database and
schema paths, but its child exited while the selector remained parked for more
than six minutes. That separately tracked harness defect is
[[docs/seon/issues/changed-test-process-cleanup-polls-observable-exit]].

## Owner

`seon.db` owns agent-facing database results, Datahike refusal translation, and
the two database-result render families. The global schema registry owns their
producer declarations.

## Acceptance

Resolved by `59edb37fa`: an ambient write never returns whole database values,
its committed-datom display obeys the configured collection bound, the exact
system transaction report remains available through the explicit arity, and a
unique-conflict face carries and renders the existing owner's identity.
