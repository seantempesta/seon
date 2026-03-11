# Datalevin API Patterns - Research Notes

Date: 2026-01-28
Version: v0.10.3 (local from git submodule)

## Setup Verification

Datalevin v0.10.3 loaded successfully from `reference-code/datalevin` git submodule.

### Dependencies

Added to `deps.edn` under `:dev` alias:

```clojure
;; Datalevin - local from git submodule (v0.10.3, 2026-01-27)
datalevin/datalevin {:local/root "reference-code/datalevin"}
```

### Important Setup Notes

1. **Dependency prep required**: Datalevin has Java code that needs compilation. Run:

   ```bash
   clj -X:deps prep
   ```

2. **Server restart required**: After adding the dependency, restart the JVM to pick up the new classpath.

3. **Compiled classes location**: `reference-code/datalevin/target/classes/`

## Core API Patterns

### Require

```clojure
(require '[datalevin.core :as d])
```

### Connection Management

```clojure
;; Create/open connection with schema
(def conn (d/get-conn "/path/to/db" schema))

;; Get current database value (immutable snapshot)
(def db (d/db conn))

;; Close connection (IMPORTANT: always close for LMDB)
(d/close conn)
```

**Key difference from XTDB:** Datalevin uses persistent connections with mutable transaction semantics. The `conn` is an atom containing the current database state. `(d/db conn)` returns an immutable snapshot.

### Schema Definition

```clojure
(def schema
  {;; String with unique identity (for lookup refs)
   :user/email    {:db/valueType :db.type/string
                   :db/unique    :db.unique/identity}

   ;; Standard value types
   :user/name     {:db/valueType :db.type/string}
   :user/age      {:db/valueType :db.type/long}
   :user/active   {:db/valueType :db.type/boolean}
   :user/score    {:db/valueType :db.type/double}
   :user/created  {:db/valueType :db.type/instant}
   :user/id       {:db/valueType :db.type/uuid}
   :user/data     {:db/valueType :db.type/bytes}

   ;; Multi-valued attribute
   :user/tags     {:db/valueType :db.type/keyword
                   :db/cardinality :db.cardinality/many}

   ;; Reference to another entity
   :user/friend   {:db/valueType :db.type/ref}

   ;; Multi-valued reference
   :user/friends  {:db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/many}

   ;; Component entity (owned, auto-deleted with parent)
   :user/profile  {:db/valueType :db.type/ref
                   :db/isComponent true}
   :profile/bio   {:db/valueType :db.type/string}

   ;; Full-text searchable
   :doc/content   {:db/valueType :db.type/string
                   :db/fulltext  true
                   :db.fulltext/autoDomain true}

   ;; Tuples
   :point/coords  {:db/valueType :db.type/tuple
                   :db/tupleTypes [:db.type/double :db.type/double]}})
```

**Schema is optional** but recommended. Without schema, Datalevin infers types. With schema, you get validation and special features (refs, components, fulltext).

### Transactions

```clojure
;; Add entities (maps with attributes)
(d/transact! conn
  [{:user/email "alice@example.com"
    :user/name "Alice"
    :user/age 30}])

;; Upsert by unique identity
(d/transact! conn
  [{:user/email "alice@example.com"  ; matches existing
    :user/age 31}])                  ; updates age

;; Explicit entity ID
(d/transact! conn
  [{:db/id 123
    :user/name "Bob"}])

;; Temporary IDs for references
(d/transact! conn
  [{:db/id "seon"
    :user/email "alice@example.com"}
   {:db/id "bob"
    :user/email "bob@example.com"
    :user/friend "seon"}])  ; resolves to Alice's entity ID

;; Nested component entities
(d/transact! conn
  [{:user/email "alice@example.com"
    :user/profile {:profile/bio "Developer"}}])

;; Retract attribute
(d/transact! conn
  [[:db/retract [:user/email "alice@example.com"] :user/age 31]])

;; Retract entire entity
(d/transact! conn
  [[:db/retractEntity [:user/email "alice@example.com"]]])

;; Transaction result
;; => {:db-before ... :db-after ... :tx-data [...datoms...] :tempids {...}}
```

**Transaction result keys:**

- `:db-before` - Database value before transaction
- `:db-after` - Database value after transaction
- `:tx-data` - Vector of datoms created
- `:tempids` - Map of temp-id to actual entity ID
- `:tx-meta` - Transaction metadata

### Datalog Queries

