---
name: datalevin
description: "Seon database patterns. Use when writing Datalog queries, transacting data, debugging empty results, working with schema/register!, db/transact!, db/query, or db/pull-by-name. Use when you see seon.db or seon.schema namespaces, or when queries return unexpected results or schema validation errors."
---

# Datalevin -- Seon Database Patterns

## Architecture

Datalevin runs as a **separate JVM process** on port 8898. It survives Seon restarts. All database access goes through the `seon.db` API, routed via core.async flow for serialized reads and writes. Schema-first: call `schema/register!`, Malli validates at transact time, and the bridge auto-derives Datalevin types.

## Quick Start

```clojure
(require '[seon.schema :as schema]
         '[seon.db :as db])

;; 1. Register attribute schemas
(schema/register! :myns/name [:string {:seon.db/identity true}])
(schema/register! :myns/score :double)

;; 2. Transact data (db-name keyword, then tx-data vector)
(db/transact! :myns [{:myns/name "alpha" :myns/score 42.0}])

;; 3. Query
(db/query :myns '[:find ?e ?n :where [?e :myns/name ?n]])
;; => #{[1 "alpha"]}

;; 4. Pull by entity id or lookup ref
(db/pull-by-name :myns '[*] [:myns/name "alpha"])
;; => {:db/id 1 :myns/name "alpha" :myns/score 42.0}
```

## Schema Registration

Register every attribute before transacting. `schema/register!` is the single source of truth.

```clojure
(schema/register! :myns/id [:string {:seon.db/identity true}])
(schema/register! :myns/label :string)
(schema/register! :myns/count :int)
(schema/register! :myns/active :boolean)
(schema/register! :myns/ratio :double)
(schema/register! :myns/uid :uuid)
(schema/register! :myns/kind :keyword)
(schema/register! :myns/sym :symbol)
(schema/register! :myns/created :inst)
(schema/register! :myns/parent :seon.db/ref)
(schema/register! :myns/tags [:vector :keyword])
(schema/register! :myns/status [:enum :active :inactive])
```

### Persistence Properties

Annotate schemas with `:seon.db/` properties (never bare `:db/` properties):

| Property | Meaning | Example |
|----------|---------|---------|
| `:seon.db/identity` | Uniquely identifies entities. Enables lookup refs and upsert. | `[:string {:seon.db/identity true}]` |
| `:seon.db/unique` | Values must be unique but this is not the identity attr. | `[:string {:seon.db/unique true}]` |

### Bridge Auto-Inference

| You write | Bridge produces |
|-----------|-----------------|
| `:string`, `:int`, `:keyword`, `:boolean`, `:double`, `:uuid`, `:symbol` | Correct `:db.type/*` |
| `:inst` | `:db.type/instant` |
| `[:enum :a :b]` | Type inferred from enum values |
| `[:vector X]` / `[:set X]` | `:db.cardinality/many` with inner type |
| Nested `[:map ...]` | `:db.type/ref` + `:db/isComponent true` |
| `:seon.db/ref` | `:db.type/ref` |
| `{:seon.db/identity true}` | `:db/unique :db.unique/identity` |
| `{:seon.db/unique true}` | `:db/unique :db.unique/value` |

### Refs

```clojure
;; Register a ref attribute
(schema/register! :myns/parent :seon.db/ref)

;; Transact with a lookup ref (resolved by Datalevin automatically)
(db/transact! :myns [{:myns/id "child-1" :myns/parent [:myns/id "parent-1"]}])
```

### Banned Types

Rejected by `validate-persisted-schemas!` at startup: `:any`, `:some`, `:nil`, `[:maybe X]`, mixed-type enums. Use `{:optional true}` instead of `[:maybe X]`.

## Database Names

Any namespace can have its own database. Pass a keyword as the first argument to all `db/` functions. Name reflects the namespace that owns the data.

| Database | Contents |
|----------|----------|
| `:seon.runtime` | Code graph, instance registry |
| `:seon.ai` | AI sessions and messages |
| `:seon.flow` | Flow traces and snapshots |
| `:seon.trading` | Trading domain data |
| `:seon.health` | Health domain data |
| `:seon.{ns}` | Any per-namespace agent context |

## Public API

All functions are in `seon.db`. Positional arguments (not map-in/map-out) -- this is the one namespace exempt from that convention.

### Write

```clojure
(db/transact! db-name tx-data)
(db/transact! db-name tx-data opts)
```

- `db-name` -- keyword (`:seon.runtime`, `:seon.ai`, etc.)
- `tx-data` -- vector of entity maps or datom tuples
- `opts` -- optional map with `:timeout-ms` (default 10000)

Validates attributes against the Malli registry, validates values, auto-adds missing Datalevin schema, then routes through the flow writer.

### Read

```clojure
(db/query db-name datalog-query & inputs)
(db/pull-by-name db-name selector eid)
(db/pull-many-by-name db-name selector eids)
(db/entity-by-name db-name eid)
```

