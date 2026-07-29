# Maintaining Seon's Datahike fork

Load this reference when the owner is code inside
`reference-code/datahike/`, not merely a Seon query or transaction caller.
The fork is a maintained first-party dependency: when its implementation is
wrong, fix it there and pin the behavior from Seon. The branch-roster repair
`357ffc87` and the planner repair `19f5cdd9` are the precedents; the latter is
recorded and pinned in
`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Resolution”.

## Planner entry point

The query path is:

1. `datahike.query/create-plan-via-ir` builds logical IR and lowers it
   (`reference-code/datahike/src/datahike/query.cljc:3377-3383`).
2. `get-or-create-plan` wraps that function with the plan cache
   (`reference-code/datahike/src/datahike/query.cljc:3448-3471`).
3. Lowering delegates operation ordering to
   `datahike.query.plan/order-plan-ops`
   (`reference-code/datahike/src/datahike/query/lower.cljc:1050-1061`;
   `reference-code/datahike/src/datahike/query/plan.cljc:1524-1663`).

Drive the planner directly when the plan itself is the subject:

```clojure
(require '[datahike.db :as db]
         '[datahike.query :as query])

(def planner-db (db/empty-db {}))

(#'query/create-plan-via-ir
 planner-db
 '[[?x :name ?name] [(identity 1) ?ordinal]]
 #{}
 nil
 nil)
```

`create-plan-via-ir` is private, so invoke its Var with `#'`; Seon's retained
alpha-renaming property uses this exact call shape
(`test/seon/datahike_fork_test.clj:12-49`). Calling it directly bypasses
`get-or-create-plan` and therefore the plan cache. Keep the clauses in a
vector when source order is part of the contract; the tied-plan repair
preserves that vector through greedy selection
(`reference-code/datahike/src/datahike/query/plan.cljc:1544-1599,1607-1663`).

## Cache evidence and clean measurements

Do not infer cache behavior from elapsed time. Use the maintained evidence
surface:

```clojure
(require '[datahike.query :as query])

(query/clear-query-cache!)

(binding [query/*query-result-cache?* false]
  (query/q-with-evidence
   '[:find ?value :where [_ :example/value ?value]]
   db))

(query/query-cache-metrics)
(query/query-cache-evidence)
```

- `q-with-evidence` returns result, dependency, cache, and resource evidence
  (`reference-code/datahike/src/datahike/query.cljc:129-164`).
- Binding `*query-result-cache?*` false marks the call `:uncacheable` and
  bypasses result-cache reads and writes
  (`reference-code/datahike/src/datahike/query.cljc:72-75,4576-4613`).
- `clear-query-cache!` replaces the result-cache LRU, while
  `query-cache-metrics` and `query-cache-evidence` report bounded occupancy and
  single-flight state
  (`reference-code/datahike/src/datahike/query.cljc:2505-2510,2636-2656`).
- These operations concern the **query-result cache**, not the private plan
  cache at `reference-code/datahike/src/datahike/query.cljc:2413-2418`.
  Bypass the latter with direct `create-plan-via-ir` when measuring planning.

The fork regression proves the clean result-cache protocol: clear, run with
the binding false, assert `:uncacheable` plus real resource work, and assert no
new cache bucket
(`reference-code/datahike/test/datahike/test/query_cache_test.cljc:44-82`).

## Reload and repeat the same probe

After editing a fork namespace in a JVM REPL, reload it before rerunning the
same form:

```clojure
(require 'datahike.query :reload)
(#'datahike.query/create-plan-via-ir planner-db clauses #{} nil nil)
```

Clojure's `:reload` flag forces the named lib to load again
(`reference-code/clojure/src/clj/clojure/core.clj:6149-6205`). Hold the
database value and probe form fixed so the before/after comparison isolates
the code edit. The planner repair's recorded evidence used this exact
before/reload/after shape
(`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Evidence”).

## Run both ownership gates

From the Seon root, pin the behavior Seon relies on:

```bash
bin/test seon.datahike-fork-test
```

From the Datahike submodule, run the owning namespace through its own Kaocha
task:

```bash
(cd reference-code/datahike &&
  bb kaocha --focus datahike.test.query-planner-test)
```

The command is source-derived: `bb kaocha` forwards arbitrary arguments to
`clojure -M:test -m kaocha.runner`
(`reference-code/datahike/bb.edn:46-51`;
`reference-code/datahike/bb/src/tools/test.clj:8-13`), `tests.edn` declares the
Kaocha suites (`reference-code/datahike/tests.edn:1-30`), and the namespace
comes from the test file's `ns` form
(`reference-code/datahike/test/datahike/test/query_planner_test.clj:1-9`).
Change the focus value to the owning test namespace for a different fork
mechanism. At a unit boundary, run every affected namespace focus plus the
root acceptance test; neither project can prove the other's boundary.
