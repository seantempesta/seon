---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty or unexpected results, designing a schema, or working with seon.db and seon.schema. Use for schema/register!, db/db, db/transact!, db/query, db/pull, db/entity, db/index-page, installed-schema, lookup refs, refs, components, identity, upsert, retract, cardinality many, CAS fences, temporal database values, or listen!."
---

# Datahike — Seon Database Patterns

Use `seon.db` for every application database operation. Use
`seon.schema/register!` as the source of truth for attribute shapes; never
hand-write Datahike schema.

This skill owns database-specific behavior: the asynchronous CLJS facade,
ordinary database values, Datalog/query/pull/transact semantics, refs,
components, identity, provenance, discovery, temporal reads, index access, and
transaction interests.

> Hand-offs: use **`data-oriented-clojure`** for the general data-oriented
> mindset and errors-as-values; **`data-modeling`** to design a domain schema;
> **`clojurescript`** for `^:async`, `await`, and self-host evaluation; and
> **`clojure-testing`** for test fixtures and fresh databases.

## Runtime boundary: ordinary database values, asynchronous operations

The JVM database server owns Datahike connections, immutable database values,
indexes, caches, query execution, serialization, and durable writes. A CLJS
process has one multiplexed transport session and ordinary request/response
data. It does **not** have a local Datahike connection or indexes.

Every database operation that crosses this boundary is asynchronous:
`db/db`, `query`, `pull`, `pull-many`, `entity`, `installed-schema`,
`index-page`, `transact!`, `listen!`, and `unlisten!`. At the agent REPL top
level the evaluator awaits the returned Promise. Inside an `^:async` function,
write `await` explicitly.

`(await (db/db))` returns the latest immutable database value known to the
session. It is a small ordinary map identifying a Datahike database state, not
the database contents and not a connection. The facade caches the newest value
for each database name and advances it from successful transactions and server
events.

Capture one value when related reads must observe the same state:

```clojure
(defn ^:async titles-at-one-db-value []
  (let [database (await (db/db))]
    {:my.example/titles
     (await
      (db/query {:seon.db/db database
                 :seon.db/query
                 '[:find [?title ...]
                   :where [?entity :my.example/title ?title]]}))
     :my.example/count
     (await
      (db/query {:seon.db/db database
                 :seon.db/query
                 '[:find (count ?entity) .
                   :where [?entity :my.example/id]]}))}))
```

Omit `:seon.db/db` when the operation should use the current database. Pass a
captured value when snapshot consistency matters. Do not dereference, bind, or
thread a connection.

For another named database, acquire its current value with:

```clojure
(await (db/db {:seon.db/database-name "experiment"}))
```

Pass that value to operations and call `(await (db/release database))` when
the process no longer needs the named database acquisition.

## Entities are attributes and connections

An entity has no type, class, or kind. It is an id plus datoms. What it is
comes from the attributes it carries and the refs that connect it. Schema
belongs to attributes; an entity may carry attributes from several domains.

- Find a set by attribute presence: `[?entity :my.source/id]`.
- Identify one with a `:db.unique/identity` attribute. Its lookup ref also
  supports upsert.
- Relate entities with ref attributes.
- Make a ref a component only when the child belongs exclusively to the
  parent and should retract with it.
- Express domain ownership with a domain ref such as `:my.todo/owner`.
- Derive provenance by joining transaction metadata; do not copy it onto every
  domain entity.

If you reach for a `:type` or `:kind` attribute, first ask which attribute or
connection actually defines the set.

## Quick start

Schemas live in the namespace whose attributes they describe. In namespace
`my.source`, `::id` reads as `:my.source/id`.

```clojure
(require '[seon.db :as db]
         '[seon.schema :as schema])

(schema/register! ::id    [:string {:seon.db/identity true}])
(schema/register! ::title :string)
(schema/register! ::rank  :int)

(defn ^:async save-source []
  (let [report
        (await
         (db/transact!
          [{::id "s1" ::title "Alpha" ::rank 1}]))]
    (if (:seon.error/message report)
      report
      (await (db/pull (:db-after report) '[*] [::id "s1"])))))
```

Successful `transact!` returns ordinary transaction-report data:
`:db-before`, `:db-after`, `:tx-data`, `:tempids`, and `:tx-meta`. Failure
returns a `:seon.error/*` map. There is no separate success-envelope API.

The supported transaction call shapes are:

```clojure
(await (db/transact! tx-data))
(await (db/transact! database tx-data))
(await (db/transact! {:seon.db/tx-data tx-data
                      :seon.db/db database
                      :seon.db/tx-meta tx-meta}))
```

The request-map keys are fully namespaced. `:seon.db/db` and
`:seon.db/tx-meta` are optional.

## Schema: register once, derive Datahike schema

`schema/register!` records the Malli declaration. The bridge derives
`:db/valueType`, `:db/cardinality`, `:db/unique`, and `:db/isComponent`. Never
write `:db.type/*` schema maps in application code.