- `db/query` -- Datalog query. Additional inputs after the query are extra sources (`:in $` is implicit for the named db).
- `db/pull-by-name` -- Pull entity by selector and eid (entity id or lookup ref).
- `db/pull-many-by-name` -- Pull multiple entities by selector and eids collection.
- `db/entity-by-name` -- Lazy map-like entity access.

## Querying

### Datalog Basics

```clojure
;; Relation (set of tuples, default)
(db/query :myns '[:find ?name ?score
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?score]])
;; => #{["alpha" 42.0]}

;; Scalar (single value)
(db/query :myns '[:find ?name .
                   :where [?e :myns/name ?name]])
;; => "alpha"

;; Collection (single column)
(db/query :myns '[:find [?name ...]
                   :where [?e :myns/name ?name]])
;; => ["alpha" "beta"]

;; Single tuple
(db/query :myns '[:find [?name ?score]
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?score]])
;; => ["alpha" 42.0]
```

### Input Parameters

```clojure
(db/query :myns '[:find ?name
                   :in $ ?min-score
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?s]
                          [(>= ?s ?min-score)]]
          30.0)
```

### Pull in Queries

```clojure
(db/query :myns '[:find (pull ?e [:myns/name :myns/score])
                   :where [?e :myns/score ?s]
                          [(> ?s 10)]])
```

### Direct Pull

```clojure
;; By entity ID
(db/pull-by-name :myns '[:myns/name :myns/score] 1)

;; By lookup ref (requires :seon.db/identity on the attr)
(db/pull-by-name :myns '[*] [:myns/name "alpha"])
```

### Order, Limit, Aggregates, Rules

See `references/querying.md` for advanced patterns.

## Common Errors and Gotchas

### "Unregistered attributes in transaction"

Register every non-system attribute with `schema/register!` before transacting.

### "Malli validation failed for :attr"

The value does not match the registered schema. Check the type -- e.g., passing a string where `:int` is expected.

### Empty Query Results

Common causes:
1. **Wrong db-name** -- querying `:seon.runtime` but data is in `:seon.ai`
2. **Attribute typo** -- Datalevin silently matches nothing on unknown attributes
3. **Type mismatch** -- querying `:myns/count 30` when stored as `30.0`
4. **Stale snapshot** -- reading before a transaction is flushed (rare in flow mode)

### Nil Values

Datalevin drops nils silently. Transacting `{:myns/name nil}` does nothing. To remove an attribute, retract explicitly:

```clojure
(db/transact! :myns [[:db/retract eid :myns/name "old-value"]])
;; Or retract all values for an attribute:
(db/transact! :myns [[:db.fn/retractAttribute eid :myns/name]])
```

### Schema Evolution

- Add new attributes any time (auto-derived on first transact).
- Cannot change value types of existing attributes.
- Adding uniqueness to an existing attribute fails if duplicates exist.

### Cardinality-Many

For `[:vector X]` / `[:set X]` attributes, transacting a new value **adds** to the set. To replace, retract first:

```clojure
(db/transact! :myns [[:db.fn/retractAttribute eid :myns/tags]
                      {:db/id eid :myns/tags :new-tag}])
```

### Schema Load Ordering

In a source file, `register!` calls must appear **before** entity schema defs that reference them. The bridge throws a clear error if a schema reference cannot be resolved.

## Testing

Bind `db/*direct-mode*` to bypass the infrastructure flow in tests:

```clojure
(binding [db/*direct-mode* true
          db/*conn-manager* fake-manager]
  (db/transact! :myns [{:myns/name "test"}])
  (db/query :myns '[:find ?n . :where [_ :myns/name ?n]]))
```

See `test/seon/test_utils.clj` for `with-temp-conn` and `with-test-datalevin` helpers. See `/clojure-testing` skill for fixtures and patterns.

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/db.clj` | Public database API (transact!, query, pull-by-name, etc.) |
| `src/seon/schema.clj` | Schema registration (register!, registered?, schema-definition) |
| `src/seon/db/schema.clj` | Malli-to-Datalevin bridge (malli-map->datalevin-schema) |
| `src/seon/db/datalevin/writer.clj` | Flow writer process (serialized writes) |
| `src/seon/db/datalevin/reader.clj` | Flow reader process (serialized reads) |
| `src/seon/db/datalevin/server.clj` | Datalevin server Integrant component |
| `src/seon/db/datalevin/conn.clj` | Connection manager (internal -- do not use directly) |
| `src/seon/graph/ingest.clj` | Code graph ingestion into Datalevin |
| `src/seon/graph/query.clj` | Code graph queries |
| `test/seon/test_utils.clj` | Test helpers (with-temp-conn, with-test-datalevin) |

## When to Read References

- `references/querying.md` -- aggregates, order/limit, rules, index lookups, performance tips
- `references/datalevin-internals.md` -- raw Datalevin API, EAV model, schema format, connection model, debugging
