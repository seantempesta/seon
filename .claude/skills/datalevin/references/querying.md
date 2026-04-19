# Advanced Querying

Loaded when agents need patterns beyond the basics in SKILL.md.

All examples use `db/query` which wraps Datalevin's `d/q` with the named database.

## Aggregates

```clojure
(db/query :myns '[:find (count ?e) (avg ?score) (max ?score)
                   :where [?e :myns/score ?score]])
;; => [[3 30.0 42.0]]
```

Built-in aggregates: `count`, `count-distinct`, `sum`, `avg`, `min`, `max`, `median`, `variance`, `stddev`, `sample`, `rand`.

## Order and Limit

Datalevin extension (not standard Datalog):

```clojure
(db/query :myns '[:find ?name ?score
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?score]
                   :order-by [?score :desc]
                   :limit 10])
```

`:order-by` takes `[var :asc|:desc]` pairs. `:limit` and `:offset` work as expected.

## Rules

Define reusable query logic:

```clojure
(def rules
  '[[(high-scorer ?e ?name)
     [?e :myns/name ?name]
     [?e :myns/score ?s]
     [(> ?s 90)]]])

(db/query :myns '[:find ?name
                   :in $ %
                   :where (high-scorer ?e ?name)]
          rules)
```

Rules are passed as the `%` input. Multiple rule definitions with the same name create logical OR.

## Predicate Expressions

Filter with Clojure functions inside `[( )]`:

```clojure
(db/query :myns '[:find ?name
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?s]
                          [(> ?s 50)]
                          [(clojure.string/starts-with? ?name "A")]])
```

## Binding Expressions

Bind computed values:

```clojure
(db/query :myns '[:find ?name ?doubled
                   :where [?e :myns/name ?name]
                          [?e :myns/score ?s]
                          [(* ?s 2) ?doubled]])
```

## Not and Or Clauses

```clojure
;; NOT: exclude matches
(db/query :myns '[:find ?name
                   :where [?e :myns/name ?name]
                          (not [?e :myns/status :inactive])])

;; OR: match either pattern
(db/query :myns '[:find ?name
                   :where [?e :myns/name ?name]
                          (or [?e :myns/status :active]
                              [?e :myns/status :pending])])
```

## Nested Pull in Queries

Pull refs and nested entities:

```clojure
(db/query :myns '[:find (pull ?e [:myns/name {:myns/parent [:myns/name]}])
                   :where [?e :myns/name _]])
;; => [[{:myns/name "child" :myns/parent {:myns/name "parent"}}]]
```

Wildcard pull: `(pull ?e [*])` returns all attributes.

## Index Lookups (Debugging)

These use raw Datalevin calls -- for debugging and optimization only, not normal application code. Require `datalevin.core` directly.

```clojure
(require '[datalevin.core :as d])

;; All datoms for entity 1 (requires raw conn, not db-name)
(d/datoms (d/db conn) :eav 1)

;; Count datoms matching pattern (nil = wildcard)
(d/count-datoms (d/db conn) nil :myns/name nil)

;; Range query on typed attribute
(d/index-range (d/db conn) :myns/score 25 50)
```

Use `d/count-datoms` instead of `(count (d/q ...))` when only counts are needed.

## Performance Tips

- **Batch inserts** in single `db/transact!` calls (not one entity per call)
- **Define schema** for attributes used in range queries (auto-derived from `schema/register!`)
- **Use scalar result form** (`.`) when expecting one result -- avoids wrapping in sets
- **Prefer pull** over multiple queries when fetching related entities
- Datalevin has two indexes: `:eav` and `:ave` (not four like Datomic)
