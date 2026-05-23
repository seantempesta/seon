# Advanced Querying

Loaded when agents need patterns beyond the basics in SKILL.md.

All examples use `db/query`, which wraps Datahike's `d/q` with the named database.

## Aggregates

```clojure
(db/query :myns '[:find (count ?e) (avg ?score) (max ?score)
                   :where [?e :myns/score ?score]])
;; => [[3 30.0 42.0]]
```

Built-in aggregates: `count`, `count-distinct`, `sum`, `avg`, `min`, `max`, `median`, `variance`, `stddev`, `sample`, `rand`.

## Order and Limit

Datahike's query language does not support `:order-by` or `:limit` clauses inside `:find`. Sort and slice the result set in Clojure:

```clojure
(->> (db/query :myns '[:find ?name ?score
                       :where [?e :myns/name ?name]
                              [?e :myns/score ?score]])
     (sort-by second >)
     (take 10))
```

For very large result sets, narrow the `:where` clauses first (use predicate filters or index lookups) rather than fetching everything and trimming in memory.

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

These use raw Datahike calls -- for debugging and optimization only, not normal application code. You need an actual conn, which lives inside the per-db `conn_process`; for one-off REPL debugging, get it via the relay or open a temporary direct conn in a test.

```clojure
(require '[datahike.api :as d])

;; All datoms in an index (eavt/aevt/avet). Pass a db value, not a conn.
(d/datoms @conn {:index :eavt :components [1]})        ;; all datoms for entity 1
(d/datoms @conn {:index :aevt :components [:myns/name]}) ;; all datoms by attr

;; Seek to a specific position
(d/seek-datoms @conn {:index :avet :components [:myns/score 25]})

;; Pull at the raw API
(d/pull @conn '[*] 1)
```

For history-aware lookups, wrap the db: `(d/datoms (d/history @conn) {:index :eavt :components [1]})`.

## Performance Tips

- **Batch inserts** in single `db/transact!` calls (not one entity per call).
- **Declare schema** for every attribute (already enforced by `db/transact!`).
- **Use scalar result form** (`.`) when expecting one result -- avoids wrapping in sets.
- **Prefer pull** over multiple queries when fetching related entities.
- **Avoid full-index scans** -- start `:where` clauses with the most selective constraint (a known entity id or a unique attribute value) so Datahike picks a small AEVT/AVET slice.
- **Skip history when you don't need it** -- queries against `@conn` are against the current db (no history overhead). Only use `(d/history @conn)` when you actually need retracted datoms.