```clojure
;; Basic query
(d/q '[:find ?name ?age
       :where
       [?e :user/name ?name]
       [?e :user/age ?age]]
     db)
;; => #{["Alice" 30] ["Bob" 25]}

;; With inputs
(d/q '[:find ?name
       :in $ ?min-age
       :where
       [?e :user/name ?name]
       [?e :user/age ?age]
       [(> ?age ?min-age)]]
     db 25)
;; => #{["Alice"]}

;; Aggregates
(d/q '[:find (count ?e) (avg ?age) (max ?age)
       :where [?e :user/age ?age]]
     db)
;; => [[3 30.0 35]]

;; With pull expression
(d/q '[:find [(pull ?e [:user/name :user/email]) ...]
       :where [?e :user/active true]]
     db)
;; => [#:user{:name "Alice" :email "alice@example.com"} ...]

;; Rules
(def rules '[[(adult? ?e)
              [?e :user/age ?a]
              [(>= ?a 18)]]])

(d/q '[:find ?name
       :in $ %
       :where
       [?e :user/name ?name]
       (adult? ?e)]
     db rules)
```

**Key difference from XTDB:** XTDB v2 uses SQL. Datalevin uses Datomic-style Datalog.

### Pull API

```clojure
;; Pull all attributes
(d/pull db '[*] [:user/email "alice@example.com"])
;; => {:db/id 1 :user/name "Alice" :user/email "alice@example.com" ...}

;; Selective attributes
(d/pull db '[:user/name :user/age] [:user/email "alice@example.com"])
;; => #:user{:name "Alice" :age 30}

;; Nested/component attributes
(d/pull db '[:user/name {:user/profile [:profile/bio]}] eid)
;; => #:user{:name "Alice" :profile #:profile{:bio "Developer"}}

;; With default values
(d/pull db '[:user/name (:user/nickname :default "N/A")] eid)

;; Pull many entities
(d/pull-many db '[:user/name :user/age]
             [[:user/email "alice@example.com"]
              [:user/email "bob@example.com"]])
```

### Entity API

```clojure
(def alice (d/entity db [:user/email "alice@example.com"]))

;; Lazy attribute access
(:user/name alice)     ;; => "Alice"
(:user/age alice)      ;; => 30

;; Navigate refs
(-> alice :user/friend :user/name)

;; Reverse navigation (who references this entity?)
(:post/_author alice)  ;; => #{post1 post2 ...}

;; Touch to eagerly load all attributes
(d/touch alice)
;; => #:user{:name "Alice" :age 30 :email "alice@example.com" ...}

;; Convert to plain map
(into {} alice)
```

### Database as Value / Speculative Transactions

```clojure
;; Databases are immutable values
(def db-before (d/db conn))
(d/transact! conn [...])
(def db-after (d/db conn))

;; db-before still has old data
(:user/age (d/entity db-before [:user/email "alice@example.com"]))

;; Speculative transaction (doesn't affect conn)
(def what-if-db (d/db-with (d/db conn)
                  [{:user/email "temp@example.com" :user/name "Temp"}]))

;; what-if-db has the new entity, conn's db does not
```

### History

```clojure
;; Get history database
(def history-db (d/history (d/db conn)))

;; Query all historical values
(d/q '[:find ?age ?tx
       :where
       [?e :user/email "alice@example.com"]
       [?e :user/age ?age ?tx]]
     history-db)
;; => #{[30 1] [31 2] [35 3]} - all ages Alice ever had
```

**Key difference from XTDB:** XTDB has bitemporal history (valid-time + tx-time). Datalevin has single-dimension history (tx-time only).

### Index Access

```clojure
;; Available indexes: :eav, :ave, :vea
;; (NOT :avet, :aevt like Datomic)

;; All datoms in :eav order
(d/datoms db :eav)

;; Datoms for specific attribute
(d/datoms db :ave :user/name)

;; Seek to starting point
(d/seek-datoms db :ave :user/name "B")
;; => datoms with name >= "B"

;; Reverse seek
(d/rseek-datoms db :ave :user/name "C")
;; => datoms with name < "C" in reverse order
```

## Full-Text Search

```clojure
;; Schema with fulltext
(def schema
  {:doc/title   {:db/valueType :db.type/string
                 :db/fulltext  true
                 :db.fulltext/autoDomain true}
   :doc/content {:db/valueType :db.type/string
                 :db/fulltext  true}})

;; Connect with search options
(def conn (d/get-conn "path" schema
            {:search-domains {"doc/title" {:index-position? true}}}))

;; Fulltext search in Datalog
;; IMPORTANT: Returns [[?e ?a ?v] ...] tuples (3 elements, not 4!)
(d/q '[:find ?title
       :in $ ?search
       :where
       [(fulltext $ ?search) [[?e ?a ?v]]]
       [?e :doc/title ?title]]
     (d/db conn) "clojure database")

;; Search specific attribute
(d/q '[:find ?title
       :in $ ?search
       :where
       [(fulltext $ :doc/content ?search {:top 5}) [[?e ?a ?v]]]
       [?e :doc/title ?title]]
     (d/db conn) "embedded")
```

