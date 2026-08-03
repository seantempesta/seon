# Advanced Querying

Loaded when you need Datalog patterns beyond the SKILL.md basics. Fresh Seon
uses co-located `datahike.api` directly; every example passes one explicit
immutable database value to `d/q`. Reads are synchronous.

## Contents

- [Aggregates](#aggregates)
- [Order and limit](#order-and-limit)
- [Rules](#rules)
- [Predicate and binding expressions](#predicate-and-binding-expressions)
- [Not and or](#not-and-or)
- [Nested pull in queries](#nested-pull-in-queries)
- [Inspecting the index](#inspecting-the-index-debugging-only)
- [Performance tips](#performance-tips)

## Aggregates

```clojure
(d/q '[:find (count ?e) (avg ?score) (max ?score)
       :where [?e ::score ?score]]
     db)
[[3 30.0 42.0]]
```

Built-ins: `count`, `count-distinct`, `sum`, `avg`, `min`, `max`, `median`,
`variance`, `stddev`, `sample`, `rand`.

### The `:with` footgun (silent undercount)

An aggregate runs over the **deduplicated projected tuples**. `(sum ?r)` over
two entities both rated `5` collapses to ONE `5` — because the projected tuple
`[5]` appears once. Add `:with ?e` to keep each entity's row distinct so
repeated values still count:

```clojure
;; WRONG — two sources rated 5 sum to 5, not 10
(d/q '[:find (sum ?r) . :where [?e ::rating ?r]] db)
;; RIGHT — :with ?e keeps each entity's contribution
(d/q '[:find (sum ?r) . :with ?e :where [?e ::rating ?r]] db)
```

The aggregate rule survives independently of the deleted knowledge-base
example: use `:with` whenever duplicate projected values must remain distinct.

## Order and Limit

Seon's maintained Datahike accepts a query map with `:query`, `:args`,
`:order-by`, `:offset`, and `:limit`
(`reference-code/datahike/src/datahike/query.cljc:98-121,3475-3560`):

```clojure
(d/q {:query '[:find ?name ?score
               :in $
               :where [?e ::name ?name] [?e ::score ?score]]
      :args [db]
      :order-by '[?score :desc]
      :limit 10})
```

For large result sets, narrow the `:where` clauses first (start from a known
entity id or a unique-attr value) rather than fetching everything and trimming.

## Rules

Reusable query logic, passed as the `%` input. Multiple defs with the same head
are logical OR. Rules can recurse (transitive closures), which Datalog patterns
alone cannot express.

```clojure
(def rules
  '[[(high-scorer ?e ?name)
     [?e ::name ?name] [?e ::score ?s] [(> ?s 90)]]])

(d/q '[:find [?name ...] :in $ %
       :where (high-scorer ?e ?name)]
     db rules)
```

The lasting lesson from the deleted recursive-work example is that negations
only filter already-bound tuples, so bind the entity positively before
invoking them. Do not restore its facade or domain.

## Predicate and binding expressions

```clojure
;; predicate — filter with a Clojure fn inside [( )]:
(d/q '[:find [?name ...]
       :where [?e ::name ?name] [?e ::score ?s]
              [(> ?s 50)] [(clojure.string/starts-with? ?name "A")]]
     db)

;; binding-expr — bind a computed value, then use/filter on it:
(d/q '[:find ?name ?doubled
       :where [?e ::name ?name] [?e ::score ?s] [(* ?s 2) ?doubled]]
     db)
```

## Not and Or

```clojure
(d/q '[:find [?name ...]
       :where [?e ::name ?name] (not [?e ::status :inactive])]
     db)

(d/q '[:find [?name ...]
       :where [?e ::name ?name]
              (or [?e ::status :active] [?e ::status :pending])]
     db)
```

Use `or-join` / `not-join` when the branches bind different variables and you
must declare which vars unify with the outer query.

## Nested pull in queries

```clojure
;; pull refs inline; a sub-pattern expands the ref'd entity:
(d/q '[:find (pull ?e [::name {::parent [::name]}])
       :where [?e ::name _]]
     db)
[[{::name "child" ::parent {::name "parent"}}]]

;; wildcard everything for matched entities:
(d/q '[:find (pull ?e [*]) :where [?e ::id _]] db)
```

A PLAIN ref with no sub-pattern pulls back as `{:db/id N}` — name it with a
sub-pattern (`{::parent [::name]}`) to pull its fields. A COMPONENT ref expands
to a nested map under `[*]`. Pull also supports reverse-ref navigation
(`::parent` → `{:my.ns/_parent [...]}`)
(`reference-code/datahike/src/datahike/pull_api.cljc:276-319`;
`reference-code/datahike/test/datahike/test/pull_api_test.cljc:250-277`).

## Inspecting the index (debugging only)

You almost never need raw datoms — `d/q`, `d/pull`, `d/entity`, and the
database value's installed schema cover normal work. Inspect the schema
without faulting data in:

```clojure
;; every installed attr (filter keyword? — the map is also keyed by attr-eid):
(filter keyword? (keys (:schema db)))

;; "does this attr have any data?" — count, don't list:
(d/q '[:find (count ?e) . :where [?e :my.kb.source/id]] db)
```

Use `d/datoms` or `d/seek-datoms` only for a measured index-level debugging or
implementation need. Do not recreate the retired `seon.db` pod facade.

## Performance tips

- **Batch inserts** in one `d/transact` call, not one entity per call.
- **Give the planner selective facts** — constrain entities with known eids or
  unique attribute values. The maintained planner, not caller clause order,
  orders operations and chooses index scans
  (`reference-code/datahike/src/datahike/query/plan.cljc:1524-1663`).
- **Scalar form (`.`)** when expecting one result — skips the set wrapper.
- **Prefer pull** over N follow-up queries when fetching related entities.
- **Thread one database value** through a unit of work instead of repeatedly
  dereferencing a connection. It is a correctness and performance win.
- **Don't `memoize` on a db value** — `=` on a DB compares the EAVT index and
  can fault index nodes from durable storage on a cache hit
  (`reference-code/datahike/src/datahike/db.cljc:703-715`;
  `docs/prds/archive/agent-fsm/research/datahike-primer.md` §5).
  Measure before caching.
- **Use `d/history` only when historical additions and retractions are the
  query subject.**
