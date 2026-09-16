---
date: 2026-09-17
lane: maintenance-success-facts
issue: docs/seon/issues/a-successful-cluster-cleanup-cannot-persist-into-maintenance-facts.md
---

# A successful cluster cleanup persists as a maintenance fact

## What the issue claimed, and what the system actually did

The issue ([a-successful-cluster-cleanup-cannot-persist-into-maintenance-facts](../../../seon/issues/a-successful-cluster-cleanup-cannot-persist-into-maintenance-facts.md))
predicted that persisting a SUCCESSFUL cleanup would be *refused* the same
way `ec260cd2e` was refused at the operator's output contract.

It is not refused. It is silently erased. Probed on `default` (pid 38993,
JVM mode, projection handed explicitly):

```clojure
(seon.schema/call-with-projection P
  (fn [] (seon.maintenance/result-entity CLEAN-RESULT)))
;; =>
;; {:seon.operator.cluster-cleanup/managed-root "/repo/operator"
;;  :seon.boot/cluster-name "retired"
;;  :seon.store/branch :cluster-retired
;;  :seon.operator.cluster-cleanup/live-instance-stopped? true
;;  :seon.operator.cluster-cleanup/branch-retired? true
;;  :seon.operator.cluster-cleanup/removed [".../clusters/retired"]
;;  :seon.operator.cluster-cleanup/reclaimed-bytes 8192
;;  :seon.operator.cluster-cleanup/complete? true
;;  :seon.maintenance.result/cluster-cleanup-collection {}}   ; <= the erasure
```

`(#'seon.db/write-error @C P [that-entity])` returned `nil`: write admission
does not object, because `:seon.maintenance.result/cluster-cleanup-collection`
is declared a bare component `:seon.db/ref` and an empty map is an admissible
ref value. So a successful collection reached the database as an EMPTY
component entity — the recurring class in its purest form: the check that
reads absence of the error keys as health, with no fact left saying the
collection happened, what it swept, or what it reclaimed.

The cause was one `select-keys` in `seon.maintenance/project-cluster-cleanup-result`:

```clojure
(assoc :seon.maintenance.result/cluster-cleanup-collection
       (select-keys (:seon.operator.cluster-cleanup/collection result)
                    [:seon.error/kind :seon.error/message]))
```

`seon.operator/collect-store!` returns its verified `:seon.operator.collect/result`
or throws (`src/seon/operator.clj:866`), so on every completed cleanup that
`select-keys` selects from a map that carries neither key.

## Schema: before and after

`resources/seon/schemas/seon.maintenance.result.edn`, before:

```clojure
:cluster-cleanup-collection-component
[:map
 {:seon.db/attributes true}
 [:seon.error/kind :seon.error/kind]
 [:seon.error/message :seon.error/message]]
```

after — the same `[:or result error]` union `ec260cd2e` gave the public slot,
with each arm declared ONCE and referenced, never copied:

```clojure
:collection-error
[:map
 {:seon.db/attributes true}
 [:seon.error/kind :seon.error/kind]
 [:seon.error/message :seon.error/message]]

:cluster-cleanup-collection-component
[:or :seon.maintenance.result/collect-component
 :seon.maintenance.result/collection-error]
```

The success arm is `:seon.maintenance.result/collect-component`, which already
existed and is already installed: it is the exact component every `collect!`
receipt stores. One noun, one shape, two owners.

`resources/seon/schemas/seon.maintenance.edn` gains
`:seon.maintenance/collection-record`, the answer shape of the new query.

## Owner: one projection, both arms

`seon.maintenance/project-collect-result` moved above
`project-cluster-cleanup-result` (no change to its body), and the cleanup
projection now routes the slot through one private `collection-component`:

```clojure
(if (:seon.error/kind collection)
  (select-keys collection [:seon.error/kind :seon.error/message])
  (project-collect-result collection))
```

The writer path was already single: `seon.schedule/settle!` calls
`maintenance/result-entity` and settles arm `:result`, or arm `:error` when
the handler threw or returned a flat error, both through
`[:db.fn/call #'settle-call]` at the serial writer (`src/seon/schedule.clj:568`).
No hunk in `seon.schedule` was needed — the defect was entirely in what the
projection handed that one path.

## The query

`seon.maintenance/last-collection` answers "when was this root last collected,
and how much did it reclaim" from facts alone. A collection's attributes sit
on the receipt's own result entity when a `collect!` firing recorded it, and
under `:seon.maintenance.result/cluster-cleanup-collection` when a cleanup
recorded it; one `or-join` covers both owners, so there is one answer, not two
code paths:

```clojure
(db/q '[:find ?completed-at ?receipt-id ?collection
        :in $ ?root
        :where
        [?collection :seon.operator.collect/managed-root ?root]
        (or-join [?collection ?receipt]
                 [?receipt :seon.maintenance.receipt/result ?collection]
                 (and [?result
                       :seon.maintenance.result/cluster-cleanup-collection
                       ?collection]
                      [?receipt :seon.maintenance.receipt/result ?result]))
        [?receipt :seon.maintenance.receipt/completed-at ?completed-at]
        [?receipt :seon.maintenance.receipt/id ?receipt-id]]
      database managed-root)
```

A root with no completed collection receipt gets
`:seon.maintenance/root-never-collected`, an evidence-complete
`seon.error/diagnostic` — the typed unknown, never an empty map a caller could
read as a clean store.

## Aggregation: per run identity, never per event

