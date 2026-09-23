---
type: evidence
status: fork 684d3290 landed and pushed; gitlink bumped; default JVM restart and a seon.db carried-projection follow-up owed
---
# Lane datahike-query-scope (schedule #24ad), 2026-09-23

Owner: `reference-code/datahike` (the maintained fork), the superproject gitlink and
this note. There are no Seon `src/` edits. The subject is audit item 9 in
`docs/research/agent-platform/cache-invalidation-audit-2026-09-23.md` (eb903978b).

## The seams (parent `0c01b5fe`)

- `datahike.query/db-cache-key` (`query.cljc:2646`) keys a raw DB by
  `db/committed-cache-identity` (`db.cljc:400`), which is
  `[connection-id generation commit-id]`. An earlier numeric as-of view appends its
  time point. `connection-id` is `[store-id branch]` (`store.cljc:44-55`), and the
  generation is per connect (`connector.cljc:376-382`). So each branch and each
  connection generation is its own LRU key, even at the same commit.
- The result LRU is `query-result-cache` (`query.cljc:2488`), a
  `lru/weighted-lru` of `*query-cache-size*` 64 buckets and
  `*query-cache-weight-limit*` 1,000,000 shallow weight (`query.cljc:2444-2463`).
- Lifetime: `open-query-cache-generation!` (`:2523`) admits a scope.
  `close-query-cache-generation!` (`:2592`) fences puts and evicts every bucket of the
  scope. `result-cache-put!` (`:3015`) requires the scope admitted. Single-flight
  keys flights by the same scoped key, and `single-flight/close-scope!`
  (`single_flight.cljc:512`) fails them on release.
- Promotion: `inheritable-entry` / `source-context-unchanged?` (`query.cljc:2951-2988`)
  copies an older bucket's entry into the current bucket when the attribute
  revisions its plan reads are equal. `compatible-source-keys?` restricted this to
  the same `[connection-id generation]`, through the key.
- A commit id names one stored value in its store. Commits are stored in konserve
  under their id (`writing.cljc:340-347`), and `create-commit-id` is at
  `writing.cljc:361`.
- Speculative values have `:committed? false` and no commit id
  (`db.cljc:444`, fork 0c01b5fe), so `committed-value-identity` returns nil and they
  never cache.

## Premise check: what evicted default's entries

This was a read-only probe of default's live cache in JVM mode (`read_only`, no
redefinition). The form walked `(.-state (:lru @datahike.query/query-result-cache))`.

- 33 buckets, all on default's connection `[224560ae… :cluster-default]`, holding
  724 entries.
- The counted weight was 641,128 against the 1,000,000 limit. The head bucket
  weighed 20,383 (28 entries). The older-commit buckets weighed 18k-98k each.
- There were **200 distinct result objects (identity), weighing 191,220**. The
  budget counts each result once per bucket that holds it: promotion copies
  forward, and so does every connection at the same commit. So the counted weight
  is 3.35 times the retained weight.

**Answer.** The LRU size is not the cause: the retained working set is 19% of the
bound. The interference comes from keying: every branch or fixture connection at a
commit stores a duplicate bucket of results that already exist, and each copy is
charged again against the one shared weight budget. That pushes default's recent
buckets out. Keying committed values by commit makes a same-commit branch add 0
buckets (bench below). The bound stays as declared: 64 buckets and 1,000,000
weight, documented in the cache header comment.

The fix does not address one remaining over-count: promotion copies within one
lineage, a per-bucket charge for a shared result. See "Left" below.

## Fix (fork commit `684d3290`, pushed to `origin/main`)

- The result key is `database-result-key` = `[store-id commit-id]`, plus the as-of
  time point. It is derived from the scoped key, so eligibility is unchanged. A
  composite key maps each member.
- A bucket is now `{:owners #{scoped-key} :entries {cache-key entry}}`, and
  `bucket-weight` sums its entries.
