# Datalevin Internals

For understanding what's underneath -- useful when debugging, reading `reference-code/datalevin/`, or interpreting error messages. Normal code should use `seon.db` and `seon.schema`, not these APIs directly.

## EAV Data Model

Every fact is an `[entity-id attribute value]` triple (datom). Entity IDs are auto-assigned positive longs. Use negative IDs or strings as tempids in transactions.

## Datalevin Schema Format

Schema is a map of attribute names to property maps. In Seon, this is auto-derived from `schema/register!` -- never written manually.

```clojure
;; What the bridge generates (you don't write this):
{:myns/name {:db/valueType :db.type/string
             :db/unique    :db.unique/identity}
 :myns/score {:db/valueType :db.type/double}
 :myns/tags {:db/cardinality :db.cardinality/many
             :db/valueType :db.type/keyword}
 :myns/parent {:db/valueType :db.type/ref}}
```

## Value Types

| Type | Keyword | Notes |
|------|---------|-------|
| String | `:db.type/string` | |
| Long | `:db.type/long` | Malli `:int` maps here |
| Double | `:db.type/double` | |
| Boolean | `:db.type/boolean` | |
| Instant | `:db.type/instant` | `java.util.Date` in Datalevin |
| UUID | `:db.type/uuid` | |
| Keyword | `:db.type/keyword` | |
| Symbol | `:db.type/symbol` | |
| Ref | `:db.type/ref` | Entity reference |
| Bytes | `:db.type/bytes` | byte arrays |

Unschemaed attributes are stored as EDN blobs (not indexed for range queries).

## Schema Properties

| Property | Values | Purpose |
|----------|--------|---------|
| `:db/valueType` | See above | Type of the attribute value |
| `:db/cardinality` | `:db.cardinality/one` (default), `:db.cardinality/many` | Single vs multi-valued |
| `:db/unique` | `:db.unique/identity`, `:db.unique/value` | Uniqueness constraint |
| `:db/isComponent` | `true` | Component entity (cascade delete) |
| `:db/tupleAttrs` | `[:a :b :c]` | Composite tuple |

`:db.unique/identity` enables upsert (transact with same unique value updates existing entity) and lookup refs `[:attr value]`.

## Differences from Datomic

- **No history/as-of** -- deletions are permanent
- **No squuid** -- use `(java.util.UUID/randomUUID)` or your own ID scheme
- **Schema is a map of maps** (not transacted, passed at connection time or via `d/update-schema`)
- **Two indexes**: `:eav` and `:ave` (not `:avet`, `:vaet` like Datomic)
- **No tx entity** -- no `:db/txInstant` by default (use `:auto-entity-time?` for `:db/created-at` and `:db/updated-at`)

## Transaction Patterns

### Map Form (preferred)

```clojure
;; Create
(d/transact! conn [{:myns/name "Dave" :myns/score 40.0}])

;; Upsert (with :db.unique/identity on :myns/name)
(d/transact! conn [{:myns/name "Dave" :myns/score 41.0}])

;; Explicit tempid for cross-references
(d/transact! conn [{:db/id -1 :myns/name "Eve"}
                    {:db/id -2 :myns/name "Frank" :myns/parent -1}])
```

### Datom Form

```clojure
(d/transact! conn [[:db/add 1 :myns/score 50.0]])
(d/transact! conn [[:db/retract 1 :myns/score 50.0]])
(d/transact! conn [[:db.fn/retractAttribute 1 :myns/score]])
(d/transact! conn [[:db.fn/retractEntity 1]])
```

### Transaction Report

```clojure
(let [report (d/transact! conn [{:myns/name "Test"}])]
  (:db-before report)  ;; DB value before tx
  (:db-after report)   ;; DB value after tx
  (:tx-data report)    ;; datoms added/retracted
  (:tempids report))   ;; {-1 => 42} tempid resolution
```

### Async Transactions

```clojure
(d/transact-async conn [{:myns/name "Fast"}])  ;; returns future, batches automatically
(d/transact! conn [{:myns/name "Last"}])         ;; block on last to ensure committed
```

### With-Transaction (multi-step atomic)

```clojure
(d/with-transaction [cn conn]
  (let [result (d/q '[:find ?e . :where [?e :myns/name "Alice"]] (d/db cn))]
    (d/transact! cn [{:db/id result :myns/score 32.0}])))
```

## Connection Model

A connection (`conn`) is an atom wrapping a DB value. Queries take a **database value** (immutable snapshot), not a connection:

```clojure
;; CORRECT
(d/q '[:find ?e :where [?e :myns/name "Alice"]] (d/db conn))
(d/q '[:find ?e :where [?e :myns/name "Alice"]] @conn)

;; WRONG -- passing connection directly
(d/q '[:find ?e :where [?e :myns/name "Alice"]] conn)
```

In Seon client/server mode, connections use `dtlv://` URIs. The connection manager handles this -- agents never create connections directly.

## Concurrent Access

- Multiple readers are fully concurrent (MVCC via LMDB)
- Writes are serialized (one writer at a time -- Seon's flow writer ensures this)
- Read transactions see a consistent snapshot from when the read started

## Client/Server Specifics

- Default credentials: `datalevin:datalevin`
- Server port: 8898
- Database names are auto-kebab-cased by the server
- Each client connection is lightweight but should be reused (connection manager handles this)

## Where to Learn More

| Topic | Document |
|-------|----------|
| Full type mapping | `docs/prds/schema-unification/design.md` |
| Serialization (Nippy) | `docs/prds/schema-unification/research/serialization-findings.md` |
| Nil semantics | `docs/prds/schema-unification/research/nil-semantics-findings.md` |
| API patterns | `CONVENTIONS.md` |
| Datalevin source | `reference-code/datalevin/` |