One `:seon.maintenance.result` entity per run identity is the existing model:
`seon.schedule/result-identity` derives it from the receipt id, which derives
from the fire id, which derives from task id plus nominal instant. Re-recording
the same run upserts that identity and REPLACES its cardinality-one attributes;
it does not mint a second entity. The regression
`re-recording-one-maintenance-run-replaces-its-attributes` asserts exactly
that (one entity for the id, the later reclaimed-bytes winning).

A per-root aggregate entity was considered and rejected: the collection slot is
declared `:seon.db/component`, so sharing one collection entity across several
receipts would make a component belong to several owners, and every historical
receipt would then report the newest numbers instead of the ones it observed.
"When was this root last collected" is DERIVED by `last-collection` from the
per-run facts instead of being remembered in a mutable mirror.

## Regressions

`test/seon/maintenance_test.clj` (canonical `test-support/with-database`):

- `a-successful-cleanup-persists-its-verified-collection-result` — the projected
  slot carries `:seon.operator.collect/reclaimed-bytes` 4096, and the stored
  component answers reclaimed/swept/complete? and its retained branch roster by
  query;
- `a-refused-collection-keeps-its-typed-error-on-the-same-slot` — the error arm
  is unchanged and claims no reclamation;
- `re-recording-one-maintenance-run-replaces-its-attributes`;
- `last-collection-answers-when-a-root-was-collected-and-what-it-reclaimed` —
  the typed refusal first, then a `collect!` receipt, then a later cleanup
  receipt whose collection becomes the root's latest answer.

`test/seon/maintenance_schema_test.clj`:

- `the-cleanup-collection-slot-declares-both-arms-it-can-carry`;
- `receipt-request-and-operation-result-attributes-are-queryable` gained the
  `:seon.schedule.fire/agent` its fire fixture was missing (a declared, required
  entry of `:seon.schedule.fire/fire`, so the write was refused) — the third of
  the three batch-67 fixture reds in these two namespaces; the other two were
  already fixed in the tree when this lane reached them.

## Verification boundary

- The defect and its cause were reproduced live on `default` (pid 38993) with
  the forms above, before any edit.
- `clj-kondo` clean on the three Clojure files; both edited EDN resources parse.
- **Batch 69 B2 cold on `54d3ee20f`** ran the regressions for the first time:
  three green, three red. All three reds were fixture or expectation defects in
  the tests, none in the projection, the schema or the query:
  1. `last-collection-…` asserted `(:seon.error/diagnostic-offending answer)`.
     `seon.error/diagnostic` moves every `diagnostic-*` field INTO
     `:seon.error/data` and dissocs it from the top level
     (`src/seon/error.clj:337`), so the top-level read was nil. The expectation
     now reads the real path and also asserts the member.
  2. `receipt-request-and-operation-result-attributes-are-queryable` seeded
     `(test-support/program-fn-row "maintenance-schema-test/handler")`, and a
     program fn row refs `[:seon.ns/name …]`, which the fixture never minted —
     "Nothing found for entity id [:seon.ns/name maintenance-schema-test]". The
     handler is now `seon.operator/observe-footprint!`, a namespace the
     canonical population holds. (`seon.schedule-test` solves the same problem
     by minting `{:seon.ns/name handler-ns}` itself; either is admissible, and
     using the canonical population avoids a synthetic namespace row.)
  3. `root-owned-portfolio-initializes-as-queryable-schedule-facts` — the red
     the batch report attributed to `seon.schedule-test` is in
     `seon.maintenance-schema-test`; `seon.schedule-test`'s own schedule rows
     already carry `:seon.schedule/zone-id`. The test's closing "a later
     ordinary cadence transaction remains authoritative" row updated
     `:seon.schedule/expression` alone, and write admission reads an
     identity-keyed map against the WHOLE schedule schema, so the partial
     update was refused for the missing required `:seon.schedule/zone-id`. The
     row now carries it.
- **In process on `default` (pid 63433, fresh store), after the fixes**, base
  realized first on a daemon thread, then one run at a time through
  `seon.test/run` with the namespace reloaded through `seon.test`'s own loader:

  | test | pass / fail / error |
  |---|---|
  | `seon.maintenance-test/last-collection-answers-when-a-root-was-collected-and-what-it-reclaimed` | 8 / 0 / 0 |
  | `seon.maintenance-schema-test/receipt-request-and-operation-result-attributes-are-queryable` | 1 / 0 / 0 |
  | `seon.maintenance-schema-test/root-owned-portfolio-initializes-as-queryable-schedule-facts` | 5 / 0 / 0 |
  | `seon.maintenance-schema-test/the-cleanup-collection-slot-declares-both-arms-it-can-carry` | 4 / 0 / 0 |

  The three regressions batch 69 already ran green
  (`a-successful-cleanup-persists-its-verified-collection-result`,
  `a-refused-collection-keeps-its-typed-error-on-the-same-slot`,
  `re-recording-one-maintenance-run-replaces-its-attributes`) were not re-run
  in process; batch 69 B2 is their proof.
- `seon.maintenance` in pid 63433 is the definition **loaded at boot from
  HEAD**, not a hot-reloaded Var and not a fresh adoption: `bin/seon init --dev
  default` is refusing for every lane on an unrelated uncommitted predicate
  (`seon.cluster.store/file-lock-object?`), and this lane did not retry it.
- No test JVM was launched, `default` was never restarted, and no probe in this
  lane transacted into `default`'s store outside `seon.test/run`'s own fixture.
