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
- **No in-process regression run, and no adoption.** `bin/seon init --dev
  default --changed …` reloaded `seon.maintenance` but then refused with
  `:seon.cluster/source-changed-during-adoption` (a concurrent lane's edit
  landed inside the window), so the adoption commit was never recorded. The
  retry was not attempted: the coordinator had paused in-process runs and
  transacting probes on `default` for the store-growth incident (~1 GB/min,
  writer under measurement), and `default`'s advertisement went missing
  shortly after — the cluster is down and a lane never starts or reforks it.
  Under the adoption-before-a-new-arity rule `last-collection`'s contract
  wrapper cannot admit calls until an adoption lands, so no in-process proof of
  the new function was possible in this window. Every claim above about the NEW code is
  therefore proven by reading and by the pre-edit live reproduction, not by
  execution; the gate request
  `tmp/orchestrator/gate-requests/maintenance-success.txt` is the first
  execution of these regressions.
- No test JVM was launched, `default` was never restarted, and no probe in this
  lane transacted into `default`'s store.
