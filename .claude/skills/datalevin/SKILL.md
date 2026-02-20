---
name: datalevin
description: "Datalevin database patterns. Use when writing Datalog queries, transacting data, debugging empty results, working with d/q or d/transact!, accessing database data, managing connections, or working with Datalevin schema. Use when you see datalevin.core, seon.db.datalevin namespace, or when queries return unexpected results."
---

# Datalevin -- Fast Embedded Datalog Database

Datalevin is a Datomic-compatible Datalog query engine built on LMDB. It stores EAV datoms (entity-attribute-value). Unlike Datomic, it has **no temporal dimension** -- when data is deleted, it is gone. ACID transactions via LMDB.

Seon runs Datalevin in **client/server mode**: a Datalevin server process runs inside the JVM, and connections use `dtlv://` URIs. Each agent namespace gets its own isolated database.

## Quick Start

```clojure
(require '[datalevin.core :as d])

;; --- Local (embedded) ---
(def conn (d/get-conn "/tmp/my-db" schema))

;; --- Remote (Seon's pattern) ---
(def conn (d/get-conn "dtlv://datalevin:datalevin@127.0.0.1:8898/mydb" schema))

;; Transact entities (map form)
(d/transact! conn [{:name "Alice" :age 30}
                    {:name "Bob" :age 25}])

;; Query (Datalog)
(d/q '[:find ?name ?age
        :where [?e :name ?name]
               [?e :age ?age]
               [(> ?age 26)]]
     (d/db conn))
;; => #{["Alice" 30]}

;; Close
(d/close conn)
```

## Data Model

### EAV Datoms

Every fact is an `[entity-id attribute value]` triple. Entity IDs are auto-assigned positive longs. Use negative IDs or strings as tempids in transactions.

### Schema

Schema is **optional** (schema-on-write). Unschemaed attributes are stored as EDN blobs. Define schema when you need:

- **Value types** for range queries and indexing
- **Cardinality many** for multi-valued attributes
- **Unique identity** for lookup refs and upserts
- **Refs** for entity-to-entity relationships

```clojure
(def schema
  {:name {:db/valueType :db.type/string
          :db/unique    :db.unique/identity}  ; enables [:name "Alice"] lookups
   :age  {:db/valueType :db.type/long}        ; enables range queries
   :tags {:db/cardinality :db.cardinality/many
          :db/valueType :db.type/string}
   :friend {:db/valueType :db.type/ref}})     ; references another entity
```

### Value Types

| Type | Keyword | Notes |
|------|---------|-------|
| String | `:db.type/string` | |
| Long | `:db.type/long` | |
| Double | `:db.type/double` | |
| Boolean | `:db.type/boolean` | |
| Instant | `:db.type/instant` | java.util.Date or java.time.Instant |
| UUID | `:db.type/uuid` | |
| Keyword | `:db.type/keyword` | |
| Symbol | `:db.type/symbol` | |
| Ref | `:db.type/ref` | Entity reference |
| Bytes | `:db.type/bytes` | byte arrays |
| (none) | EDN blob | Any Clojure data, not indexed for range queries |

### Schema Properties

| Property | Values | Purpose |
|----------|--------|---------|
| `:db/valueType` | See above | Type of the attribute value |
| `:db/cardinality` | `:db.cardinality/one` (default), `:db.cardinality/many` | Single vs multi-valued |
| `:db/unique` | `:db.unique/identity`, `:db.unique/value` | Uniqueness constraint |
| `:db/isComponent` | `true` | Component entity (cascade delete) |
| `:db/tupleAttrs` | `[:a :b :c]` | Composite tuple |

**`:db.unique/identity`** -- enables upsert (transact with same unique value updates existing entity) and lookup refs `[:attr value]`.

**`:db.unique/value`** -- uniqueness without upsert; rejects duplicates.

### Differences from Datomic

- **No history/as-of** -- deletions are permanent
- **No squuid** -- use `(java.util.UUID/randomUUID)` or your own ID scheme
- **Schema is a map of maps** (not transacted, passed at connection time)
- **Use `update-schema`** to evolve schema on an open connection
- **Two indexes**: `:eav` and `:ave` (not `:avet`, `:vaet` like Datomic)
- **No tx entity** -- no `:db/txInstant` by default (use `:auto-entity-time?` option for `:db/created-at` and `:db/updated-at`)