```clojure
(schema/register! ::name   :string)
(schema/register! ::count  :int)
(schema/register! ::active :boolean)
(schema/register! ::ratio  :double)
(schema/register! ::when   :inst)
(schema/register! ::uid    :uuid)
(schema/register! ::status [:enum :open :done])
(schema/register! ::tags   [:vector :keyword])
(schema/register! ::parent :seon.db/ref)
(schema/register! ::children
                  [:vector {:seon.db/component true} :seon.db/ref])
```

| Declaration | Derived Datahike behavior |
|---|---|
| `[:string {:seon.db/identity true}]` | string, cardinality one, unique identity |
| `[:enum :open :done]` | keyword, cardinality one |
| `[:vector :seon.db/ref]` | ref, cardinality many |
| `[:vector {:seon.db/component true} :seon.db/ref]` | component ref, cardinality many |
| `:inst` | instant, cardinality one |
| `:seon.db/ref` | ref, cardinality one |

Use `(await (db/installed-schema database))` to inspect schema installed in a
particular database. Use `(db/malli->datahike-schema attr-keys)` to inspect the
schema derived from registered declarations.

Optional attribute means absent, never stored nil. Do not use `:any`, `:some`,
`:nil`, or `[:maybe x]` for Seon-authored database data unless the boundary is
genuinely polymorphic.

## Transactions: ordinary data and atomic assertions

```clojure
;; Upsert by identity. Omitted keys remain unchanged.
(await (db/transact! [{::id "s1" ::title "Alpha v2"}]))

;; Retract one value or the whole entity explicitly.
(await (db/transact! [[:db/retract [::id "s1"] ::title]]))
(await (db/transact! [[:db.fn/retractEntity [::id "s1"]]]))

;; Cardinality-many adds values. Replace by retracting then adding in one tx.
(await (db/transact! [[:db/retract [::id "s1"] ::tags]
                      {::id "s1" ::tags [:lisp :db]}]))

;; Link newly created entities in one transaction with a shared tempid.
(await (db/transact! [{:db/id "person" ::person-id "alice"}
                      {::id "s2" ::author "person"}]))
```

`(db/cas-assert ref attr value)` produces
`[:db.fn/cas ref attr value value]`: an in-transaction assertion that the fact
still has that value. Put it first in a transaction that must commit only if
the observed fact has not changed. The sole writer evaluates it atomically;
failure returns an error value.

When an entire database value must remain current, pass it as
`:seon.db/expected-db` in the transaction request. Prefer a fact-level CAS when
only one fact carries the authority because unrelated writes should not abort
the transaction.

## Queries preserve Datomic/Datahike semantics

`db/query` runs on the JVM and returns ordinary result data. Datalog query
syntax and `:find` result shapes remain Datahike's:

```clojure
(await (db/query '[:find ?name ?rank
                   :where [?entity ::name ?name]
                          [?entity ::rank ?rank]]))

(await (db/query '[:find ?name .
                   :where [?entity ::name ?name]]))

(await (db/query '[:find [?name ...]
                   :where [?entity ::name ?name]]))

(await (db/query '[:find [?name ?rank]
                   :where [?entity ::name ?name]
                          [?entity ::rank ?rank]]))
```

For `:in` values, positional arguments preserve the query's declared source
positions. Pass an explicit database value where `$` appears:

```clojure
(await
 (db/query '[:find [?name ...]
             :in $ ?minimum
             :where [?entity ::rank ?rank]
                    [(>= ?rank ?minimum)]
                    [?entity ::name ?name]]
           database
           5))
```

The request-map form separates the selected database from ordinary inputs:

```clojure
(await
 (db/query {:seon.db/db database
            :seon.db/query
            '[:find [?name ...]
              :in $ ?minimum
              :where [?entity ::rank ?rank]
                     [(>= ?rank ?minimum)]
                     [?entity ::name ?name]]
            :seon.db/args [5]}))
```

Ref attributes store entity ids. Join through the target when matching its
attributes:

```clojure
(await
 (db/query '[:find (count ?function) .
             :where [?function :seon.fn/ns ?namespace]
                    [?namespace :seon.ns/name :seon.db]]))
```

Lookup-ref values must have the stored type. For a string identity attribute,
use `[:seon.fn/sym "seon.db/query"]`, not a symbol.

Use resource bounds in map requests when work or response size is not already
structurally bounded: `:seon.db/max-work`, `:seon.db/max-results`, and
`:seon.db/max-result-weight`.

## Pull, entity, and index access

`pull` follows refs and expresses the returned shape. `entity` is shorthand
for pulling `[*]`. Both return eager ordinary data, not a remote or lazy
Datahike entity object.

```clojure
(await (db/pull '[*] [::id "s1"]))
(await (db/pull database '[::title {::author [::person-id]}] [::id "s1"]))
(await (db/entity database [::id "s1"]))
(await (db/pull-many database '[*] [[::id "s1"] [::id "s2"]]))
```

Use `index-page` for bounded raw datom access in native Datahike index order.
It supports `:eavt`, `:aevt`, and `:avet`, an optional component prefix, and a
cursor for the next page.

