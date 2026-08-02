---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty/unexpected results, or working with resources/seon/schema.edn, seon.schema.edn, or the Malli-to-Datahike bridge. Use for d/transact argument maps or raw vectors, d/q, d/pull, d/entity, lookup-refs, refs/components/identity, upsert, retract, cardinality-many, :db.fn/call transition functions, CAS fences, as-of/since history, listen!, or any 'where do I put this data / how do I read it back' question."
---

# Datahike — Seon Database Patterns

First-party attribute and entity schemas are one EDN map at
`resources/seon/schema.edn`. `seon.schema.edn/load!` loads that one classpath
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
`256b714d97a0e8f952b01a47c693eff2976ccee7`. Verify both the gitlink and the
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
- **Reads are synchronous and local.** `d/q` / `d/pull` / `d/entity` resolve
  against an immutable database value — a pointer, not a fetch. Compose reads
  in straight-line code.
- **Writes are a function call.** Current core owners and test fixtures call
  `datahike.api/transact` on the co-located connection. Do not recreate the
  retired `seon.db` pod facade.

A db is a **value, not a place**. The "race" you think you have ("the DB moved
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
  `d/transact` upserts (same identity value ⇒ update in place, no duplicate).
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
(->> (keys (:schema @connection))
     (filter keyword?)
     (filter #(= "seon.cluster.run" (namespace %)))
     sort)

;; enumerate entities BY ID-ATTR PRESENCE (scan that attr's index)
(d/q '[:find ?id :where [?e :seon.cluster.run/id ?id]] @connection)

;; count by identity attribute, never by a "kind"
(d/q '[:find (count ?e) . :where [?e :seon.cluster.agent/id]] @connection)
```

The grouping label is always an **attribute** (a namespace or an id-attr), never
a "kind".

## Quick start — declare in EDN, load, transact, read back

Put first-party schema forms in the one classpath resource. It is one EDN map
from registry key to Malli form; section comments are only editorial.

```clojure
;; resources/seon/schema.edn — knowledge-base section
{:my.kb.source/id [:string {:seon.db/identity true}]
 :my.kb.source/title :string
 :my.kb.source/rank :int}
```

```clojure
(require '[datahike.api :as d]
         '[seon.schema :as schema]
         '[seon.schema.datahike :as schema.datahike]
         '[seon.schema.edn :as schema.edn])

;; 1. Load and activate the one complete classpath population.
(schema.edn/load! {})
(schema/activate! (schema/snapshot))

;; 2. Install the derived declarations. Production cluster population owns
;;    this step; a focused REPL probe can perform it explicitly.
(d/transact connection
            (schema.datahike/malli->datahike-schema
             [:my.kb.source/id :my.kb.source/title :my.kb.source/rank]))

;; 3. Transact ordinary data, every key namespaced.
(d/transact connection
            [{:my.kb.source/id "s1"
              :my.kb.source/title "Alpha"
              :my.kb.source/rank 1}])

;; 4. Query — synchronous; pick the :find shape:
(d/q '[:find ?t :where [?e :my.kb.source/title ?t]] @connection)
(d/q '[:find [?t ...] :where [?e :my.kb.source/title ?t]] @connection)
(d/q '[:find (count ?e) . :where [?e :my.kb.source/id]] @connection)

;; 5. Pull/entity — read one entity by lookup-ref [identity-attr value]:
(d/pull @connection '[*] [:my.kb.source/id "s1"])
(d/entity @connection [:my.kb.source/id "s1"])
```

The run section of `resources/seon/schema.edn`, `src/seon/cluster/run.cljc`, and
`test/seon/cluster/run_test.clj` are the live worked set: declarations,
identity attributes, refs, in-transaction transitions, and properties over the
real database.

## Schema: one EDN population, one admission gate

Declare the **Malli type** in `resources/seon/schema.edn`; the bridge
(`seon.schema.datahike/malli->datahike-attr`, `src/seon/schema/datahike.cljc`)
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

A config attribute is declared once in `resources/seon/schema.edn`.
`seon.schema.edn/derive-config-forms` derives
`:seon.config/manifest`, `:seon.config/effective`, and
`:seon.config/entity` from those leaf declarations. Never hand-maintain those
three composite maps or a second dial roster. `config/default.edn` is the
complete shipped decision document; it is data, not another schema list.

### Program rows, live context, and the session image are distinct

Keep four states separate; the checked semantic source is
`../data-oriented-clojure/references/program-state.md`.

1. **Static build indexing** analyzes first-party `src/` and `test/` without
   evaluating application forms, then publishes canonical namespace,
   function, schema, and test rows (`src/seon/fn/analyzer.clj:107-151`;
   `src/seon/fn.clj:199-310,403-423,687-773`).
2. **Runtime program-row publication** remains stricter: only a complete
   contracted function, admitted schema, test, or namespace-context row can
   reach the terminal transaction. An uncontracted function never becomes a
   `:seon.fn` row (`src/seon/sci/eval.clj:427-450,789-850,1323-1456`;
   `src/seon/cluster/run.cljc:645-742`).
3. **The process-live cluster SCI ctx** is one mutable interpreter context per
   cluster, shared by that cluster's agents and absent from other clusters
   (`src/seon/cluster.clj:1337-1363`;
   `src/seon/sci/eval.clj:1205-1228`).
4. **The durable session image** records non-program-row definitions as
   `:seon.code.def` facts. Each changed row is exact-reconciled beside the
   terminal receipt as a metadata-faithful EDN value, blob-backed faithful
   value, proven deterministic pure source form, or explicit unrestorable row;
   cold cluster acquisition installs that image into the new ctx
   (`resources/seon/schema.edn:2151`;
   `src/seon/cluster/loop.cljc:325-458,1411-1424`;
   `src/seon/sci/eval.clj:1142-1228`;
   `test/seon/sci/session_image_test.clj:99-217,239-323`).

The session image preserves REPL state; it does not weaken program-row contract
admission or publish scratch definitions as `:seon.fn` program rows
(`src/seon/sci/eval.clj:427-450`;
`resources/seon/schema.edn:2151`).

### Global schema replacement and removal

Schemas are globally identified by `:seon.schema/key`, never owned by the
namespace where registration happened. Runtime `schema/unregister!` only
stages removal inside the current evaluation delta
(`src/seon/schema.cljc:1110-1127`). Seon's terminal transaction refuses
replacement or removal while any directly or transitively affected Datahike
attribute—including an entity schema's child attributes—has current datoms.
Removal also refuses while another schema or function contract depends on the
key. After current datoms and dependencies are retracted, the operation may
commit atomically with the program row and derived Datahike declarations
(`src/seon/schema.cljc:1929-1958`;
`src/seon/cluster/run.cljc:580-742`;
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
(`src/seon/schema/datahike.cljc`) — don't inline.

### Omission ruling — stored and in-memory contracts differ

`[:maybe]` is allowed in in-memory function RETURN contracts (stored
attributes stay nil-free — the bridge forces absence there).

For a stored map, optional field = `{:optional true}` and absent, never nil. To
clear a stored value, retract it. `:any` remains reserved for genuine
third-party boundaries such as a raw Datahike connection or database value.

## Write path

`datahike.api/transact` accepts BOTH of its documented compatibility shapes:

```clojure
(d/transact connection {:tx-data [{::id "s1"}]
                        :tx-meta {:seon.db/process process-ref}})
(d/transact connection [{::id "s1"}]) ; raw vector/sequence shorthand
```

The arg-map is canonical when transaction metadata is needed. A map without
`:tx-data` is rejected; vectors and sequences are wrapped internally as
`{:tx-data ...}`. Grounding:
`reference-code/datahike/src/datahike/api/impl.cljc:30-48`.

`d/transact` THROWS on a rejected transaction — that is the fence working, and
core code inside a transaction relies on it. Agent-facing boundaries convert
the failure to a flat `:seon.error` value.

**Gotcha: inspect the complete cause chain, not only `(ex-data error)`.**
Datahike's `throwable-promise` wraps the writer's `ExecutionException` in an
outer `ex-info` whose data is `{}`; it does not discard the original throwable.
The refusing transition's intact `ex-data` is at the third link, and Datahike's
own CAS/schema rejection data is recoverable the same way (probe and output:
`docs/prds/sci-execution-runtime/research/n3-plan-2026-07-27.md` §8). At the
one transact wrapper, walk `ex-cause` to the end and select the deepest
non-empty `ex-data`. Classify that value; never parse the message. Treat a
chain with no classifiable data as an unknown core failure, not as a refusal.

```clojure
;; UPSERT by identity — same identity value ⇒ same entity, no duplicate.
;; OMITTED keys are LEFT UNCHANGED (absent ≠ cleared):
(d/transact connection [{::id "s1" ::title "Alpha v2"}])

;; CLEAR one field — retraction is EXPLICIT (omission does nothing):
(d/transact connection [[:db/retract [::id "s1"] ::title]])

;; DELETE the whole entity (component children cascade):
(d/transact connection [[:db.fn/retractEntity [::id "s1"]]])

;; CARDINALITY-MANY: transacting a value ADDS to the set. To REPLACE,
;; retract-all then add, bundled BEFORE the add-map in ONE ordered tx:
(d/transact connection [[:db/retract [::id "s1"] ::tags]
                        {::id "s1" ::tags [:lisp :db]}])

;; LINK new entities in ONE tx via shared TEMPID strings. Assume ::person-id
;; and ::author are already declared in resources/seon/schema.edn. A
;; lookup-ref does not resolve forward; a tempid does.
(d/transact connection [{:db/id "p1" ::person-id "alice"}
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
`d/transact` vector is discarded too (REPL-verified).

```clojure
(defn claim-unheld-call
  "Claim an existing open run only when no process currently holds it."
  [db request]
  (let [id (:seon.cluster.run/id request)
        process (:seon.cluster.run/process request)
        run (d/entity db [:seon.cluster.run/id id])]
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

(d/transact connection [[:db.fn/call claim-unheld-call request]])
```

`f` here is a resolved function, so this form is for co-located core code.
Current run custody is process presence: absent is unheld and present is held;
there is no epoch or lease. The production `claim-call` additionally recovers
custody from a process absent from the supplied live-process set and stamps
that holder's running receipts interrupted
(`src/seon/cluster/run.cljc:18-30,265-309`).
`test/seon/cluster/run_test.clj:14-23,487-514` independently models the same
custody fence.
`src/seon/cluster/run.cljc` is the worked example;
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

`d/q` takes the query, then the database value, then any `:in` inputs (`$` is
the db, implicit and first).

```clojure
;; relation / scalar / collection / single-tuple — chosen by :find shape:
(d/q '[:find ?n ?r :where [?e ::name ?n] [?e ::rank ?r]] db)   ;=> #{["A" 1] …}
(d/q '[:find ?n . :where [?e ::name ?n]] db)                   ;=> "A"  (one scalar)
(d/q '[:find [?n ...] :where [?e ::name ?n]] db)               ;=> ["A" "B"]
(d/q '[:find [?n ?r] :where [?e ::name ?n] [?e ::rank ?r]] db) ;=> ["A" 1]

;; :in parameter — pass inputs AFTER the query:
(d/q '[:find [?n ...] :in $ ?min
       :where [?e ::rank ?r] [(>= ?r ?min)] [?e ::name ?n]] db 5)

;; predicate + binding-expr inside :where:
(d/q '[:find ?s :where [?e ::doc ?d] [(count ?d) ?l] [(> ?l 400)] [?e ::sym ?s]] db)

;; pull inside a query — navigate refs:
(d/q '[:find (pull ?e [::name {::parent [::name]}]) :where [?e ::name _]] db)
```

Advanced shapes (aggregates, rules, `not`/`or`, the `:with` aggregate footgun)
→ `references/querying.md`.

### Two read traps that bite everyone

```clojure
;; REF-JOIN, not keyword-in-slot. A ref attr stores an EID, not the target's
;; value. To match by the target's name, JOIN through it:
(d/q '[:find (count ?e) . :where [?e ::run ?r] [?r ::id "run-1"]] db)   ; GOOD
;; (d/q '[:find ?e :where [?e ::run "run-1"]] db)   ; a ref slot holds an EID

;; LOOKUP-REF value is the STORED type. :seon.fn/sym is a :string, so pass the
;; STRING — a quoted symbol throws "Cannot compare String to Symbol":
(d/pull db '[*] [::sym "seon.config/effective"])   ; GOOD
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
(->> (keys (:schema @connection)) (filter keyword?)
     (filter #(= "seon.cluster.run" (namespace %))) sort)
```

An installed attr is a shape to reuse. Query its presence to learn whether live
entities carry it. Check before inventing an attr.

## Common errors and gotchas

- **"Bad entity attribute … not defined in current schema"** — the attribute
  was loaded but never INSTALLED on this connection. Transact
  `(schema.datahike/malli->datahike-schema [attr …])` first. There is no lazy
  install under `:schema-flexibility :write`.
- **An uninstalled attr also throws on read** — a `d/datoms` scan or explicit
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
(d/q '[:find ?user ?process-id ?at
       :where [?e :my.kb.source/id "src-1"]
              [?e :my.kb.source/title _ ?tx]
              [?tx :seon.db/user ?user]
              [?tx :seon.db/process ?process]
              [?process :seon.db.process/id ?process-id]
              [?tx :db/txInstant ?at]]
     db)
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
(`resources/seon/schema.edn:878`;
`src/seon/context.clj:121-140`). Genuinely underivable → a real domain attr.

## Temporal, listeners, triggers (brief)

```clojure
;; Time-travel: derive a db VALUE at another point, pass it as the db.
(d/q '[:find ?v :where [?e ::name ?v]] (d/as-of @connection some-tx))
(d/q '[:find ?e :where [?e ::status :done]] (d/since @connection last-seen))
(d/q '[:find ?v ?tx ?added :where [?e ::name ?v ?tx ?added]]
     (d/history @connection))
```

`as-of` reports its ORIGIN db's basis-t, not the as-of point — don't read the
returned value's `:t` as the time you asked for.

`d/listen!` installs a transaction listener by key; the handler receives the
transaction report (`:db-before`, `:db-after`, `:tx-data`) — never reach back to
the connection from inside it. Event-driven detection through a listener is the
sanctioned alternative to polling or a tuned timeout.

## Key files

| File | Purpose |
|------|---------|
| `resources/seon/schema.edn` | first-party attribute/entity/value schemas |
| `src/seon/schema/edn.clj` | classpath loading, config derivation, population admission |
| `src/seon/schema.cljc` | registry, activation, entity-schema decomposition |
| `src/seon/schema/datahike.cljc` | the Malli→Datahike bridge (extend it here) |
| `src/seon/cluster/run.cljc` | EXEMPLAR: identity, refs, in-transaction transitions |
| `test/seon/cluster/run_test.clj` | the fixture + how to assert commit and refusal |
| `src/seon/fn.clj` | static first-party rows plus global schema EDN rows; `current-src` publication only |
| `src/seon/sci/eval.clj` | selective runtime publication of contracted functions, schemas, tests |
| `src/seon/cluster/loop.cljc` | terminal receipt plus exact `:seon.code.def` reconciliation |
| program section of `resources/seon/schema.edn` | program rows and durable session-image schemas |
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
