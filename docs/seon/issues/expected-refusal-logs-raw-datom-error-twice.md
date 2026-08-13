---
type: issue
status: open
severity: friction
tags: [issue, database, rendering]
---

# An expected transaction refusal logs a raw Datom-heavy error before the bounded writer face

## Problem

When a transaction is refused as designed (e.g. the `:transact/unique`
regression in `seon.cluster.agent-namespace-test`), the log carries TWO
entries for one event: datahike.db.transaction emits the raw exception with
full `#datahike/Datom [...]` literals, then datahike.writer emits the bounded
`:datahike/write-rejected` face. Reported by the gate-fix-db-refusal lane
(2026-08-06, standing ugly-output order); visible in
`tmp/bare-gate-2026-08-06b.log` around the agent-namespace failure.

An EXPECTED refusal is an ordinary outcome the caller receives as a flat
error value — a double log entry with raw Datom internals is noise that
trains readers to skip real errors.

## Expected

One bounded log face per refusal event (the writer's), with the raw
transaction detail reachable through the flat error value's data — or a
deliberate fork-level decision about transaction-log verbosity, recorded.
Owner: the Datahike fork's transaction/writer logging seam
(`reference-code/datahike/src/datahike/db/transaction.cljc`,
`writer.cljc`) — a fork logging change, gated on our own falsifier.

## Acceptance

An expected unique-constraint refusal produces one log line; the flat error
value still carries Datahike's own `:error`/`:attribute` data (proven by the
gate-fix-db-refusal regression).

## N1 disposition — 2026-08-12

Still open in the protected database owner. The exact edit is to classify the
known Datahike unique-constraint refusal once at `seon.db`, return its flat
error value without core-fault logging, and leave one structured log only for
an unexpected fault. This lane did not touch `src/seon/db.clj`.