```clojure
(await
 (db/index-page
  database
  {:seon.db/index :avet
   :seon.db/components [::rank]
   :seon.db/direction :forward
   :seon.db/limit 50}))
```

The result contains `:datahike.index-page/datoms`,
`:datahike.index-page/complete?`, and, when more data remains,
`:datahike.index-page/cursor`. Do not try to access JVM indexes directly from
CLJS.

## Temporal database values

`as-of`, `since`, and `history` are pure transformations of an ordinary
database value. Pass their result to an asynchronous read:

```clojure
(let [database (await (db/db))
      old-db (db/as-of database transaction-id)
      changes-db (db/since database transaction-id)
      history-db (db/history database)]
  {:my.example/old
   (await (db/query {:seon.db/db old-db
                     :seon.db/query '[:find ?value :where [?e ::name ?value]]}))
   :my.example/changes
   (await (db/query {:seon.db/db changes-db
                     :seon.db/query '[:find ?e :where [?e ::status :done]]}))
   :my.example/history
   (await (db/query {:seon.db/db history-db
                     :seon.db/query
                     '[:find ?value ?tx ?added
                       :where [?e ::name ?value ?tx ?added]]}))})
```

The database value contains the database name, transaction basis, commit
identity, and temporal selection. It is sufficient for the JVM to rehydrate
the intended immutable Datahike value; it does not contain all datoms.

## Transaction interests

`listen!` registers session-owned interest with the JVM. Without a selector it
listens to all datoms; use a Datalog query or datom patterns to make delivery
selective.

```clojure
(defn on-source-transaction [report-or-event]
  ;; Handle ordinary transaction data or an explicit resynchronization event.
  report-or-event)

(defn ^:async watch-sources []
  (let [database (await (db/db))]
    (await
     (db/listen!
      {:seon.db/db database
       :seon.db/key ::sources
       :seon.db/query
       '[:find ?source :where [?source :my.source/id]]
       :seon.db/handler on-source-transaction}))))

(await (db/unlisten! ::sources))
```

A matching transaction invokes the handler with ordinary transaction-report
keys: `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and `:tx-meta`.
Resynchronization and database-advanced notifications are explicit event maps
that include the new `:db-after`. Handlers may return Promises; rejected or
thrown handlers are logged and do not crash the transport session.

Treat the delivered `:db-after` as the immutable post-transaction value. Run
any follow-up query against that value instead of asking for an unrelated
latest value.

## Transaction metadata and provenance

Every datom's transaction id points to a transaction entity. Seon persists two
ordinary provenance refs in transaction metadata:

- `:seon.db/user` — the human, root agent, or task agent responsible; and
- `:seon.db/process` — the stable process that accepted the transaction.

Join through the transaction to derive who, which process, and when:

```clojure
(await
 (db/query '[:find ?user ?process-id ?instant
             :where [?entity :my.source/id "s1"]
                    [?entity :my.source/title _ ?tx]
                    [?tx :seon.db/user ?user]
                    [?tx :seon.db/process ?process]
                    [?process :seon.db.process/id ?process-id]
                    [?tx :db/txInstant ?instant]]))
```

Do not add `created-by`, `created-at`, `updated-at`, or source-turn projections
to domain entities when the transaction already contains that information.

## Discovery and common failures

Inspect installed attributes before inventing another shape:

```clojure
(defn ^:async attributes-in [attribute-namespace]
  (let [database (await (db/db))
        installed (await (db/installed-schema database))]
    (->> (keys installed)
         (filter keyword?)
         (filter #(= attribute-namespace (namespace %)))
         sort)))
```

- Empty query results usually mean a misspelled attribute, a stored-value type
  mismatch, or a missing ref join.
- Nil is not a stored value. Retract an attribute to clear it.
- Adding schema attributes is supported; changing an installed attribute's
  value type is not. Adding uniqueness also fails if duplicates exist.
- A `:seon.error/message` result means the asynchronous operation failed. Pass
  it back as data or handle it explicitly; do not assume a successful eval
  means a successful database operation.
- Never call `datahike.api` outside `src/seon/db/`. The JVM server owns the
  native API and resources.

## Key source

| File | Purpose |
|---|---|
| `src/seon/db.cljs` | Agent-facing asynchronous facade and exact call shapes |
| `src/seon/db/protocol.cljc` | Ordinary request, response, database-value, index, and listener contracts |
| `src/seon/db/server.clj` | JVM authority and native Datahike execution |
| `src/seon/schema.cljc` | Schema declarations and registration |
| `src/seon/db/internal.cljs` | Malli-to-Datahike bridge and transaction validation |
| `reference-code/datahike/` | Maintained Datahike source; read it rather than guessing semantics |

The local references retain deeper Datalog, modeling, and Datahike-internal
background. When an older example differs from `src/seon/db.cljs`, keep its
Datahike semantics and translate the invocation to the asynchronous `seon.db`
facade documented here.
