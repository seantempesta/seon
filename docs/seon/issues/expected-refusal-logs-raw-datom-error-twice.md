---
type: issue
status: open
severity: friction
tags: [issue, database, render, class/n1, wave/datahike-fork-logging-seam]
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

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — half repaired, and the raw half is the half that was filed.**

Repaired: the writer's log is now one bounded, classified face.
`reference-code/datahike/src/datahike/writer.cljc:105-114` derives
`expected-refusal-face` (kind, one-line cause, attribute) and `:152-157`
logs `:datahike/write-rejected` with that face only — "never attach the
throwable, invocation, or tx args".

Still present: the FIRST entry. The refusal is raised inside
`datahike.db.transaction` with `log/raise`, at
`reference-code/datahike/src/datahike/db/transaction.cljc:29` and `:533`:

```clojure
(log/raise "Cannot add " datom " because of unique constraint: " found …)
```

`replikativ.logging/raise` is defined as "Logging an error and throwing an
exception": it expands to `(trove/log! {:level :error :msg (str …) …})`
followed by the `throw`. The `datom` argument is stringified into that
message, so the raw `#datahike/Datom [...]` entry is emitted at error level
before the writer's bounded face — two log entries per expected refusal,
exactly as filed.

No refused transaction was induced: this verification performs no writes.
The verdict is therefore source-exact on both log sites, not a captured log
pair.

surface: database (the fork's transaction/writer logging seam)

## Error-facet retirement observation — 2026-09-20

The writer's short face still depends on retired `:seon.error/kind` or
Datahike's native `:error` at `writer.cljc:105`. A transaction function that
throws a complete Seon base plus its specific facet has neither. The
`:db.fn/call` seam directly applies the transaction function
(`db/transaction.cljc:1153`), so no native discriminator is added before the
writer selects its log. Such a refusal falls through to the full throwable
and invocation log. This is source evidence, not a captured log assertion.
The error-family lane converted `seon.turn/refuse!` to its declared rule
facet and preserved its value through `seon.db`; it did not restore a kind
for the logger or edit the dependency. The fork's eventual logging
regression must also cover a kind-free transaction-function refusal.

Fix sketch: the unique-constraint and nil-value sites are EXPECTED outcomes
of a caller's transaction, not fork faults — replace `log/raise` with a
plain `throw` of the same `ex-info` at those sites and let the writer's
classified face be the one log. Gate it on our own falsifier, as the note's
Expected section already requires.