**Search expression syntax:**

- Simple: `"word1 word2"` (AND by default)
- Boolean: `[:or "foo" [:and "bar" [:not "baz"]]]`
- Phrase: `{:phrase "exact phrase"}`

## Key-Value Store (LMDB Direct)

```clojure
;; Open KV store
(def lmdb (d/open-kv "path/to/kv"))

;; Create sub-databases (DBIs)
(d/open-dbi lmdb "users")
(d/open-dbi lmdb "sessions")

;; Write
(d/transact-kv lmdb
  [[:put "users" "user:1" {:name "Alice" :role :admin}]
   [:put "users" "user:2" {:name "Bob"}]
   [:put "sessions" "sess:abc" {:user-id 1}]])

;; Read
(d/get-value lmdb "users" "user:1")
;; => {:name "Alice" :role :admin}

;; Range queries
(d/get-range lmdb "users" [:all])
;; => [["user:1" {...}] ["user:2" {...}]]

(d/get-range lmdb "users" [:at-least "user:2"])
;; => [["user:2" {...}]]

;; Close
(d/close-kv lmdb)
```

## Transaction Listeners

```clojure
(def tx-log (atom []))

(d/listen! conn :my-listener
  (fn [{:keys [tx-data db-before db-after]}]
    (swap! tx-log conj {:count (count tx-data)})))

(d/transact! conn [...])
;; listener fires asynchronously

(d/unlisten! conn :my-listener)
```

## Key Differences from XTDB v2

| Feature | XTDB v2 | Datalevin |
|---------|---------|-----------|
| Query Language | SQL | Datalog |
| Schema | Optional column types | Optional attribute schema |
| Temporal | Bitemporal (valid + tx time) | Single timeline (tx time) |
| Transaction | `submit-tx` (async) | `transact!` (sync) |
| Connection | Node with databases | Single database connection |
| Storage | Arrow/Parquet | LMDB |
| Full-text | Not built-in | Built-in search engine |
| KV access | No | Yes (LMDB directly) |
| Memory | Higher (columnar) | Lower (key-value) |
| Index names | `:avet`, `:aevt` | `:eav`, `:ave`, `:vea` |

## Migration Considerations for Seon

1. **Query translation**: All XTDB SQL queries need rewriting to Datalog
2. **Schema design**: Define explicit schemas for better performance and validation
3. **Temporal queries**: Only tx-time available; if valid-time is needed, model explicitly
4. **Multi-database**: Datalevin doesn't have XTDB's database-per-namespace; use separate connections
5. **Performance**: Lower memory footprint; faster for simple lookups; slower for complex aggregations
6. **Search**: Built-in full-text search could replace external solution

## Code Patterns for Seon

### Repository Pattern

```clojure
(ns seon.db.datalevin
  (:require [datalevin.core :as d]))

(defn get-conn [dir schema]
  (d/get-conn dir schema))

(defn transact! [conn tx-data]
  (d/transact! conn tx-data))

(defn q [query db & inputs]
  (apply d/q query db inputs))

(defn pull [db pattern eid]
  (d/pull db pattern eid))

(defn entity [db eid]
  (d/entity db eid))

(defn close [conn]
  (d/close conn))
```

### With Integrant

```clojure
(defmethod ig/init-key :seon/datalevin [_ {:keys [path schema]}]
  (d/get-conn path schema))

(defmethod ig/halt-key! :seon/datalevin [_ conn]
  (d/close conn))
```

## Test Results

All core API tests passed:

- Connection creation and closing ✓
- Schema with all value types ✓
- Transactions (add, update, retract) ✓
- Datalog queries with filters, inputs, aggregates ✓
- Pull API with nesting and defaults ✓
- Entity API with lazy loading ✓
- References and reverse navigation ✓
- Component entities ✓
- Full-text search ✓
- Key-value store ✓
- Transaction listeners ✓
- History queries ✓
- Speculative transactions ✓
- Index access (:eav, :ave) ✓

## Test Files

Test scripts created during research (in `tmp/`):

- `datalevin-test.clj` - Basic CRUD operations
- `datalevin-fixed.clj` - Full-text search, refs, components
- `datalevin-final.clj` - Comprehensive API coverage
- `datalevin-indexes.clj` - Index access patterns

## Next Steps

1. Define schemas for Seon entities (sessions, messages, agents)
2. Create connection lifecycle with Integrant
3. Implement repository layer with Datalevin backend
4. Add migration utilities for existing XTDB data