## Query Patterns

### Datalog Basics

```clojure
;; Find relation (returns set of tuples)
(d/q '[:find ?name ?age
        :where [?e :name ?name]
               [?e :age ?age]]
     (d/db conn))

;; With input parameter
(d/q '[:find ?name
        :in $ ?min-age
        :where [?e :name ?name]
               [?e :age ?a]
               [(>= ?a ?min-age)]]
     (d/db conn) 30)

;; Scalar result (single value)
(d/q '[:find ?name .
        :where [?e :name ?name]
               [?e :age 30]]
     (d/db conn))
;; => "Alice"

;; Collection result (single column)
(d/q '[:find [?name ...]
        :where [?e :name ?name]]
     (d/db conn))
;; => ["Alice" "Bob" "Carol"]

;; Single tuple
(d/q '[:find [?name ?age]
        :where [?e :name ?name]
               [?e :age ?age]
               [(> ?age 30)]]
     (d/db conn))
;; => ["Carol" 35]
```

### Aggregates

```clojure
(d/q '[:find (count ?e) (avg ?age) (max ?age)
        :where [?e :age ?age]]
     (d/db conn))
;; => [[3 30.0 35]]
```

### Order and Limit

```clojure
;; Datalevin extension (not in standard Datalog)
(d/q '[:find ?name ?age
        :where [?e :name ?name]
               [?e :age ?age]
        :order-by [?age :desc]
        :limit 10]
     (d/db conn))
```

### Pull in Queries

```clojure
(d/q '[:find (pull ?e [:name :age :tags])
        :where [?e :age ?a]
               [(> ?a 25)]]
     (d/db conn))
;; => [[{:name "Alice" :age 30 :tags ["dev" "clj"]}]
;;     [{:name "Carol" :age 35 :tags ["dev" "py"]}]]
```

### Pull API (Direct)

```clojure
;; By entity ID
(d/pull (d/db conn) '[:name :age :tags] 1)

;; By lookup ref (requires :db.unique/identity)
(d/pull (d/db conn) '[:name :age :tags] [:name "Alice"])

;; Wildcard
(d/pull (d/db conn) '[*] [:name "Alice"])

;; Nested refs
(d/pull (d/db conn) '[:name {:friend [:name :age]}] 1)

;; Pull many
(d/pull-many (d/db conn) '[:name :age] [1 2 3])
```

### Entity API

```clojure
;; Lazy map-like access
(def alice (d/entity (d/db conn) [:name "Alice"]))
(:age alice)  ;; => 30
(:tags alice) ;; => #{"dev" "clj"}

;; Force all attributes
(d/touch alice)
;; => {:db/id 1 :name "Alice" :age 30 :tags ["dev" "clj"]}

;; Reverse refs (who references this entity?)
(:_friend alice) ;; => [{:db/id 2} ...]
```

### Rules

```clojure
(def rules
  '[[(older-than ?e ?age)
     [?e :age ?a]
     [(> ?a ?age)]]])

(d/q '[:find ?name
        :in $ % ?min
        :where [?e :name ?name]
               (older-than ?e ?min)]
     (d/db conn) rules 30)
```

### Index Lookups (Low-Level)

```clojure
;; All datoms for entity 1
(d/datoms (d/db conn) :eav 1)

;; All entities with :name = "Alice"
(d/datoms (d/db conn) :ave :name "Alice")

;; Count datoms matching pattern (nil = wildcard)
(d/count-datoms (d/db conn) nil :name nil)  ;; count of :name datoms

;; Range query
(d/index-range (d/db conn) :age 25 35)
```

## Transactions

### Map Form (Preferred)

```clojure
;; Create (tempid auto-assigned)
(d/transact! conn [{:name "Dave" :age 40}])

;; Upsert (with :db.unique/identity on :name)
(d/transact! conn [{:name "Alice" :age 31}])  ;; updates existing Alice

;; Explicit tempid for cross-references
(d/transact! conn [{:db/id -1 :name "Eve" :age 28}
                    {:db/id -2 :name "Frank" :friend -1}])

;; Nested refs
(d/transact! conn [{:name "Grace"
                     :friend {:db/id -1 :name "Heidi"}}])
```

### Datom Form

