---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty/unexpected results, or working with resources/seon/schemas/, seon.schema.edn, or the Malli-to-Datahike bridge. Use for seon.db/transact! argument maps or raw vectors, seon.db/q, pull, entity, lookup-refs, refs/components/identity, upsert, retract, cardinality-many, :db.fn/call transition functions, CAS fences, as-of/since history, listen!, or any 'where do I put this data / how do I read it back' question."
---

# Datahike — Seon Database Patterns

First-party attribute and entity schemas are one EDN map at
`resources/seon/schemas/`. `seon.schema.edn/load!` loads that classpath
population, and `seon.schema.datahike/malli->datahike-schema` derives the
Datahike declarations — never hand-write Datahike schema. This skill owns the
database-specific facts: EDN population + bridge, query/pull/transact,
refs/components/identity, in-transaction transition functions and CAS fences,
provenance, discovery, and history.

> Hand-offs (single-ownership of facts — don't duplicate them here):
> the general data-oriented mindset (errors-as-values, derive-don't-store, no
> bare keys) → **`data-oriented-clojure`**; what shape to declare and why →
> **`data-modeling`**; test fixtures/generators → **`clojure-testing`**.

## Maintaining the Datahike fork

When the defect is inside `reference-code/datahike/`, that fork is ours to
fix; do not work around it in Seon. The branch-roster repair `357ffc87` and the
planner repair `19f5cdd9` are historical precedents, recorded in
`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Resolution”.

Read `references/fork-maintenance.md` before editing the fork. It maps:

- the planner's `create-plan-via-ir` → logical IR → lower →
  `order-plan-ops` path and the private-Var REPL probe;
- the distinction between the plan cache and result cache, plus
  `q-with-evidence`, `*query-result-cache?*`, cache clearing, and metrics for a
  clean measurement; and
- the fork's own Kaocha focus command plus Seon's separate
  `seon.datahike-fork-test` acceptance gate.

The root gitlink currently selects
`c15272730e74fb3f8bba91f6361c268492a99ba7`. Verify both the gitlink and the
submodule checkout before every fork edit; the dependency ledger and source
map are in `references/fork-maintenance.md`. Never treat a historical repair
commit as current provenance.

## The runtime: co-located, synchronous, one connection per branch

Everything runs in the **cluster JVM**, which embeds Datahike. There is no wire
on the database path.

- **One process holds one physical store lock and may open many branches.**
  Each cluster is a distinct branch in that store
  (`src/seon/cluster/registry.clj:1-38`;
  `src/seon/cluster/store.clj:288-398`). A self-writer connection identity is
  `[store-id branch]`, and Seon refuses a second open connection to the same
  branch (`reference-code/datahike/src/datahike/store.cljc:50-61`;
  `src/seon/cluster/store.clj:369-398`).
- **Creation settings and reopen settings are different.** Fresh creation
  supplies fused index roots and the selected diff buffer; reopen and
  `open-branch!` omit those creation-only keys so Datahike adopts the stored
  values. An incomplete genesis is recreated; a complete store is reopened
  without rewriting its configuration
  (`src/seon/cluster/store.clj:155-183,266-346,369-398`;
  `reference-code/datahike/src/datahike/connector.cljc:183-237,343-362`;
  `test/seon/cluster/store_test.clj:94-162,248-266`).
- **Reads are synchronous and local.** `seon.db/q`, `pull`, `pull-many`,
  `entity`, and `datoms` accept an explicit immutable database value or elide
  it for the calling agent's current cluster database. `entity` and `datoms`
  realize eager ordinary data so process-local Datahike objects never cross
  into SCI (`src/seon/db.clj:198-248,262-392`;
  `test/seon/db_test.clj:31-77,147-171`). Compose reads in straight-line code.
- **Writes are a function call returning a value.** `seon.db/transact!`
  accepts an explicit live connection or elides it for ambient custody, and
  accepts Datahike's positional transaction data or its `{:tx-data :tx-meta}`
  argument map. Rejections are flat `:seon.error` values, not throws from the
  public boundary (`src/seon/db.clj:465-517`;
  `test/seon/db_test.clj:173-198`).
- **Reference admission is identity-only.** Database values and connections
  declare `:seon.schema/identity-only` plus a qualified identity projection.
  Admission consults the registry at every depth and never walks Datahike
  records (`resources/seon/schemas/seon.db.edn:8-34`;
  `src/seon/schema.clj:2572-2640`; `src/seon/sci/admit.clj:141-149,229-260`).
- **`seon.db` is the one database namespace, never a facade.** New first-party
  code calls it for `q`, `pull`, `pull-many`, `entity`, `datoms`, `db`,
  `history`, `as-of`, `since`, and `transact!`. Only `seon.db` itself and the
  store/registry custody owners for open, release, and branch lifecycle may
  require `datahike.api` directly. The pre-ruling 34-namespace call-site sweep
  is still in flight; do not copy those old direct calls
  (`docs/seon/issues/seon-db-is-not-the-one-database-namespace.md:77-125`;
  `src/seon/db.clj:1-14,159-170,198-248,262-443,501-517`).

A database is a **value, not a place**. The "race" you think you have ("the database moved
between deciding and acting") is almost always re-reading the connection three
times instead of threading one value. Better still: don't pre-read — decide
inside the transaction (below).

## There are NO entity kinds — only attributes + connections

The most-repeated correction. An entity has no type/class/kind — it is an id
plus a set of datoms. What it "is" comes entirely from which attributes it
carries and how refs connect it. Schema attaches to **attributes**, never to
entities; an entity is open (it can carry attrs from several domains at once).
(Full mindset + the OO reflexes to drop: `data-oriented-clojure`.)

Applied to datahike, four moves replace every "for each kind":

- **FIND** a set → query by **attribute presence**: `[?e :my.kb.source/id]`
  enumerates every source. There is no "list all of kind K".
- **IDENTIFY** one → a `:db.unique/identity` **attribute**. That is also how
  `db/transact!` upserts (same identity value ⇒ update in place, no duplicate).
- **RELATE / REMOVE** → follow **refs**. Cascade is a property of the
  connection (`:db/isComponent` retracts children), never a kind operation.
- **SCOPE** is two independent axes, don't conflate them:
  - **provenance** — `:seon.db/user` and `:seon.db/process` refs on the
    transaction. They answer who caused the facts and which stable process
    accepted them. Boot/config facts are derived by joining the process ref.
  - **ownership** — a domain ref like `:seon.cluster.run/agent` pointing at the
    owning entity. Answers "whose row is this". Per-agent filtering.

If you catch yourself writing a `:type`/`:kind` field or a per-kind loop, stop
and reframe in attributes + connections + provenance.

**Inspect database contents by attribute.** Use the installed schema to discover
attributes, then query by attribute presence:

```clojure
;; every installed domain attribute, including attrs with no live values
(->> (keys (:schema (db/db connection)))
     (filter keyword?)
     (filter #(= "seon.cluster.run" (namespace %)))
     sort)

;; enumerate entities BY ID-ATTR PRESENCE (scan that attr's index)
(db/q '[:find ?id :where [?e :seon.cluster.run/id ?id]]
      (db/db connection))

;; count by identity attribute, never by a "kind"
(db/q '[:find (count ?e) . :where [?e :seon.cluster.agent/id]]
      (db/db connection))
```

The grouping label is always an **attribute** (a namespace or an id-attr), never
a "kind".

## Quick start — declare in EDN, load, transact, read back

Put first-party schema forms in the one classpath resource. It is one EDN map
from registry key to Malli form; section comments are only editorial.

```clojure
;; resources/seon/schemas/ — owning knowledge-base family
{:my.kb.source/id [:string {:seon.db/identity true}]
 :my.kb.source/title :string
 :my.kb.source/rank :int}
```

```clojure
(require '[seon.db :as db]
         '[seon.schema :as schema]
         '[seon.schema.datahike :as schema.datahike]
         '[seon.schema.edn :as schema.edn])

;; 1. Load and activate the one complete classpath population.
(schema.edn/load! {})
(schema/activate! (schema/snapshot))

;; 2. Install the derived declarations. Production cluster population owns
;;    this step; a focused REPL probe can perform it explicitly.
(db/transact! connection
              (schema.datahike/malli->datahike-schema
               [:my.kb.source/id :my.kb.source/title :my.kb.source/rank]))

;; 3. Transact ordinary data, every key namespaced.
(db/transact! connection
              [{:my.kb.source/id "s1"
                :my.kb.source/title "Alpha"
                :my.kb.source/rank 1}])

;; 4. Query — synchronous; pick the :find shape:
(db/q '[:find ?t :where [?e :my.kb.source/title ?t]] (db/db connection))
(db/q '[:find [?t ...] :where [?e :my.kb.source/title ?t]]
      (db/db connection))
(db/q '[:find (count ?e) . :where [?e :my.kb.source/id]]
      (db/db connection))

;; 5. Pull/entity — read one entity by lookup-ref [identity-attr value]:
(db/pull (db/db connection) '[*] [:my.kb.source/id "s1"])
(db/entity (db/db connection) [:my.kb.source/id "s1"])
```

Use Datahike's own argument-map keys when that operation defines them; never
invent a Seon envelope. These are equivalent to their positional forms, and
each ambient form elides only the database value or connection:

```clojure
(db/q (db/db connection) {:query query-form :args inputs})
(binding [db/*conn* connection]
  (db/q {:query query-form :args inputs}))

(db/pull (db/db connection) {:selector '[*] :eid entity-id})
(binding [db/*conn* connection]
  (db/pull {:selector '[*] :eid entity-id}))

(db/pull-many (db/db connection)
              {:selector '[*] :eids entity-ids})
(db/datoms (db/db connection)
           {:index :avet :components [:my.kb.source/id]})

(db/transact! connection {:tx-data transaction-data
                          :tx-meta transaction-metadata})
```

The accepted shapes and ambient parity are the public contracts at
`src/seon/db.clj:198-248,262-334,380-443,501-517` and the recurring proofs at
`test/seon/db_test.clj:31-77,147-218`.

`resources/seon/schemas/seon.cluster.run.edn`, `src/seon/cluster/run.clj`, and
`test/seon/cluster/run_test.clj` are the live worked set: declarations,
identity attributes, refs, in-transaction transitions, and properties over the
real database.

## Schema: one EDN population, one admission gate

Declare the **Malli type** under `resources/seon/schemas/`; the bridge
(`seon.schema.datahike/malli->datahike-attr`, `src/seon/schema/datahike.clj`)
derives every Datahike facet — `:db/valueType`, `:db/cardinality`, `:db/unique`,
`:db/isComponent`, `:db/index`, `:db/noHistory`. Never write `:db.type/*`
yourself.

`schema.edn/load!` reads `seon/schema.edn` with one classpath resource lookup,
refuses duplicate keys and an unreadable resource, and contributes
candidates without activation. Activation admits the whole population:
references must resolve and every predicate must be registered and carry an
honest generator. Production cluster population then installs the derived
Datahike declarations and canonical schema rows.

**Population is not installation.** Loading/activation teaches the registry;
the database learns an attribute only when its derived declaration is
transacted. Under `:schema-flexibility :write` transacting an uninstalled
attribute throws `Bad entity attribute … not defined in current schema`.
There is no lazy install.

Runtime agent-authored schema declarations still enter through
`schema/register!`; that is selective corpus admission, not the authoring path
for shipped first-party schemas. It passes the candidate plus the complete
population through the same `schema.edn/admit` gate.

### Config dial authority is the leaf registration

A config attribute is declared once under `resources/seon/schemas/`.
`seon.schema.edn/derive-config-forms` derives
`:seon.config/manifest`, `:seon.config/effective`,
`:seon.config/agent-overlay`, and `:seon.config/entity` from those leaf
declarations. Never hand-maintain those composite maps or a second dial roster. `config/default.edn` is the
complete shipped decision document; it is data, not another schema list.

### Program rows, base context, run fork, and session image are distinct

Keep the four states separate; the checked current/target source is
[`program-state.md`](../data-oriented-clojure/references/program-state.md).

### Global schema replacement and removal

Schemas are globally identified by `:seon.schema/key`, never owned by the
namespace where registration happened. Runtime `schema/unregister!` only
stages removal inside the current evaluation delta
(`src/seon/schema.clj:1072-1092`). Seon's terminal transaction refuses
replacement or removal while any directly or transitively affected Datahike
attribute—including an entity schema's child attributes—has current datoms.
Removal also refuses while another schema or function contract depends on the
key. After current datoms and dependencies are retracted, the operation may
commit atomically with the program row and derived Datahike declarations
(`src/seon/schema.clj:1894-1958`;
`src/seon/cluster/run.clj:580-742`;
`test/seon/schema_usage_guard_test.clj:80-397`).

The maintained Datahike fork independently refuses schema-attribute
removal while its current AEVT is nonempty
(`reference-code/datahike/src/datahike/db/transaction.cljc:136-142,276-305`). With
history enabled, later retraction and schema removal preserve ordinary temporal
datoms. Historical simulation pairs an `as-of` database value with Seon's
historical global schema rows at that same basis to rebuild the Malli
projection; Datahike's physical schema map delegates to the current origin and
does not time-travel. `:seon.db/no-history? true` intentionally removes old
values and is the explicit exception
(`docs/prds/sci-execution-runtime/research/schema-removal-history-probe-2026-07-30.md`).

### Bridge derivation (live-verified)

`(schema.datahike/malli->datahike-schema [attr …])` shows exactly what gets
installed:

| EDN declaration | Bridge installs |
|---|---|
| `[:string {:seon.db/identity true}]` | `:db.type/string` + `:db/unique :db.unique/identity` (UPSERT + lookup-ref) |
| `[:and {:seon.db/identity true} ::your-id-shape]` | same — `:and` bridges on its base, so a referenced id shape works |
| `[:enum :open :done]` | `:db.type/keyword`, cardinality one |
| `[:vector :seon.db/ref]` | `:db.type/ref`, cardinality **many** |
| `[:vector {:seon.db/component true} :seon.db/ref]` | `:db.type/ref`, many, **`:db/isComponent true`** (children cascade-retract) |
| `:inst` | `:db.type/instant` |
| `:seon.db/ref` | `:db.type/ref`, cardinality one |
| `[:string {:seon.db/unique true}]` | `:db/unique :db.unique/value` (no upsert) |
| `[:string {:seon.db/no-history? true}]` | `:db/noHistory true` — current value only |
| `[:maybe X]` on a stored attribute | **THROWS** — declare the non-nil base and omit the absent key |

Shapes used in two+ schemas get **declared once and referenced** — never
inlined twice. The canonical example is `:seon.db/ref` (every ref attr
references it). If the bridge can't map a shape you need, **fix the bridge**
(`src/seon/schema/datahike.clj`) — don't inline.

### Omission ruling — stored and in-memory contracts differ

`[:maybe]` is allowed in in-memory function RETURN contracts (stored
attributes stay nil-free — the bridge forces absence there).

For a stored map, optional field = `{:optional true}` and absent, never nil. To
clear a stored value, retract it. Authored contracts refuse `:any`, `:some`,
and `:nil`; database values and connections use named registered predicate
schemas plus identity-only projection
(`src/seon/schema/internal.cljc:20,59-105`;
`resources/seon/schemas/seon.db.edn:8-34`).

## Write path

`seon.db/transact!` preserves BOTH of Datahike's documented transaction
shapes, with either an explicit connection or the calling agent's ambient
cluster connection:

```clojure
(db/transact! connection {:tx-data [{::id "s1"}]
                          :tx-meta {:seon.db/process process-ref}})
(db/transact! connection [{::id "s1"}]) ; positional vector/sequence

(binding [db/*conn* connection]
  (db/transact! {:tx-data [{::id "s2"}]})
  (db/transact! [{::id "s3"}]))
```

The arg-map is canonical when transaction metadata is needed. A map without
`:tx-data` is rejected; vectors and sequences are wrapped internally as
`{:tx-data ...}`. Grounding:
`reference-code/datahike/src/datahike/api/impl.cljc:30-48` ↔
`src/seon/db.clj:465-517`; explicit/ambient parity is asserted at
`test/seon/db_test.clj:173-198`.

Datahike throws internally on a rejected transaction — that is the fence
working — while public `db/transact!` returns the transition's own flat error,
a classified `:seon.db/rejected`, or `:seon.db/unknown-failure`. Development
may rethrow only the unknown core failure under the one panic config
(`src/seon/db.clj:465-499`).

Ambient-custody calls return the declared bounded transaction-report face;
unbound system callers retain Datahike's exact report
(`src/seon/db.clj:964-1029,1113-1137`; `test/seon/db_test.clj:330-401`).
Classified writer refusals still reach the caller unchanged. The maintained
Datahike writer logs one bounded single-line `:datahike/write-rejected` face
without throwable, invocation, or transaction arguments; unclassified writer
failures retain the full diagnostic
(`reference-code/datahike/src/datahike/writer.cljc:85-114,147-161`).

**Gotcha: inspect the complete cause chain, not only `(ex-data error)`.**
Datahike's `throwable-promise` wraps the writer's `ExecutionException` in an
outer `ex-info` whose data is `{}`; it does not discard the original throwable.
The refusing transition's intact `ex-data` is at the third link, and Datahike's
own CAS/schema rejection data is recoverable the same way (probe and output:
`docs/prds/sci-execution-runtime/research/n3-plan-2026-07-27.md` §8). At the
one `seon.db/transact!` boundary, walk `ex-cause` to the end and select the deepest
non-empty `ex-data`. Classify that value; never parse the message. Treat a
chain with no classifiable data as an unknown core failure, not as a refusal.

```clojure
;; UPSERT by identity — same identity value ⇒ same entity, no duplicate.
;; OMITTED keys are LEFT UNCHANGED (absent ≠ cleared):
(db/transact! connection [{::id "s1" ::title "Alpha v2"}])

;; CLEAR one field — retraction is EXPLICIT (omission does nothing):
(db/transact! connection [[:db/retract [::id "s1"] ::title]])

;; DELETE the whole entity (component children cascade):
(db/transact! connection [[:db.fn/retractEntity [::id "s1"]]])

;; CARDINALITY-MANY: transacting a value ADDS to the set. To REPLACE,
;; retract-all then add, bundled BEFORE the add-map in ONE ordered tx:
(db/transact! connection [[:db/retract [::id "s1"] ::tags]
                          {::id "s1" ::tags [:lisp :db]}])

;; LINK new entities in ONE tx via shared TEMPID strings. Assume ::person-id
;; and ::author are already declared under resources/seon/schemas/. A
;; lookup-ref does not resolve forward; a tempid does.
(db/transact! connection [{:db/id "p1" ::person-id "alice"}
                          {::id "s2" ::author "p1"}])
```

### Decide INSIDE the transaction — `[:db.fn/call f request]`

The strongest fence is not a fence at all: put the decision where the state
is. `[:db.fn/call f & args]` applies `f` to the **mid-transaction database
value** followed by `args`, and splices the returned tx-data into the same
transaction (`reference-code/datahike/src/datahike/db/transaction.cljc:1152-1165`).
So one pure function can read current state, REFUSE an ineligible request by
throwing — aborting the whole transaction atomically — and return plain tx-data
otherwise. No caller pre-reads, no observed-* request fields, no window between
deciding and acting. The abort is atomic: every other operation in the same
transaction vector is discarded too (REPL-verified).

```clojure
(defn claim-unheld-call
  "Claim an existing open run only when no process currently holds it."
  [database request]
  (let [id (:seon.cluster.run/id request)
        process (:seon.cluster.run/process request)
        run (db/entity database [:seon.cluster.run/id id])]
    (cond
      (nil? run)
      (throw (ex-info "run does not exist" {:seon.cluster.run/id id}))

      (:seon.cluster.run/closed-at run)
      (throw (ex-info "run is closed" {:seon.cluster.run/id id}))

      (:seon.cluster.run/process run)
      (throw (ex-info "run is held"
                      {:seon.cluster.run/id id
                       :seon.cluster.run/process
                       (:seon.cluster.run/process run)}))

      :else
      [[:db/add (:db/id run) :seon.cluster.run/process process]])))

(db/transact! connection [[:db.fn/call claim-unheld-call request]])
```

`f` here is a resolved function, so this form is for co-located core code.
Current run custody is process presence: absent is unheld and present is held;
there is no epoch or lease. The production `claim-call` additionally recovers
custody from a process absent from the supplied live-process set and stamps
that holder's running receipts interrupted
(`src/seon/cluster/run.clj:18-30,264-309`).
`test/seon/cluster/run_test.clj:14-23,487-514` independently models the same
custody fence.
`src/seon/cluster/run.clj` is the worked example;
`test/seon/cluster/run_test.clj` asserts both rails.

### The CAS work-fence (commit iff the database fact has not moved)

A `[:db.fn/cas ref attr old new]` with **`old == new`** is an in-tx assertion
"this value is STILL `old`". Lead a work-tx with it and the whole tx commits
atomically iff the assertion holds; otherwise it aborts with a
`:transact/cas` error recoverable from the cause chain's deepest non-empty
`ex-data` (see the gotcha above). An `old` of **`nil` means
"this attribute must be ABSENT"** — the canonical way to open a pointer race
exactly once. On a cardinality-many attribute the assertion is "some current
value equals `old`". CAS is pure transaction data, unlike a
`:db.fn/call` carrying a resolved function. Source:
`reference-code/datahike/src/datahike/db/transaction.cljc:973-990` (`compare-and-swap`);
`:db/cas` is an accepted alias.

## Read path — Datalog, synchronous

`db/q` preserves Datahike's positional query interface and its `{:query :args}`
argument-map interface. The database value is an ordinary `:in` input; when
the one `$` source is omitted, `db/q` inserts the calling agent's current
database at its parsed source position (`src/seon/db.clj:172-248`;
`test/seon/db_test.clj:31-58`).

```clojure
;; relation / scalar / collection / single-tuple — chosen by :find shape:
(db/q '[:find ?n ?r :where [?e ::name ?n] [?e ::rank ?r]] database)
#{["A" 1] ["B" 2]}

(db/q '[:find ?n . :in $ ?wanted
        :where [?e ::name ?n] [(= ?n ?wanted)]]
      database "A")
"A"

(db/q '[:find [?n ...] :in $ ?wanted
        :where [?e ::name ?n] [(= ?n ?wanted)]]
      database "A")
["A"]

(db/q '[:find [?n ?r] :in $ ?wanted
        :where [?e ::name ?n] [?e ::rank ?r] [(= ?n ?wanted)]]
      database "A")
["A" 1]

;; :in parameter — pass inputs AFTER the query:
(db/q '[:find [?n ...] :in $ ?min
        :where [?e ::rank ?r] [(>= ?r ?min)] [?e ::name ?n]]
      database 5)

;; predicate + binding-expr inside :where:
(db/q '[:find ?s :where
        [?e ::doc ?d] [(count ?d) ?l] [(> ?l 400)] [?e ::sym ?s]]
      database)

;; pull inside a query — navigate refs:
(db/q '[:find (pull ?e [::name {::parent [::name]}])
        :where [?e ::name _]]
      database)
```

Advanced shapes (aggregates, rules, `not`/`or`, the `:with` aggregate footgun)
→ `references/querying.md`.

### Two read traps that bite everyone

```clojure
;; REF-JOIN, not keyword-in-slot. A ref attr stores an EID, not the target's
;; value. To match by the target's name, JOIN through it:
(db/q '[:find (count ?e) .
        :where [?e ::run ?r] [?r ::id "run-1"]]
      database) ; GOOD
;; (db/q '[:find ?e :where [?e ::run "run-1"]] database)
;; a ref slot holds an EID

;; LOOKUP-REF value is the STORED type. :seon.fn/sym is a :string, so pass the
;; STRING — a quoted symbol throws "Cannot compare String to Symbol":
(db/pull database '[*] [::sym "seon.config/effective"]) ; GOOD
```

Want a number? Put `(count …)` in the query rather than listing then counting;
an agent-facing surface clips large result sets, and the count is what you
wanted anyway. Empty `#{}` on a query that should match? The attribute keyword
is almost certainly misspelled or uninstalled.

## Discovery — inspect the database before declaring a new shape

```clojure
;; EVERY attr installed on the db, including attrs with no live values.
;; Filter keyword? — the map is also keyed by numeric attr-eid (a datahike
;; internal):
(->> (keys (:schema (db/db connection))) (filter keyword?)
     (filter #(= "seon.cluster.run" (namespace %))) sort)
```

An installed attr is a shape to reuse. Query its presence to learn whether live
entities carry it. Check before inventing an attr.

## Common errors and gotchas

- **"Bad entity attribute … not defined in current schema"** — the attribute
  was loaded but never INSTALLED on this connection. Transact
  `(schema.datahike/malli->datahike-schema [attr …])` first. There is no lazy
  install under `:schema-flexibility :write`.
- **An uninstalled attr also throws on read** — a `db/datoms` scan or explicit
  pull of an unknown attribute throws rather than returning empty. Gate a raw
  scan on `(contains? (:schema @connection) attr)`.
- **Empty results** — wrong attr spelling, a type mismatch (querying `30` where
  `30.0` is stored), or a ref-join you wrote as keyword-in-slot.
- **Nil values rejected** — never store nil. To clear, `[:db/retract eid attr]`.
- **`:db.type/long` rejects a `java.lang.Integer`** — `(count …)` and other
  int-returning calls must be `(long …)` before they are transacted, or you get
  `Bad entity value … Must be conform to: (= (class %) java.lang.Long)`
  (REPL-verified 2026-07-27).
- **Schema evolution** — use Seon's global schema lifecycle, never raw
  Datahike schema surgery. Nonidentical change/removal refuses while affected
  current datoms or contract dependencies exist; after retraction it may
  commit. Adding uniqueness still fails if duplicates exist. Ordinary history
  survives; `no-history` values intentionally do not.

## Transaction metadata — two durable provenance refs

Every datom's fourth field names its transaction, and the transaction is a real
entity. Datahike turns `:tx-meta` into datoms on that entity and stamps
`:db/txInstant`. Seon persists exactly two ordinary provenance facts:

- `:seon.db/user` — a ref to the existing human/root or agent entity; and
- `:seon.db/process` — a ref to a stable `:seon.db.process/id` such as
  `:seon.db.process/repl`, `/boot`, or `/config`.

Who/process/when wrote a datom is therefore a join:

```clojure
;; which database user and process wrote this title?
(db/q '[:find ?user ?process-id ?at
        :where [?e :my.kb.source/id "src-1"]
               [?e :my.kb.source/title _ ?tx]
               [?tx :seon.db/user ?user]
               [?tx :seon.db/process ?process]
               [?process :seon.db.process/id ?process-id]
               [?tx :db/txInstant ?at]]
      database)
```

Do not register provenance projections such as `created-by`, `created-at`,
`updated-at`, or `source-turn` on domain entities. Join the transaction. A real
custom transaction fact such as an import batch may be written on
`:db/current-tx`; it is not another Seon provenance mechanism.

**The one exception:** a pre-event basis transaction — an application fact
about a database value observed before the entity's own transaction. The
current example is `:seon.context.capture/basis-t`, the `:max-tx` of the
database value used to render the prompt; intervening transactions mean the
capture entity's creation transaction cannot derive that earlier basis
(`resources/seon/schemas/seon.cluster.run.edn`;
`src/seon/context.clj:121-140`). Genuinely underivable → a real domain attr.

## Temporal, listeners, triggers (brief)

```clojure
;; Time-travel: derive a db VALUE at another point, pass it as the db.
(db/q '[:find ?v :where [?e ::name ?v]]
      (db/as-of (db/db connection) some-tx))
(db/q '[:find ?e :where [?e ::status :done]]
      (db/since (db/db connection) last-seen))
(db/q '[:find ?v ?tx ?added :where [?e ::name ?v ?tx ?added]]
      (db/history (db/db connection)))
```

`as-of` reports its origin database value's basis transaction, not the as-of
point — don't read the returned value's `:t` as the time you asked for.

Datahike's `listen!` installs a transaction listener by key; the handler
receives the transaction report (`:db-before`, `:db-after`, `:tx-data`) — never
reach back to the connection from inside it. It remains system-side rather
than part of the agent-first core surface. Event-driven detection through a
listener is the sanctioned alternative to polling or a tuned timeout
(`docs/seon/issues/seon-db-is-not-the-one-database-namespace.md:44-59`).

## Key files

| File | Purpose |
|------|---------|
| `resources/seon/schemas/` | first-party attribute/entity/value schemas |
| `src/seon/db.clj` | one database namespace: explicit/ambient reads, writes, ordinary-data projection, flat errors |
| `test/seon/db_test.clj` | positional/argument-map and explicit/ambient parity; eager return proofs |
| `src/seon/schema/edn.clj` | classpath loading, config derivation, population admission |
| `src/seon/schema.clj` | registry, activation, entity-schema decomposition |
| `src/seon/schema/datahike.clj` | the Malli→Datahike bridge (extend it here) |
| `src/seon/cluster/run.clj` | EXEMPLAR: identity, refs, in-transaction transitions |
| `test/seon/cluster/run_test.clj` | the fixture + how to assert commit and refusal |
| `src/seon/fn.clj` | static first-party rows plus global schema EDN rows; `current-src` publication only |
| `src/seon/sci/eval.clj` | selective runtime publication of contracted functions, schemas, tests |
| `src/seon/cluster/loop.clj` | terminal receipt plus exact `:seon.def` reconciliation |
| program families under `resources/seon/schemas/` | program rows and durable session-image schemas |
| `test/seon/sci/session_image_test.clj` | cold session-image restoration acceptance |
| `reference-code/datahike/src/datahike/api/impl.cljc` | accepted transact argument shapes |
| `reference-code/datahike/` | the fork's source — read it, don't guess semantics |

## When to read references

- `references/querying.md` — aggregates, rules, `not`/`or`, predicates, the
  `:with` footgun, performance.
- `references/fork-maintenance.md` — planner internals, private-Var probes,
  cache evidence, reloads, and the fork/root test boundary.
For modeling decisions, use the separate `data-modeling` skill. For dependency
semantics, read `reference-code/datahike/` directly.
