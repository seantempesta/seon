---
type: research
status: complete
tags: [prd, database, schema, history, datahike, malli]
---

# Schema removal and history probe

## Verdict

The ruled lifecycle is sound, with one explicit boundary:

- A nonidentical schema change or removal refuses while the schema or any
  transitive dependent carries current data.
- After all current affected data is retracted, the change or removal may
  commit.
- With Datahike history enabled and `:db/noHistory` absent, removing the
  current Datahike schema does **not** delete the temporal datoms. An `as-of`
  Datalog query at the pre-retraction basis still returns the old value.
- Datahike's `AsOfDB` does **not** reconstruct the old schema map. It delegates
  `IDB/-schema` to the current origin database. After removal, the historical
  database value therefore has no Datahike schema entry for the old attribute.
- Seon's historical `:seon.schema/key` plus `:seon.schema/form` datoms are the
  missing validation half. Querying those rows at the same basis and building
  an immutable Malli projection restores the old validator without restoring
  any process-global registry.
- An attribute explicitly declared `:db/noHistory true` is the exception:
  retracting its current value intentionally destroys its past value, so an
  `as-of` simulation cannot recover that data. This is existing Datahike
  semantics, not a schema-removal defect.

Thus ordinary historical attributes lose no data when their current schema is
removed. A simulation must pair the historical database value with Seon's
schema projection derived at the same basis; it must not expect Datahike's
`:schema` map to time-travel.

## Dependency and history grounding

The maintained dependency revisions exercised here were:

- Datahike fork `c0a74e12`, pinned by Seon commit `dd3de0d7c`.
- Malli as checked out under `reference-code/malli`.
- Seon's schema usage guard at `fcb50f7b4`, following its first landing at
  `ef1cfa5c1`.

The fork history is exact. Commit `5cdbc88a` added
`reject-index-removal-with-current-data`, the AEVT emptiness check, and the
upstream recurring removal/history tests. Commit `c0a74e12` widened the
retract-entity path from keyword-only identifiers to every Datahike attribute
identifier. Seon pins those two commits in `9be2a8991` and `dd3de0d7c`.

The relevant source boundaries are:

- `reference-code/datahike/src/datahike/db/transaction.cljc:136-141` checks
  the current AEVT before indexed schema removal, and lines 275-305 apply it
  while retracting the schema entity.
- `reference-code/datahike/src/datahike/db.cljc:567-618` defines `AsOfDB`.
  Its searches apply the historical time predicate, but lines 594-605
  delegate schema, reverse schema, attribute properties, and identifier maps
  to `origin-db`.
- `reference-code/malli/src/malli/registry.cljc:17-65` provides immutable,
  composite, and atom-backed mutable registries. There is no unregister
  operation. Malli's own test at
  `reference-code/malli/test/malli/core_test.cljc:3643-3687` proves compiled
  references cache their children and therefore ignore a later mutable
  registry change. Rebuilding Seon's immutable projection is required; a
  `swap! dissoc` against a long-lived registry is insufficient.
- `src/seon/cluster/run.cljc:561-615` derives all affected Datahike attributes,
  checks their current AEVT datoms, and constructs deterministic
  retract-then-declare transaction data. Lines 617-709 apply the same rule to
  deletion and nonidentical replacement inside the terminal transaction.
- `src/seon/schema.cljc:1746-1778` queries schema and function rows from the
  exact database value, and lines 2172-2192 compile validators against exactly
  one immutable projection.

The separation matters. Datahike protects the physical indexed-attribute
removal boundary. Seon owns the stronger semantic rule for every Malli schema
change, including changes whose derived Datahike declaration is unchanged.

## Probe 1: Datahike removal and temporal datoms

This fresh in-memory probe used `:schema-flexibility :write`,
`:keep-history? true`, and an indexed cardinality-one long attribute. The
essential forms were:

```clojure
(d/transact conn [{:db/id 100 :probe/value 7}])
(def data-t (:max-tx @conn))

;; Refuses atomically while 7 is current.
(d/transact conn [[:db.fn/retractEntity :probe/value]])

(d/transact conn [[:db/retract 100 :probe/value 7]])
(d/transact conn [[:db.fn/retractEntity :probe/value]])

(def past (d/as-of @conn data-t))
(d/q '[:find ?v . :where [100 :probe/value ?v]] past)
(get (dbi/-schema past) :probe/value)
```

The exact condensed output was:

```clojure
:current-data-removal
{:refusal
 {:error :transact/schema,
  :entity :probe/value,
  :invalid-updates #:db{:index [true nil]}},
 :same-basis? true,
 :current-value 7}

:after-retract-removal
{:current-schema nil,
 :past-query 7,
 :past-raw-schema nil,
 :past-interface-schema nil,
 :past-time-point 536870914,
 :past-origin-max-tx nil}
```

The refusal left `:max-tx` unchanged and retained both schema and data. After
the data retraction, removal succeeded and the current schema entry vanished,
while the as-of query returned `7`.

The two nil schema observations are expected. `(:schema past)` is nil because
an `AsOfDB` record exposes only its `origin-db` and `time-point` fields.
`(dbi/-schema past)` is authoritative and delegates to the current origin
database, where the attribute has been removed. Likewise, read the requested
historical point from `:time-point`; ordinary map lookup of `:max-tx` on the
wrapper is not the requested basis.

## Probe 2: Seon change after current data deletion

This probe used the production `program-row-tx` transaction function in a
fresh `seon.test-support/with-database` database. The old and new definitions
derived the same Datahike long/index declaration but different Malli
semantics:

```clojure
(def old-form [:int {:seon.db/index true}])
(def new-form [:int {:min 8 :seon.db/index true}])

(d/transact conn [{:seon.schema-usage-guard/base 7}])
(def data-t (:max-tx @conn))

;; Refuses because this is a nonidentical semantic change with current data.
(d/transact
 conn
 [[:db.fn/call #'run/program-row-tx {}
   {:seon.schema/key :seon.schema-usage-guard/base
    :seon.schema/form (pr-str new-form)}]])

(d/transact conn [[:db/retract entity :seon.schema-usage-guard/base]])

;; Now succeeds.
(d/transact
 conn
 [[:db.fn/call #'run/program-row-tx {}
   {:seon.schema/key :seon.schema-usage-guard/base
    :seon.schema/form (pr-str new-form)}]])
```

The exact condensed output was:

```clojure
:seon-change-with-current-data
{:refusal
 {:seon.schema/error :seon.schema/current-data-blocks-change,
  :seon.schema/keys #{:seon.schema-usage-guard/base},
  :seon.schema/data-attributes [:seon.schema-usage-guard/base],
  :seon.error/kind :user-input},
 :same-basis? true,
 :row-form "[:int #:seon.db{:index true}]",
 :value 7}

:seon-change-after-retract
{:current-row-form "[:int {:min 8, :seon.db/index true}]",
 :current-valid-7? false,
 :current-valid-8? true,
 :past-row-form "[:int #:seon.db{:index true}]",
 :past-valid-7? true,
 :past-datalog-value 7,
 :past-raw-schema nil}
```

The historical validator was rebuilt from the historical row, not recovered
from Malli global state:

```clojure
(let [past-db (d/as-of @conn data-t)
      past-form
      (d/q '[:find ?form .
             :in $ ?key
             :where
             [?schema :seon.schema/key ?key]
             [?schema :seon.schema/form ?form]]
           past-db
           :seon.schema-usage-guard/base)
      projection
      (schema/build-projection
       {:seon.schema-usage-guard/base (edn/read-string past-form)})]
  ((schema/projection-validator
    projection :seon.schema-usage-guard/base)
   7))
;; => true
```

The full production operation should use
`schema/projection-from-database past-db`, which also loads function contracts
at that basis. The reduced one-row construction above isolated this schema
fact from unrelated fixture contracts while proving the same projection and
validator mechanism.

## Probe 3: Seon removal after current data deletion

The same fresh fixture was driven through the generic typed deletion row:

```clojure
{:seon.program/delete-identities
 [[:seon.schema/key :seon.schema-usage-guard/base]]}
```

The exact condensed output was:

```clojure
:seon-removal-with-current-data
{:refusal
 {:seon.schema/error :seon.schema/current-data-blocks-change,
  :seon.schema/keys #{:seon.schema-usage-guard/base},
  :seon.schema/data-attributes [:seon.schema-usage-guard/base],
  :seon.error/kind :user-input},
 :same-basis? true,
 :row-present? true}

:seon-removal-after-retract
{:current-row nil,
 :current-datahike-schema nil,
 :past-row-form "[:int #:seon.db{:index true}]",
 :past-validator-7? true,
 :past-datalog-value 7,
 :past-interface-schema nil}
```

This is the exact simulation contract: current row and current Datahike schema
are absent; the historical program row and temporal value both remain; the
former reconstructs validation for the latter.

## Probe 4: the `noHistory` exception

A fresh attribute declared with `:db/noHistory true` was written with `7`, its
basis captured, and then retracted. Even before schema removal, the old value
was unavailable:

```clojure
:no-history-after-removal
{:before-removal-as-of-value nil,
 :current-schema nil,
 :after-removal-as-of-value nil}
```

This means the lifecycle rule must not promise simulation recovery for a
schema whose derived attribute explicitly opts out of history. The schema row
may still describe the old validator, but there is no old value left to
validate. Ordinary attributes in these probes omitted `:db/noHistory` and did
retain their values.

## Recurring acceptance specification

One recurring test should own the complete history class, using a fresh
`:memory` database with `:keep-history? true` and the production population:

1. Register a global schema whose derived attribute is indexed and retains
   history; write a conforming value and capture `(:max-tx @connection)` as
   `data-t`.
2. Attempt both a nonidentical schema replacement and typed schema deletion
   while the value is current. For each, walk the complete cause chain and
   assert `:seon.schema/current-data-blocks-change`, the exact affected
   attribute, unchanged `:max-tx`, unchanged program row, and unchanged value.
3. Retract every current affected datom. Perform the replacement and, in a
   second fresh trial, the deletion. Assert the new current form after change;
   after deletion assert both the program row and Datahike schema entry are
   absent.
4. At `(d/as-of @connection data-t)`, assert Datalog returns the old value and
   the program query returns the old `:seon.schema/form`.
5. Build the immutable projection from the historical database rows and assert
   its validator accepts the old value. Independently assert the current
   projection enforces the replacement when testing the change branch.
6. Assert `(get (dbi/-schema past-db) attribute)` reflects the **current**
   origin schema rather than the old one. This prevents a future test or
   caller from claiming Datahike itself reconstructs historical schema.
7. Add one explicit `:seon.db/no-history? true` trial asserting the as-of value
   is absent after retraction. This preserves the honest exception instead of
   silently overpromising recovery.

The existing `retracted-current-data-allows-change-and-retains-history` test
proves history datoms survive replacement, and the fork's
`indexed-schema-removal-requires-empty-current-aevt` proves Datahike removal
retains an as-of query. The recurring Seon test still needs to join those two
halves by asserting the historical program row rebuilds the validator at the
same basis, plus the `noHistory` exception above.
