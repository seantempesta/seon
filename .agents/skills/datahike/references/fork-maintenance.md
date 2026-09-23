---
type: reference
status: active
tags: [datahike, fork, maintenance]
---

# Maintaining Seon's Datahike fork

Load this reference when the owner is code inside
`reference-code/datahike/`, not merely a Seon query or transaction caller.
The fork is a maintained first-party dependency: when its implementation is
wrong, fix it there and pin the behavior from Seon. The branch-roster repair
`357ffc87` and the planner repair `19f5cdd9` are historical precedents; the
planner repair was recorded in an issue note deleted with the issue archive;
read it with
`git show 215447c46^:docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
(“Resolution”).

## Contents

- [Dependency ledger](#dependency-ledger)
- [Planner entry point](#planner-entry-point)
- [Cache evidence and clean measurements](#cache-evidence-and-clean-measurements)
- [Reload and repeat the same probe](#reload-and-repeat-the-same-probe)
- [Run both ownership gates](#run-both-ownership-gates)

## Dependency ledger

Verify the root gitlink and checkout before reading or editing the fork:

```bash
git ls-files -s reference-code/datahike
git -C reference-code/datahike rev-parse HEAD
```

Both must print the same commit. Read the selected revision from those
commands; this reference does not copy it, because a copied hash goes stale
at the next bump. Treat `357ffc87` and `19f5cdd9` only as repair provenance.
Anchors below name the Var and its line at the gitlink checked out on
2026-09-23; re-read the Var if the gitlink has moved.

| Mechanism | Selected-revision source | Seon acceptance |
|---|---|---|
| Planner | `create-plan-via-ir` `reference-code/datahike/src/datahike/query.cljc:3438`; `get-or-create-plan` `:3509`; the `order-plan-ops` call in `reference-code/datahike/src/datahike/query/lower.cljc:1059`; `order-plan-ops` `reference-code/datahike/src/datahike/query/plan.cljc:1524-1663` | `test/seon/datahike_fork_test.clj:16-54` |
| Result and plan caches | `*query-result-cache?*` `reference-code/datahike/src/datahike/query.cljc:71`; `q-with-evidence` `:128`; plan `query-cache` `:2412`; `clear-query-cache!` `:2519`; `query-cache-metrics` `:2647`; `query-cache-evidence` `:2656`; the result-cache bypass `:4658` | `reference-code/datahike/test/datahike/test/query_cache_test.cljc:85-119` |
| Writer, listener completion and ordered persistence | `transact!` `reference-code/datahike/src/datahike/api/impl.cljc:30-42`; the writer loop `create-thread` `reference-code/datahike/src/datahike/writer.cljc:108`, `notify-listeners!` `:379`, `transact!` `:390`; `commit!` `reference-code/datahike/src/datahike/writing.cljc:423`, `merge-writer!` `:864`, `transact!` `:876` | `file-store-executes-ordered-multi-key-operations` `test/seon/cluster/store_test.clj:124`; `a-throwing-datahike-listener-cannot-strand-a-committed-write` `test/seon/db_test.clj:1292` |
| Store create/reopen | `create-time-fixed-index-keys` `reference-code/datahike/src/datahike/connector.cljc:183`; `-connect-impl*` `:288` | `datahike-configuration` `src/seon/cluster/store.clj:152`; `open-store!` `:417`; `open-write-release-reopen-preserves-data` `test/seon/cluster/store_test.clj:137`; `create-settings-apply-only-to-fresh-stores` `:171` |
| Branch identity and roster | `connection-id` `reference-code/datahike/src/datahike/store.cljc:44`; `branches` `reference-code/datahike/src/datahike/versioning.cljc:182`, `branch!` `:212`, `delete-branch!` `:279` | `open-store!` `src/seon/cluster/store.clj:417`; `open-branch!` `:538`; `open-branch-refuses-what-the-roster-refutes` `test/seon/cluster/store_test.clj:634` |
| Schema removal | `reject-schema-removal-with-current-data` `reference-code/datahike/src/datahike/db/transaction.cljc:138`; `remove-schema` `:278` | `test/seon/schema_usage_guard_test.clj:152-483` (the schema-removal deftests) |
| Final report validation | `validate-report` `reference-code/datahike/src/datahike/db/transaction.cljc:1224-1234`, invoked at `:1295`; optional `:tx-meta :datahike/validate-report`, nil accepts, a returned value rejects before writer admission | `missing-reference-diagnostics-describe-the-declared-value` `test/seon/db_test.clj:1510`; verification boundary in `docs/prds/steward-platform/research/write-admission-2026-09-17.md` |
| Test launchers | `reference-code/datahike/bb.edn:46-51`; `reference-code/datahike/bb/src/tools/test.clj:8-13`; `reference-code/datahike/tests.edn:1-30` | `bin/test`; `test/seon/datahike_fork_test.clj:1-54` |

## Planner entry point

The query path is:

1. `datahike.query/create-plan-via-ir` builds logical IR and lowers it
   (`reference-code/datahike/src/datahike/query.cljc:3438`).
2. `get-or-create-plan` wraps that function with the plan cache
   (`reference-code/datahike/src/datahike/query.cljc:3509`).
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
(`test/seon/datahike_fork_test.clj:16-54`). Calling it directly bypasses
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
  (`reference-code/datahike/src/datahike/query.cljc:128-163`).
- Binding `*query-result-cache?*` false marks the call `:uncacheable` and
  bypasses result-cache reads and writes
  (`reference-code/datahike/src/datahike/query.cljc:71-74`, `:4658`).
- `clear-query-cache!` replaces the result-cache LRU, while
  `query-cache-metrics` and `query-cache-evidence` report bounded occupancy and
  single-flight state
  (`reference-code/datahike/src/datahike/query.cljc:2519`, `:2647`, `:2656`).
- These operations concern the **query-result cache**, not the private plan
  cache at `reference-code/datahike/src/datahike/query.cljc:2412`.
  Bypass the latter with direct `create-plan-via-ir` when measuring planning.

The fork regression proves the clean result-cache protocol: clear, run with
the binding false, assert `:uncacheable` plus real resource work, and assert no
new cache bucket
(`reference-code/datahike/test/datahike/test/query_cache_test.cljc:85-119`).

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
(`git show 215447c46^:docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`,
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
