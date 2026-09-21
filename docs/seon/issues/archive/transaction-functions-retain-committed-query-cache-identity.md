---
type: issue
status: resolved
severity: blocker
tags: [issue, database, runtime, schema, class/p3]
---

# Transaction functions retain a committed query-cache identity

Resolved 2026-09-16 by maintained fork commit `49ea5933` and its Seon
acceptance regression. `datahike.core/with` detaches cache identity before
transaction functions execute. Focused Kaocha runs in default's existing JVM
pass 17/0/0 before and after source reload. The canonical declaration/deletion
regression passes 8/0/0 after loading the file definitions, recorded run 74085.
See [the landing record](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md).
Cold integration proof remains the orchestrator's gate; no test JVM was launched.

Fresh canonical in-process probe, 2026-09-16 03:35 UTC, isolated development
cluster `turn-test-reds19`, committed snapshot `9c03c1ec1` plus owned changes:
one transaction declares `:turn-schema-batch/value` and then deletes it through
`seon.turn/row-tx`. The deletion database contains the declaration, but the
normal `seon.schema/projection-from-database` query returns a projection without
it. Binding the dependency's existing `datahike.query/*query-result-cache?*`
to false around the same read returns `[:int {:seon.db/index true}]`.
The value still carries `:datahike.cache/committed? true`, connection identity,
generation, and the entering committed head. The
[complete captured values](../../prds/context-generation/research/turn-test-reds-batch19-schema-cache-2026-09-16.edn)
and [probe source](../../prds/context-generation/research/turn-test-reds-batch19-schema-cache-probe-2026-09-16.clj)
are retained with the landing record.

`reference-code/datahike/src/datahike/core.cljc:130` initializes the transaction
report's `:db-after` from the committed input unchanged. It clears cache context
only after `transact-tx-data` returns (`:139`). Transaction functions execute
before that clearing (`db/transaction.cljc:1152`). The existing
`datahike.db/clear-cache-context` (`db.cljc:408`) describes the required ownership
boundary but has no caller in the dependency source searched by this probe.

This falsifies the sufficiency of only changing the declaration writer to
derive its current projection: that candidate still passes 4 and fails 3
assertions in the composed declaration/deletion regression (run 48567).
The separate dependency case returns an unknown Malli invalid-schema failure;
that second symptom has not independently been reduced to the cache defect.

Acceptance: every speculative/mid-transaction database value is excluded from
committed query-cache identity before any transaction function runs. Verify
that repeated identical queries observe earlier writes in the same transaction,
then replay the declaration/deletion regression with ordinary caching enabled.
At that earlier stop, no cache disabling, dependency edit, or local projection
workaround had landed. The owner subsequently granted the dependency repair.
The related
[unregister issue](runtime-schema-unregister-retains-installed-attribute.md)
remains open.