```clojure
;; Add single fact
(d/transact! conn [[:db/add 1 :name "Updated"]])

;; Retract single fact
(d/transact! conn [[:db/retract 1 :name "Updated"]])

;; Retract attribute (all values)
(d/transact! conn [[:db.fn/retractAttribute 1 :name]])

;; Retract entire entity
(d/transact! conn [[:db.fn/retractEntity 1]])
```

### Transaction Report

```clojure
(let [report (d/transact! conn [{:name "Test" :age 99}])]
  (:db-before report)  ;; DB value before tx
  (:db-after report)   ;; DB value after tx
  (:tx-data report)    ;; datoms added/retracted
  (:tempids report))   ;; {-1 => 42} tempid resolution
```

### Async Transactions (High Throughput)

```clojure
;; Returns a future, batches automatically
(d/transact-async conn [{:name "Fast" :age 1}])

;; Block on the last one to ensure all committed
(d/transact! conn [{:name "Last" :age 2}])
```

### With-Transaction (Multi-Step Atomic)

```clojure
(d/with-transaction [cn conn]
  (let [result (d/q '[:find ?e . :where [?e :name "Alice"]] (d/db cn))]
    (d/transact! cn [{:db/id result :age 32}])
    ;; Can call (d/abort-transact (d/db cn)) to rollback
    ))
```

## Connection Management (Seon)

Seon uses a **client/server** architecture. The server runs in-process, agents connect as clients.

### Key Files

| File | Purpose |
|------|---------|
| `src/seon/db/datalevin/server.clj` | Datalevin server Integrant component |
| `src/seon/db/datalevin/conn.clj` | Connection manager with TTL caching |
| `src/seon/ai/datalevin.clj` | AI session/message persistence |
| `src/seon/ctx.clj` | Unified context with Datalevin persistence |
| `src/seon/render.clj` | Renderer resolution cache (Datalevin-backed) |
| `src/seon/graph/ingest.clj` | Code index (scanner data into Datalevin) |
| `src/seon/graph/query.clj` | Code index queries |

### Getting Connections

```clojure
(require '[seon.db.datalevin.conn :as conn])

;; From Integrant system
(def mgr (:seon/connection-manager integrant.repl.state/system))

;; Master database (orchestrator data)
(def master-conn (conn/get-master-conn! {::conn/manager mgr}))

;; Namespace database (agent-isolated)
(def trading-conn (conn/get-namespace-conn! {::conn/manager mgr
                                              ::conn/namespace 'seon.trading}))

;; With schema
(def typed-conn (conn/get-namespace-conn! {::conn/manager mgr
                                            ::conn/namespace 'seon.trading
                                            ::conn/schema my-schema}))

;; Stats
(conn/connection-stats {::conn/manager mgr})
;; => {::conn/total-connections 3
;;     ::conn/namespaces (:seon.trading :seon.health)
;;     ::conn/master-connected? true}
```

### Database Naming

- Master: `seon` (for cross-namespace data)
- Per-namespace: `seon.{namespace}` (e.g., `seon.trading`, `seon.health`)
- URIs: `dtlv://datalevin:datalevin@127.0.0.1:8898/seon.trading`

### Connection Lifecycle

- Connections are cached with TTL (default 5 min)
- Expired connections are cleaned up by a background scheduler
- On system shutdown, all connections are closed
- Connections survive `(reset)` via Integrant suspend/resume

## Seon Usage Patterns

### Schemaless by Default

Seon mostly uses Datalevin in schemaless mode. Attributes without schema definitions are stored as EDN blobs. This is fine for most use cases -- define schema only when you need unique identity, cardinality many, refs, or range queries.

```clojure
;; This works without schema -- :role stored as EDN blob
(d/transact! conn [{:name "Alice" :role "admin" :metadata {:level 5}}])
```

### Namespaced Keys

Seon uses fully-qualified namespaced keys throughout:

```clojure
(d/transact! conn [{:seon.ai/session-id "ses-abc"
                     :seon.ai/status :running
                     :seon.ai/created-at (java.time.Instant/now)}])

(d/q '[:find ?id ?status
        :where [?e :seon.ai/session-id ?id]
               [?e :seon.ai/status ?status]]
     (d/db conn))
```

### Logical IDs via Unique Identity

When entities need stable lookup IDs (not integer entity IDs):

