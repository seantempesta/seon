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

## Authored-shape scratch observation — 2026-09-23

The scratch Juniper installer refused `agent-already-running` and emitted the
complete `encode-call-output-in` projection inside writer invocation data,
including all function contracts and schema forms. The scratch log grew to
674,642,724 bytes; the diagnostic was unreadable. This is live evidence
of the existing unbounded invocation-logging class, not a new logging owner.
The bounded cause, publication identity and incomplete seed are preserved in
[the authored-shape landing note](../../prds/steward-platform/research/schema-shape-authored-2026-09-23.md).
The owned scratch root was downed and removed; no foreign logger was edited.

## Writer argument dump removed — 2026-09-23 owner ruling

Fork `006e634ae955c186619adb5f3868cca29d8c97fb`, pushed to
`seantempesta/datahike/main`, removes writer invocation/argument serialization.
`writer.cljc:85–103` preserves exception class/message/stack without exception
data and retains branch/commit plus supplied cluster/test/run/error identities.
When logging completes, the callback carries the identical exception and actual
objects; the scalar-evidence observation below identifies a failure before that
delivery. The
retired discriminator and message truncation path are deleted. Maintained-fork
regression: 13 assertions, zero failures/errors, 36.042 ms. This resolves the
writer's projection dump; the earlier transaction `log/raise` sites remain a
separate part of this open issue. The full six-test duration comparison is
currently refused by the stale exported config contract, as recorded in the
[test-system landing note](../../prds/steward-platform/research/test-system-fork-2026-09-23.md).

## Scalar evidence interrupts writer error delivery — B3 probe, 2026-09-22

At `2026-09-22T07:57:19.686617Z`, the kind-cut lane's selected
`seon.effect-test/request-commits-before-io-dispatch-and-settles-once` began.
The supervisor then reported `IllegalArgumentException: find not supported on
type: java.lang.String`. Its stack identifies `clojure.core/select-keys`,
`datahike.writer/write-error-log` at `writer.cljc:90`, and the writer catch at
`:142`. The dependency remains pinned to
`006e634ae955c186619adb5f3868cca29d8c97fb`.

Source inspection verifies that `write-error-log` applies `select-keys` directly
to nested `[:seon.error/data :seon.error/diagnostic-evidence]`. The catch invokes
that logger before putting the original exception on the callback. Scalar
evidence therefore throws in the error-reporting path before delivery. The
captured trace does not expose the original transaction refusal; its cause is
unknown, and this observation does not attribute it to the kind cut.

The earlier selected request at `05:51:28.320381Z` also stopped progressing in
this same effect test and exited at the runner's no-progress bound. The final
request's completion and namespace observations are recorded in the
[B3 landing note](../../prds/agent-platform/landing/lane-b3-kind-cut-2026-09-21.md).
No dependency code or writer policy was changed by this lane. The logging
owner's regression needs a scalar diagnostic-evidence case and positive callback
delivery evidence, in addition to the existing bounded-output cases.