- The scoped key still owns lifetime:
  - a put and a promotion require the scope admitted;
  - an admitted exact hit adds its scope to `:owners`;
  - single-flight stays per scope;
  - `close-query-cache-generation!` removes the scope from every bucket through the
    new `lru/weighted-update-where`, which preserves recency, and evicts a bucket
    only when no owner is left.
  - `:completed-snapshots-evicted` counts only buckets that were actually dropped.
- Promotion by revisions stays within one connection generation.
  `source-context-unchanged?` now compares the contexts' `connection-id` and
  `generation`. Reason: a connection opens with no attribute revisions
  (`connector.cljc:377`), so equal empty revisions across two lineages would not
  prove equal datoms.
- Hit path: owner membership is checked before admission, and the key is built
  without rest-destructuring.
- There is a CHANGELOG entry under 0.8 Features.

## Proof

The fork tests use the lane's 28-namespace set. One JVM per tree ran sequentially,
from a socket REPL on an ephemeral port that I started myself. The parent is a
`git archive 0c01b5fe` snapshot with `target/` linked.

| tree | tests | pass | fail | error |
|---|---|---|---|---|
| fork `684d3290` (final, fresh JVM) | 234 | 1425 | 4 | 1 |
| parent `0c01b5fe` (fresh JVM) | 233 | 1420 | 4 | 0 |

The residual reds pre-date this change:

- the three `config-mismatch` assertions (`connector_release_test:239,260`,
  `query_cache_test` config-mismatch);
- `optimistic-test/tx-report-happy-path-converges-to-conn:310`, which is flaky: it
  was green in my earlier fork run and red on the parent;
- `api-test/test-metrics-hht`, a load-order-dependent error with no hitchhiker
  `-flush` implementation. It is recorded on both trees in
  `lane-datahike-fncall-revisions-2026-09-23.md`, and here it was red in both fork
  JVMs and green in the parent JVM. The query cache is not involved.

The new tests:

- **`sibling-branches-at-one-commit-share-one-result-bucket`**
  - The sibling reads as a `hit`, and one key `[store-id head]` remains.
  - The owners are both scopes.
  - A speculative `with` value is `uncacheable` and adds no key.
  - A write gives the writer its own new key.
  - Releasing one sibling keeps the shared bucket, and the other sibling still hits.
  - The last release evicts the bucket.
  - **It is red on the parent: 7 assertions** (run from a copy in scratch namespace
    `lane.new-query-scope-tests`).
- **`revisions-never-promote-a-result-across-connection-lineages`** is green on both.
  It is a guard for the new design. It is **red when the lineage check is removed**:
  `(not (= "after" "before"))`, which is a false promotion (falsified in my JVM with
  `alter-var-root`).

Updated tests, whose expectations had changed:

- `committed-identity-ignores-forced-legacy-collision-across-stores` now keys by
  result key.
- The two private `result-cache-put!` fence tests take the result key.
- The retired `sibling-branches-at-one-commit-have-independent-cache-scopes` was
  replaced by the sharing test above.

**Sharing bench** (`scratchpad/bench.clj`): a memory store with 203 attributes and
2,000 entities. The query is a join with a predicate. Values are the third run.

| step | parent | fork |
|---|---|---|
| main, first q | miss-owner 6.3 ms | miss-owner 9.0 ms |
| branch `:bench-b` at the same commit | **miss-owner 7.3 ms** | **hit 0.047 ms** |
| buckets after the branch query | 2 | **1** |
| branch after its own write | miss-owner 6.0 ms | miss-owner 10.1 ms |
| main after the branch's write | hit 0.09 ms | hit 0.17 ms |
| speculative `with` on the branch | uncacheable, key set unchanged | uncacheable, key set unchanged |

**Hot path** (`scratchpad/micro.clj`): fresh JVMs, µs per operation, min and median
over the runs. The q hit is 7 rounds of 100k; transact is 5×300 single-datom writes;
`with` is 5×5,000.

| probe | parent (5 runs) | fork (5 runs) |
|---|---|---|
| `q` completed hit | min 3.8-5.3, median 4.4-5.6 | min 3.8-4.2, median 4.2-4.6 |
| `transact` 1 datom | min 711-1,220 | min 527-1,036 |
| `with` 1 datom | min 44-47 | min 39-44 |

