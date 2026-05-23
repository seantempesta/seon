# Datahike Internals

For understanding what's underneath -- useful when debugging, reading `reference-code/datahike/`, or interpreting error messages. Normal code should use `seon.db` and `seon.schema`, not these APIs directly.

## EAV Data Model

Every fact is an `[entity-id attribute value tx added?]` datom. Entity IDs are auto-assigned positive longs. Use negative IDs or strings as tempids in transactions. Datahike stores datoms in a hitchhiker-tree backed by LMDB.

## Datahike Schema Format

In Datahike, schema is **transacted as datoms** -- entity maps with `:db/ident`, `:db/valueType`, `:db/cardinality`, and optionally `:db/unique`. The Seon bridge auto-derives these from `schema/register!` calls -- never write them manually.

```clojure
;; What the bridge transacts for you (illustrative):
[{:db/ident       :myns/name
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}
 {:db/ident       :myns/score
  :db/valueType   :db.type/double
  :db/cardinality :db.cardinality/one}
 {:db/ident       :myns/tags
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/many}
 {:db/ident       :myns/parent
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/one}]
```

Datahike requires `:db/cardinality` on every attribute. The bridge supplies `:db.cardinality/one` by default.

## Value Types

| Type | Keyword | Notes |
|------|---------|-------|
| String | `:db.type/string` | |
| Long | `:db.type/long` | Malli `:int` maps here |
| Double | `:db.type/double` | |
| Boolean | `:db.type/boolean` | |
| Instant | `:db.type/instant` | `java.util.Date` |
| UUID | `:db.type/uuid` | |
| Keyword | `:db.type/keyword` | |
| Symbol | `:db.type/symbol` | |
| Ref | `:db.type/ref` | Entity reference |
| Bytes | `:db.type/bytes` | byte arrays |

Unlike Datomic Cloud, Datahike's `:db.type/tuple` is not currently supported by the Seon bridge.

## Schema Properties

| Property | Values | Purpose |
|----------|--------|---------|
| `:db/valueType` | See above | Type of the attribute value |
| `:db/cardinality` | `:db.cardinality/one`, `:db.cardinality/many` | Single vs multi-valued (required in Datahike) |
| `:db/unique` | `:db.unique/identity`, `:db.unique/value` | Uniqueness constraint |
| `:db/isComponent` | `true` | Component entity (cascade retract) |
| `:db/index` | `true` | Indexed for AVET access (Datahike auto-indexes uniques) |

`:db.unique/identity` enables upsert (transact with same unique value updates existing entity) and lookup refs `[:attr value]`.

## How Datahike Differs from Datomic

- **Embedded**, not client-server (Seon uses the file-backed LMDB store).
- **History is configurable** via `:keep-history?` at db creation. When enabled, `(d/history db)` gives a history db and `(d/as-of db t)` gives a point-in-time snapshot. Seon enables history.
- **`:db/txInstant`** exists on every tx entity (Datahike tracks tx metadata).
- **Schema-on-write** is the default and what Seon uses (`:schema-flexibility :write`). `:read` mode exists but is not used.
- **Storage backends**: file (LMDB via konserve-lmdb), memory, JDBC, Redis. Seon uses file.
- **Indexes**: hitchhiker-tree provides EAVT, AEVT, AVET. No VAET unless `:index-all-datoms?` is set.

## Transaction Patterns

### Map Form (preferred)

```clojure
;; Create
(d/transact conn [{:myns/name "Dave" :myns/score 40.0}])

;; Upsert (with :db.unique/identity on :myns/name)
(d/transact conn [{:myns/name "Dave" :myns/score 41.0}])

;; Explicit tempid for cross-references
(d/transact conn [{:db/id -1 :myns/name "Eve"}
                  {:db/id -2 :myns/name "Frank" :myns/parent -1}])
```

### Datom Form

```clojure
(d/transact conn [[:db/add 1 :myns/score 50.0]])
(d/transact conn [[:db/retract 1 :myns/score 50.0]])
(d/transact conn [[:db.fn/retractAttribute 1 :myns/score]])
(d/transact conn [[:db.fn/retractEntity 1]])
```

### Transaction Report

```clojure
(let [report (d/transact conn [{:myns/name "Test"}])]
  (:db-before report)  ;; DB value before tx
  (:db-after report)   ;; DB value after tx
  (:tx-data report)    ;; datoms added/retracted
  (:tempids report))   ;; {-1 => 42} tempid resolution
```

In Seon, you never call `d/transact` directly -- always `db/transact!`, which validates and routes through the flow writer. The flow writer returns the same shape of report.

## Connection Model

A Datahike `conn` is an atom wrapping a DB value. Queries take a **database value** (immutable snapshot), not a connection:

```clojure
;; CORRECT
(d/q '[:find ?e :where [?e :myns/name "Alice"]] @conn)
(d/q '[:find ?e :where [?e :myns/name "Alice"]] (d/db conn))

;; WRONG -- passing connection directly
(d/q '[:find ?e :where [?e :myns/name "Alice"]] conn)
```

In Seon, connections are owned by the per-db `conn_process` and never handed out to application code. `db/query` dereferences the conn internally for each read.

## History and As-Of

```clojure
(require '[datahike.api :as d])

;; Current value
(d/q '[:find ?n :where [_ :myns/name ?n]] @conn)

;; Historical -- all values ever, including retracted
(d/q '[:find ?n :where [_ :myns/name ?n]] (d/history @conn))

;; Point-in-time snapshot
(d/q '[:find ?n :where [_ :myns/name ?n]]
     (d/as-of @conn #inst "2026-01-01"))

;; Since (only datoms added after a point)
(d/q '[:find ?n :where [_ :myns/name ?n]]
     (d/since @conn #inst "2026-05-01"))
```

History is on by default in Seon's stores (`:keep-history? true`). This means retractions remain queryable via `(d/history db)`.

## Concurrent Access

- Multiple readers concurrent on the same conn snapshot (immutable db value).
- Writes are serialized by the flow writer process -- one writer per database.
- The conn atom updates atomically on each transact.

## Store Configuration

The on-disk store lives at `data/datahike/<db-name>/`. Configuration is set when the db is first created (in `seon.db.datahike.system`) and includes:

- `:store {:backend :file :path "data/datahike/<name>"}` -- LMDB file store
- `:keep-history? true` -- retain retractions for history queries
- `:schema-flexibility :write` -- attributes must be declared before use
- `:attribute-refs? false` -- attributes are keywords, not entity refs

Changing these requires deleting the on-disk store. Schema additions are fine; schema modifications (changing value type, adding uniqueness to data that already has duplicates) will fail.

## Where to Learn More

| Topic | Document |
|-------|----------|
| Full type mapping | `docs/prds/schema-unification/design.md` |
| Migration history | `docs/seon/architecture/decisions/` (datahike migration ADR) |
| API patterns | `docs/conventions.md` |
| Datahike source | `reference-code/datahike/` (if present) or `~/.m2/repository/io/replikativ/datahike/` |
