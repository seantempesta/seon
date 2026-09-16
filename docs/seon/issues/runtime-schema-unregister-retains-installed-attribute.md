---
type: issue
status: open
severity: friction
tags: [issue, runtime, schema, database, class/p3]
---

# Schema unregister leaves the installed Datahike attribute

At `a55bdfc80` plus the deletion-presence repair, the real turn test
`seon.cluster.turn-test/runtime-schema-unregister-removes-one-unused-global-schema`
passes its saved return-text, stable program identity, and projection-removal
assertions, but fails the existing assertion that the unused Datahike attribute
is removed: `(contains? (:schema db) :shared.runtime/unregister-me)` is true.

Recorded isolated in-process run 41291 at 2026-09-16T01:25:48Z: three passes,
one failure, zero errors, fresh canonical base and SCI context, armed contracts.
The prior retired-result assertion masked this later observable in the baseline.
The candidate observation rewrite is not retained until this boundary is fixed.

The relevant owner is `src/seon/turn.clj:1036` (`schema-attribute-change-tx`),
called by deletion `row-tx` at `:1205`. It derives a projection diff and emits
`:db.fn/retractEntity` for removed attributes. The owning-seam probe below identifies why the deletion produces no effective
attribute removal. `src/seon/turn.clj` was under
concurrent edits and protected during this lane.

Acceptance: the same real register/unregister turn removes the unused database
attribute and schema definition, preserves its program identity tombstone,
and retains the existing refusal when current data uses an affected attribute.
See [the lane evidence](../../prds/context-generation/research/turn-test-reds-2026-09-16.md).

## Owning-seam probe — 2026-09-16 01:46 UTC

A scoped observer around the real `schema-attribute-change-tx` in the isolated
snapshot JVM captured the complete call during the unchanged register/unregister
scenario:

```clojure
{:turn-test-reds/current-form nil
 :turn-test-reds/candidate-form nil
 :turn-test-reds/stored-form
 {:seon.schema/form "[:int #:seon.db{:index true}]"}
 :turn-test-reds/installed
 {:db/ident :shared.runtime/unregister-me
  :db/valueType :db.type/long
  :db/cardinality :db.cardinality/one
  :db/index true}
 :turn-test-reds/tx-data []}
```

Thus the transaction's database already contains the schema and installed
attribute, while the supplied projection lacks it. The comparison emits no
retraction. The fix belongs at the declaration writer's projection acquisition:
derive the projection from that transaction's actual program facts, including
prior rows in the same batch, before comparing desired declarations. A guard
on this single attribute would leave the class intact. `src/seon/turn.clj`
remains protected; no foreign code or live session was changed.

## Candidate falsified by a dependency boundary — 2026-09-16

After the turn owner became available, the in-process candidate reused
`schema/projection-from-database` at deletion, matching declaration admission.
A composed declaration/deletion regression still returned 4/3/0 (run 48567).
The complete observer proves the normal query misses the just-written schema
while the same read with query-result caching disabled sees its exact form.
The mid-transaction value incorrectly retains committed cache identity.
See [the dependency issue](transaction-functions-retain-committed-query-cache-identity.md).
No candidate production edit or relaxed deletion assertion was retained.
