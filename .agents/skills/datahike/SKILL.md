---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty/unexpected results, or working with seon.schema / seon.schema.datahike. Use for schema/register!, the Malli-to-Datahike bridge, d/transact, d/q, d/pull, d/entity, lookup-refs, refs/components/identity, upsert, retract, cardinality-many, :db.fn/call transition functions, CAS fences, as-of/since history, listen!, or any 'where do I put this data / how do I read it back' question."
---

# Datahike — Seon Database Patterns

`seon.schema/register!` is the single source of truth for every attribute's
shape, and `seon.schema.datahike/malli->datahike-schema` derives the Datahike
declarations from it — you NEVER hand-write datahike schema. This skill owns the
database-specific facts: register + bridge, query/pull/transact,
refs/components/identity, in-transaction transition functions and CAS fences,
provenance, discovery, and history.

> Hand-offs (single-ownership of facts — don't duplicate them here):
> the general data-oriented mindset (errors-as-values, derive-don't-store, no
> bare keys) → **`data-oriented-clojure`**; what shape to register and why →
> **`data-modeling`**; test fixtures/generators → **`clojure-testing`**.

## The runtime: co-located, synchronous, one writer per store

Everything runs in the **cluster JVM**, which embeds Datahike. There is no wire
on the database path.

- **One live write connection per store.** That invariant is structural: two
  JVMs writing one store both won the same epoch CAS and 40 of 40 returned
  commits vanished. One process may host many stores; a store may not have two
  writers.
- **Reads are synchronous and local.** `d/q` / `d/pull` / `d/entity` resolve
  against an immutable database value — a pointer, not a fetch. Compose reads
  in straight-line code.
- **Writes are a function call.** `d/transact` on the co-located connection.
  There is no `seon.db` facade in the fresh tree yet; the store owner and test
  fixtures call `datahike.api` directly. When `seon.db` lands it becomes the
  sole API and returns an envelope rather than throwing.

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
  `transact!` upserts (same identity value ⇒ update in place, no duplicate).
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

## Quick start — register, transact (check the envelope), read back

Inside namespace `my.kb.source` you write `::id` and the reader expands it to
`:my.kb.source/id`. Schemas live in the namespace whose name they carry.

```clojure
(require '[datahike.api :as d]
         '[seon.schema :as schema]
         '[seon.schema.datahike :as schema.datahike])

;; 1. REGISTER the Malli shape — this teaches the schema registry only.
(schema/register! ::id    [:string {:seon.db/identity true}])  ; natural key → upsert
(schema/register! ::title :string)
(schema/register! ::rank  :int)

;; 2. INSTALL the derived declarations — this teaches the DATABASE. Under
;;    :schema-flexibility :write an uninstalled attribute THROWS on transact.
(d/transact connection
            (schema.datahike/malli->datahike-schema [::id ::title ::rank]))

;; 3. TRANSACT ordinary data, every key namespaced.
(d/transact connection [{::id "s1" ::title "Alpha" ::rank 1}])

;; 4. QUERY — synchronous; pick the :find shape:
(d/q '[:find ?t :where [?e ::title ?t]] @connection)        ;=> #{["Alpha"]}  relation
(d/q '[:find [?t ...] :where [?e ::title ?t]] @connection)  ;=> ["Alpha"]     collection
(d/q '[:find (count ?e) . :where [?e ::id]] @connection)    ;=> 1             scalar

;; 5. PULL / ENTITY — read one entity by lookup-ref [identity-attr value]:
(d/pull @connection '[*] [::id "s1"])     ;=> {:db/id N :my.kb.source/id "s1" …}
(d/entity @connection [::id "s1"])
```

`src/seon/cluster/run.cljc` + `test/seon/cluster/run_test.clj` are the live
worked pair: identity attributes, refs, in-transaction transitions, and a
model-based property over the real database. `src-old/my/kb.cljc` is the
quarry's runnable manual — read it for idiom, never extend it.

## Schema: register! is the single source of truth

Register the **Malli type**; the bridge
(`seon.schema.datahike/malli->datahike-attr`, `src/seon/schema/datahike.cljc`)
derives every datahike facet — `:db/valueType`, `:db/cardinality`, `:db/unique`,
`:db/isComponent`, `:db/index`, `:db/noHistory`. You never write `:db.type/*`
yourself.

**Registration is not installation.** `register!` teaches the Malli registry;
the database learns an attribute only when you transact its derived declaration.
Under `:schema-flexibility :write` (what Seon runs) transacting an uninstalled
attribute throws
`Bad entity attribute … not defined in current schema` — REPL-verified
2026-07-27. There is no lazy install.

```clojure
(schema/register! ::name   :string)
(schema/register! ::count  :int)
(schema/register! ::active :boolean)
(schema/register! ::ratio  :double)
(schema/register! ::when   :inst)          ; java.util.Date / js/Date
(schema/register! ::uid    :uuid)
(schema/register! ::tag    :keyword)
(schema/register! ::status [:enum :open :done])      ; enum of keywords
(schema/register! ::tags   [:vector :keyword])        ; cardinality-MANY
(schema/register! ::parent :seon.db/ref)              ; ref → another entity
```

### Bridge derivation (live-verified)

`(schema.datahike/malli->datahike-schema [attr …])` shows exactly what gets
installed:

| You register | Bridge installs |
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
| `[:maybe X]` | **THROWS** — register the non-nil base and omit the absent key |

Shapes used in two+ schemas get **registered once and referenced** — never
inlined twice. The canonical examples: `:seon.db/ref` (every ref attr
references it). If the bridge can't map a shape you need, **fix the bridge**
(`src/seon/schema/datahike.cljc`) — don't inline.

### Banned types

No `:any` / `:some` / `:nil` / `[:maybe X]` for seon-authored data. Optional
field = `{:optional true}` and **absent, never nil**. (`:any` is allowed only
at genuine third-party boundaries — e.g. a raw datahike db handle.)

## Write path

`d/transact` THROWS on a rejected transaction — that is the fence working, and
core code inside a transaction relies on it (below). A boundary that an agent
can reach catches it and returns an `::ok? false` envelope instead; the error's
`:seon.error/data :seon.error/kind` distinguishes `:user-input` (fix tx-data,
retry) from `:core-bug` (report it).

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

;; LINK new entities in ONE tx via shared TEMPID strings. A lookup-ref does
;; NOT resolve forward to a not-yet-committed entity; a tempid does. ::author
;; is a ref, so the tempid "p1" in its slot resolves to the new person:
(schema/register! ::person-id [:string {:seon.db/identity true}])
(schema/register! ::author :seon.db/ref)
(d/transact connection [{:db/id "p1" ::person-id "alice"}
                        {::id "s2" ::author "p1"}])
```

### Decide INSIDE the transaction — `[:db.fn/call f request]`

The strongest fence is not a fence at all: put the decision where the state
is. `[:db.fn/call f & args]` applies `f` to the **mid-transaction database
value** followed by `args`, and splices the returned tx-data into the same
transaction (`reference-code/datahike/src/datahike/db/transaction.cljc:1142`).
So one pure function can read current state, REFUSE an ineligible request by
throwing — aborting the whole transaction atomically — and return plain tx-data
otherwise. No caller pre-reads, no observed-* request fields, no window between
deciding and acting. The abort is atomic: every other operation in the same
`d/transact` vector is discarded too (REPL-verified).

```clojure
(defn claim-call
  "Refuse unless the run is open and unclaimed; otherwise return tx-data."
  [db {::keys [run-id process now] :as request}]
  (let [run (d/entity db [::id run-id])]
    (when-not (eligible? run now)
      (throw (ex-info "run is not claimable" {::run-id run-id})))
    [{::id run-id ::process process ::lease-until (lease-end now)}]))

(d/transact connection [[:db.fn/call claim-call request]])
```

`f` here is a resolved function, so this form is for CO-LOCATED core code —
a closure cannot cross a wire. `src/seon/cluster/run.cljc` is the worked
example; `test/seon/cluster/run_test.clj` asserts both rails.

### The CAS work-fence (commit iff the database fact has not moved)

A `[:db.fn/cas ref attr old new]` with **`old == new`** is an in-tx assertion
"this value is STILL `old`". Lead a work-tx with it and the whole tx commits
atomically iff the assertion holds; otherwise it aborts with a
`:transact/cas` error recoverable from the cause chain's deepest non-empty
`ex-data` (see the gotcha above). An `old` of **`nil` means
"this attribute must be ABSENT"** — the canonical way to open a pointer race
exactly once. On a cardinality-many attribute the assertion is "some current
value equals `old`". CAS is pure data, so unlike `:db.fn/call` it can cross a
wire. Source:
`reference-code/datahike/src/datahike/db/transaction.cljc:963` (`compare-and-swap`);
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
(d/pull db '[*] [::sym "seon.db/query"])   ; GOOD
```

Want a number? Put `(count …)` in the query rather than listing then counting;
an agent-facing surface clips large result sets, and the count is what you
wanted anyway. Empty `#{}` on a query that should match? The attribute keyword
is almost certainly misspelled or uninstalled.

## Discovery — inspect the database before registering a new shape

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
  was registered but never INSTALLED on this connection. Transact
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
- **Schema evolution** — add attrs any time; you cannot change an existing
  attr's value type, and adding uniqueness fails if duplicates exist.

## Transaction metadata — two durable provenance refs

Every datom's fourth field names its transaction, and the transaction is a real
entity. Datahike turns `:tx-meta` into datoms on that entity and stamps
`:db/txInstant`. Seon persists exactly two ordinary provenance facts:

- `:seon.db/user` — a ref to the existing human/root or agent entity; and
- `:seon.db/process` — a ref to a stable `:seon.db.process/id` such as
  `:seon.db.process/repl`, `/boot`, or `/config`.

Turn, eval, test, replay, and other execution values remain process-local
unless they are real domain facts. Who/process/when wrote a datom is therefore a join:

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

**The one exception:** a PRE-event snapshot coordinate — an application fact
about a db value observed BEFORE the entity's own tx (canonical example:
`:seon.agent.turn/rendered-as-of`, the frozen basis-t a prompt rendered from;
other agents' txs interleave, so the entity's creation-tx is NOT that
coordinate). Genuinely underivable → a real domain attr.

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
| `src/seon/schema.cljc` | `register!`, the registry, entity-schema decomposition |
| `src/seon/schema/datahike.cljc` | the Malli→Datahike bridge (extend it here) |
| `src/seon/cluster/run.cljc` | EXEMPLAR: identity, refs, in-transaction transitions |
| `test/seon/cluster/run_test.clj` | the fixture + how to assert commit and refusal |
| `src-old/my/kb.cljc` | quarry: runnable manual — read for idiom, do not extend |
| `reference-code/datahike/` | the fork's source — read it, don't guess semantics |

## When to read references

- `references/querying.md` — aggregates, rules, `not`/`or`, predicates, the
  `:with` footgun, performance.
- `references/data-modeling.md` — fact-DB modeling: deep-namespace `::`
  convention, natural-key upsert, one-directional refs (free reverse), reified
  relationships, reified-tx provenance, intra-tx tempids vs lookup-refs.
- `references/datahike-internals.md` — EAV/datoms, value types, schema-as-datoms,
  history, and where to read in the fork.

Both reference files were written against the pod's `seon.db` facade; their
MODELLING content holds, but read their call syntax through this skill's
`d/`-direct forms.
