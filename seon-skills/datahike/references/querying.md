# Advanced Querying

Loaded when you need Datalog patterns beyond the SKILL.md basics. All examples
use `db/query` (alias `db` = `seon.db`), which wraps datahike's `d/q` with the
db auto-injected from `*conn*` — you omit the db; `:in` inputs come AFTER the
query. Reads are synchronous.

## Aggregates

```clojure
(db/query '[:find (count ?e) (avg ?score) (max ?score)
            :where [?e ::score ?score]])
;; => [[3 30.0 42.0]]
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
(db/query '[:find (sum ?r) . :where [?e ::rating ?r]])
;; RIGHT — :with ?e keeps each entity's contribution
(db/query '[:find (sum ?r) . :with ?e :where [?e ::rating ?r]])
```

(Live example: `my.kb/source-stats` in `src/my/kb.cljs`.)

## Order and Limit

Datahike's query language has no `:order-by` / `:limit` inside `:find`. Sort and
slice in Clojure after the query:

```clojure
(->> (db/query '[:find ?name ?score
                 :where [?e ::name ?name] [?e ::score ?score]])
     (sort-by second >)
     (take 10))
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

(db/query '[:find [?name ...] :in $ %
            :where (high-scorer ?e ?name)]
          rules)
```

`seon.agent.todo/rules` (`src/seon/agent/todo.cljs`) is the live exemplar: a
recursive `descendant` closure, plus `leaf`/`open-work`/`blocked`/`ready`
derived entirely from two refs — the whole work-queue is pure Datalog over the
tree/DAG, nothing precomputed. Note its comment: negations (`leaf`, `not
blocked`) only FILTER already-bound tuples, so bind the entity positively
BEFORE invoking them.

## Predicate and binding expressions

```clojure
;; predicate — filter with a Clojure fn inside [( )]:
(db/query '[:find [?name ...]
            :where [?e ::name ?name] [?e ::score ?s]
                   [(> ?s 50)] [(clojure.string/starts-with? ?name "A")]])

;; binding-expr — bind a computed value, then use/filter on it:
(db/query '[:find ?name ?doubled
            :where [?e ::name ?name] [?e ::score ?s] [(* ?s 2) ?doubled]])
```

## Not and Or

```clojure
(db/query '[:find [?name ...]
            :where [?e ::name ?name] (not [?e ::status :inactive])])

(db/query '[:find [?name ...]
            :where [?e ::name ?name]
                   (or [?e ::status :active] [?e ::status :pending])])
```

Use `or-join` / `not-join` when the branches bind different variables and you
must declare which vars unify with the outer query.

## Nested pull in queries

```clojure
;; pull refs inline; a sub-pattern expands the ref'd entity:
(db/query '[:find (pull ?e [::name {::parent [::name]}]) :where [?e ::name _]])
;; => [[{::name "child" ::parent {::name "parent"}}]]

;; wildcard everything for matched entities:
(db/query '[:find (pull ?e [*]) :where [?e ::id _]])
```

A PLAIN ref with no sub-pattern pulls back as `{:db/id N}` — name it with a
sub-pattern (`{::parent [::name]}`) to pull its fields. A COMPONENT ref expands
to a nested map under `[*]`. Reverse-ref navigation (`::parent` →
`{:my.ns/_parent [...]}`) is free — see `references/data-modeling.md`.

## Inspecting the index (debugging only)

You almost never need raw datoms — `query`/`pull`/`entity` + `store-inventory`/
`installed-schema` cover normal work. When you genuinely must see the raw EAV
shape while debugging, the guarded surface is `seon.db`, not `datahike.api`
directly (the one-API rule). `installed-schema` answers "what attrs exist on
this db" without faulting data in:

```clojure
;; every attr the conn has installed (filter keyword? — also keyed by attr-eid):
(filter keyword? (keys (db/installed-schema @db/*conn*)))

;; "does this attr have any data?" — count, don't list:
(db/query '[:find (count ?e) . :where [?e :my.kb.source/id]])
```

If a datahike primitive you need (e.g. raw `d/datoms`/`d/seek-datoms` index
walks) isn't surfaced in `seon.db`, the right move is to ADD the wrapper there —
that keeps the single-API rule. Do not reach around it into `datahike.api`.

## Performance tips

- **Batch inserts** in one `db/transact!` call, not one entity per call.
- **Most selective clause first** — pin the entity by a known eid or a unique
  attr value so datahike picks a small AEVT/AVET slice, not a full-index scan.
- **Scalar form (`.`)** when expecting one result — skips the set wrapper.
- **Prefer pull** over N follow-up queries when fetching related entities.
- **Thread one db value** through a unit of work instead of re-deref'ing
  `@*conn*` per leaf fn — in the pod each deref reconstitutes a fresh value
  (`datahike-primer.md` §1). It's a correctness AND a perf win.
- **Don't `memoize` on a db value** — `=` on a DB walks the whole EAVT index,
  faulting every node in off the store on a cache HIT (`datahike-primer.md` §5).
  Measure before caching; `:memory`/local reads are sub-ms on small datom counts.
- **Skip history unless you need retractions** — current-db queries carry no
  history overhead; only reach for `db/history` when you want retracted datoms.