```clojure
(def schema {:seon.ai/session-id {:db/valueType :db.type/string
                                    :db/unique :db.unique/identity}})

;; Upsert by session-id
(d/transact! conn [{:seon.ai/session-id "ses-abc"
                     :seon.ai/status :completed}])

;; Lookup by session-id
(d/pull (d/db conn) '[*] [:seon.ai/session-id "ses-abc"])
```

## Performance Profile

Based on LMDB characteristics and Datalevin benchmarks:

### Write Throughput

| Scale | Sync (default) | Async (`transact-async`) |
|-------|----------------|--------------------------|
| 1K entities | ~50-200ms | ~5-20ms |
| 10K entities | ~500-2000ms | ~50-200ms |
| 100K entities | ~5-20s | ~500ms-2s |

Sync writes flush to disk on every transaction. Use `transact-async` for bulk loads. Manual batching compounds with auto-batching for even higher throughput.

### Read Performance

- **Point lookups** (entity/pull by ID): sub-millisecond
- **Simple queries** (few clauses, small result): 1-5ms
- **Scan queries** (age > X over 10K): 5-50ms
- **Complex joins** over 100K: 50-500ms (cost-based optimizer helps)

### Memory/Disk

- LMDB memory-maps the database file -- read performance scales with OS page cache
- Disk usage: ~2-5x raw data size (due to B+ tree overhead and indexes)
- Two indexes by default: `:eav` and `:ave`

### Optimization Tips

- Batch inserts in single `transact!` call (not one entity per call)
- Use `transact-async` for write-heavy workloads
- Define schema with `:db/valueType` for attributes used in range queries
- Use `d/count-datoms` instead of `(count (d/q ...))` when you only need counts
- `(d/datalog-index-cache-limit db 0)` when bulk loading to save memory
- Use `:nometasync` env flag for 5x write speedup (last tx may be lost on crash but DB stays intact)

## Gotchas

### Nil Values

Datalevin does not store nil values. Transacting `{:name "Alice" :age nil}` silently drops the `:age` attribute. To "delete" an attribute, use `[:db/retract eid :attr value]`.

### Schema Evolution

- You can **add** new schema attributes any time via `d/update-schema`
- You **cannot change** value types of existing attributes
- Adding `:db/unique` to an existing attribute may fail if duplicates exist

### Connection = Atom Wrapping DB

A connection (`conn`) is an atom. To get the current database value, deref it: `@conn` or `(d/db conn)`. Queries take a **database value** (immutable snapshot), not a connection.

```clojure
;; CORRECT
(d/q '[:find ?e :where [?e :name "Alice"]] (d/db conn))
(d/q '[:find ?e :where [?e :name "Alice"]] @conn)

;; WRONG -- passing connection directly
(d/q '[:find ?e :where [?e :name "Alice"]] conn)
```

### Cardinality Many Behavior

For `:db.cardinality/many` attributes, transacting a new value **adds** to the set (does not replace). To replace all values, retract first:

```clojure
;; Adds "rust" to existing tags, doesn't replace
(d/transact! conn [{:db/id 1 :tags "rust"}])

;; To replace: retract attribute then add
(d/transact! conn [[:db.fn/retractAttribute 1 :tags]
                    [:db/add 1 :tags "only-this"]])
```

### Empty Query Results

Common causes:
1. **Wrong DB value** -- passing `conn` instead of `(d/db conn)` or `@conn`
2. **Attribute typo** -- Datalevin is schemaless, so typos silently match nothing
3. **Type mismatch** -- querying `:age 30` when it was stored as `30.0`
4. **Stale snapshot** -- using a DB value from before the transaction

### Server Mode Specifics

- Default credentials: `datalevin:datalevin` (change in production)
- Server port: 8898 (Seon default)
- Database names are auto-kebab-cased by the server
- Each client connection is lightweight but should be reused (connection manager handles this)

### Large Values

EDN blobs (untyped attributes) can store arbitrary Clojure data, including large maps and vectors. However, very large values (>1MB) may impact LMDB page usage. For large documents, consider splitting into multiple attributes or entities.

### Concurrent Access

- Multiple readers are fully concurrent (MVCC via LMDB)
- Writes are serialized (one writer at a time)
- Last write wins for concurrent writes to the same attribute
- Read transactions see a consistent snapshot from when the read started