There is no regression.

A first measurement after an in-JVM reload showed q-hit at 5.0-5.9 µs. That led to
the hit-path reorder above. That JVM's numbers carry reload artifacts, so they are
not used. An in-JVM parent swap by `load-file` produced class-identity errors
(`QueryCall cannot be cast to QueryCall`) and mixed the code. It was discarded, and
the parent ran in its own JVM.

The lint (`clj-kondo`) on the three files shows the same 31 errors as the parent.
The extra warnings are all `Unresolved var: d/...`, the stale `datahike.api` macro
cache.

## Seon follow-up (other owner: `src/seon/db.clj`, not edited)

Audit item 9's second half. In `carried-projection`'s committed branch
(`db.clj:1423-1437` in the current working tree), a revision **hit** never records
`[::commit id]`. A new branch at that commit therefore misses the revision key,
which carries the connection-id, and then misses the commit key too. The change is
to always file the cell under the commit key:

```clojure
(datahike.db/committed-value-identity source)
(let [commit-key [::commit (:datahike.value/commit-id
                            (datahike.db/committed-value-identity source))]
      cell (cache/lookup-or-miss
            projection-cache (projection-cache-key source)
            (fn [_]
              (cache/lookup-or-miss
               projection-cache commit-key
               (fn [_] (delay (content-projection source))))))]
  ;; A revision hit also names this commit, so a new branch or connection
  ;; at the same commit reuses the cell.
  @(cache/lookup-or-miss projection-cache commit-key (fn [_] cell)))
```

## Left

- **RESET NEEDED: JVM restart only.** Default loads Datahike from source, so the
  fork change is live only after a restart. The cache is process-local and nothing
  is stored.
- **Weight over-count from promotion copies.** Within one lineage, a promoted
  result is charged once per bucket (3.35 times on default). Making the budget
  count retained results would need identity-aware accounting in `datahike.lru`,
  or moving entries on promotion. Moving would lose hits for other lineages that
  read the older commit. This is not in this slice. The orchestrator should route
  it to the issue authority under audit item 9.
- An exact hit from a second lineage does not re-scope the entry's
  `:source-contexts`. That lineage therefore promotes by recomputing once after its
  first write, which is equal to the parent's cost.
- The ClojureScript build was not run. The change uses only portable core
  functions, and `lru.cljc` already uses `.-state` on both platforms.

## TIMINGS (over 1 s)

| operation | wall | justification |
|---|---|---|
| fork test JVM, first `require` of 28 namespaces | 19.6-21.8 s | cold compile of Datahike from source, proportional to the dependency (about 60k lines). **Over 10 s: defect**, the class already owed by the fncall-revisions landing (no warm fork runner or AOT classes). `docs/seon/issues/` is not in this lane's paths. |
| fork suite, 28 namespaces (`run-tests`) | 41.1-43.2 s | 234 deftests, many of which create and delete stores and connections, proportional to the suite. **Over 10 s: same defect class.** Focused runs were 12.5-14.0 s (5 namespaces). |
| JVM start to socket REPL `:ready` | about 4-5 s per start, 4 starts | JVM plus clojure CLI classpath (cached), proportional to the dependency set |
| `require :reload` of lru + query | 0.86 s | under 1 s |
| sharing bench, first query per JVM | 3.3-4.1 s | the first query compiles the planner paths, proportional to the query engine; later runs 6-24 ms |
| git push of the fork | 1.9 s | network round trip to GitHub |

Every other probe was under 1 s. The reuse bench shows the new cache hits: a branch
at the same commit is a hit, and a speculative value stays uncacheable.

## Changed paths

- fork: `CHANGELOG.md`, `src/datahike/query.cljc`, `src/datahike/lru.cljc`,
  `test/datahike/test/query_cache_test.cljc`
- superproject: gitlink `reference-code/datahike` `0c01b5fe` → `684d3290`, and this note
