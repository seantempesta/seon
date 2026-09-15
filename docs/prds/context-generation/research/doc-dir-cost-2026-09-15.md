---
type: research
status: active
tags: [research, sci, database, performance]
---

# Doc and dir cost — 2026-09-15

Implementation commit: `d5e5b870e` on `steward-platform`.
Production change: `src/seon/db.clj`; regressions: `test/seon/db_test.clj`.
No documentation-rendering, schema-constructor, or environment-owner edits.

## Surface and authority

Read AGENTS.md, both assigned allocation issues, and the three assigned
skills end to end, followed by the complete named documentation, schema
projection, and database read functions. Also used the clojure-testing skill.
Measurements use default's MCP JVM, PID 23729; no stop, restart, or refork.
Runtime health returned unknown / Read timed out, while JVM evaluation worked.
The existing [health issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md)
owns that observation boundary. No protected files are edited by this slice.

## Before

ThreadMXBean measures bytes allocated on the evaluating platform thread,
not retained heap or work performed on other threads. Units below are exact
bytes. The issue describes the method and sequence but has no literal probe
form; the equivalent form used is preserved below.

| Operation | ms | allocated bytes | rows |
|---|---:|---:|---:|
| program-documentation, first, my.message | 9707.402375 | 48569985464 | 4 |
| projection-from-database, same DB | 438.265667 | 1856142672 | 22 map entries |
| program-documentation, second | 478.87 | 1860624248 | 4 |
| raw pull-in-find, my.note | 10.696167 | 18834960 | 3 |
| raw pull-in-find, repeated | 0.033042 | 17912 | 3 |
| raw function ids, my.message | 8.155542 | 18426832 | 4 |
| raw pull-many with cached ids | 0.336625 | 432928 | 4 |
| projection alone, later acquired DB | 9132.229292 | 48560246024 | 22 map entries |

The nested documentation pull is not the root cause; it remains unchanged.
`read-declarations` made an operation-local delayed projection, and string
decoding forced the full constructor whenever thread-local custody was absent.
The warm rebuild accounts for essentially all warm documentation allocation.

Cold projection construction also calls
`schema/admission-from-asserting-transaction` once per distinct asserting
transaction. Its `[?declaration _ _ ?tx]` query costs 590,195,544 bytes /
140.003833 ms for one uncached absent transaction. Reordering its clauses
costs 590,196,456 bytes / 141.249542 ms. Function sources alone span 72
transactions. Reading all transaction/source pairs together costs 634,643,152
bytes / 217.708375 ms. An equivalent indexed datom traversal produced the
same 109 transaction/source sets in 26.812792 ms / 35,818,160 bytes.
These explain the cold amplification; the owner's subsequent ruling keeps
this slice at the database carriage seam, removing rebuilds from ordinary
agent reads instead of changing projection construction.

## Dependency ledger and design

- `src/seon/db.clj`: `supplied-database-value` receives the environment;
  explicit connection acquisition uses `resolve-database-value`.
- `src/seon/env.clj:289`: `env/of` dereferences the state into the immutable
  environment before call preparation supplies it. The supplier carries an
  existing state when available, otherwise uses `env/environment-state` to
  hold that already supplied environment. Its projection object is retained.
- `resources/seon/operator/runtime.clj:11`: the existing running-instance
  registry owns live cluster custody. Explicit connection acquisition matches
  the actual connection, not a branch name shared by another store.
- `src/seon/schema.clj:264`: `projection-cache-value` already retains compiled
  codec answers on the projection. No cache mechanism or registry is added.
- `reference-code/datahike/src/datahike/db.cljc:385`: committed identity is
  connection/generation/commit, unaffected by record metadata.
- `reference-code/datahike/src/datahike/query.cljc:3449`: planner keys depend
  on clauses, bindings, rules, input shape, and schema hash, not DB identity.
- Live probe: `vary-meta` copy has equal committed identity, equal query
  result, and explicit `:datahike.cache.outcome/hit`. `history`, `as-of`, and
  `since` each preserve the exact carried instance as their schema origin.
- Archaeology: `768c6a0e0` added the old global projection LRU; `371a50dba`
  deleted it. This change does not restore that cache.

The owner directed metadata carriage on 2026-09-15. Reads consult origin
metadata first, supplied dynamic projection second, and only then rebuild.
Fallback emits one bounded warning line per forced read delay, naming the
read operation and elapsed milliseconds, including failed construction.
Native reads that need no projection emit no warning. Temporal constructors
also copy metadata to their outward wrapper; decoding follows the origin.

## Exact baseline probe

```clojure
(let [database @(seon.operator/connection "default")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      measure (fn [label f]
                (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
                      start (System/nanoTime)
                      result (f)]
                  {:label label
                   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                   :allocated-bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) before)
                   :rows (count result)}))]
  [(measure :documentation-first #(#'seon.sci.eval/program-documentation database 'my.message))
   (measure :projection #(seon.schema/projection-from-database database))
   (measure :documentation-second #(#'seon.sci.eval/program-documentation database 'my.message))])
```

## After and gates

Live verification used a hot reload of `seon.db` followed by
`seon.instrument/apply!` with default's supplied projection. This is not a
successful in-place adoption claim. Automatic adoption was refused at program
reconciliation: test-run `19d02fe5428b` has both `:seon.test.run/id` and
`:seon.test.run/program-digest` identities. The hook result is
`tmp/source-publications/5c5f0eeb-dbab-4443-96d4-c3623217fb72.edn`;
`logs/current-source-failure.log` records `:seon.fn/index-refused`.
Protected test-provenance/adoption files were not changed to repair it.

| Warm operation | ms | allocated bytes |
|---|---:|---:|
| supplied nested pull-many | 0.506542–0.568916 | 820872–822984 |
| supplied wildcard pull-many | 0.521417–0.650834 | 1371000–1376496 |
| documentation-value | 0.647792–0.952583 | 1214512 |
| directory-value | 2.003–2.197042 | 4262728–4262792 |
| full SCI `(doc my.message/send)` | 13.636–18.313417 | 23950992–23951056 |
| full SCI `(dir my.message)` | 8.637417–9.459833 | 14370024–14370096 |

The first nested and wildcard pulls after reload cost 2.073416 ms / 1457936
bytes and 1.312833 ms / 1363656 bytes. First documentation-value was
13.1155 ms / 22286888 bytes; first directory-value 6.62025 ms / 8334296 bytes.
First complete SCI evaluations were 384.470041 ms for doc and 194.010833 ms
for dir, including evaluation/render setup. Subsequent calls are the table
above. Every SCI request carried a 5000 ms evaluation limit and returned a
documentation map without an evaluation error. No provider request was made.

Cold *unowned* reads still build the whole projection. That fallback is
deliberately retained for fixtures and raw database callers, but now emits a
warning on every call that forces it. It is not claimed to meet the warm
agent-read bound. The broader projection issue is narrowed to that explicit
fallback and direct constructor callers.

Fast isolated check: `bin/test-fast --paths src/seon/db.clj
test/seon/db_test.clj -- seon.db-test`, snapshot HEAD `466f562e6`, **45 tests /
320 assertions, zero failures/errors**. The three new regressions measure
nested and wildcard reads (<10 MB and <20 ms), verify one fallback warning
per uncarried call, and verify temporal-origin decoding without fallback.
The existing unhanded-query comparison also passes: ten raw queries
31,062,542 ns versus wrapped 31,177,834 ns.

Isolated gate: `bin/test --paths src/seon/db.clj test/seon/db_test.clj --
seon.db-test`, snapshot HEAD `466f562e6`, **45 tests / 324 assertions, zero
failures/errors, exit 0**. Successful root `tmp/test-runs/run.MM1SAk` was
removed by the runner.

Platform gate: `bin/test --paths src/seon/db.clj test/seon/db_test.clj
--platform`, same snapshot HEAD, **85 tests / 514 assertions / 6 failures /
0 errors, exit 1**. All failures belong to the protected runner fixture:
`selected-paths-overlay-head-for-preparation-and-every-worker` launches a
temporary repository without `bin/_test-slot`. The concurrent lane's complete
bin-directory copy is uncommitted and correctly excluded from this snapshot.
The gate additionally reported a persistent-results recording refusal:
`Branch head changed before force-branch!`. No green platform gate or durable
platform result is claimed. Separate issue records preserve both boundaries:
[fixture helpers](../../../seon/issues/test-launcher-fixtures-omit-required-helpers.md)
and [result recording](../../../seon/issues/test-result-recording-refuses-after-branch-head-change.md).

After adding explicit allocation-counter availability assertions, the final
database gate ran **45 tests / 326 assertions / 0 failures / 0 errors**.
Production bytes are unchanged from the platform run. The launcher exited 0
and removed `tmp/test-runs/run.fJcyBy`, but printed
`:seon.fresh-operator/prepl-response-silent`: persistent recording received no
response for its 30000 ms bound. Therefore this last run proves the assertions,
not durable result publication. The protected runner's old exit behavior
returned 0 despite that missing evidence; the concurrent test-provenance
change addresses that behavior. The earlier 45/324 isolated gate had exited 0
without a recording refusal. No protected session or file was operated to
repair either recording boundary.

Cleanup: the platform runner exited before cleanup; the process table showed
no Java process retaining `run.sGdSJM`. Its failed root was then removed without
following symlinks. No scratch cluster or worktree was created. The owner must
reconcile the issue index for the archived documentation issue and the two new
platform-boundary notes; this lane does not edit the owner's schedule.

### Exact full-SCI after probe

```clojure
(let [ctx (:seon.sci.eval/ctx
           (get @seon.operator.runtime/running-instances "default"))
      caps (:seon.sci.admit/caps (seon.env/of ctx))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))]
  (mapv
   (fn [source]
     (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
           started (System/nanoTime)
           result (seon.sci.eval/evaluate
                   {:seon.sci.eval/ctx ctx :seon.cluster.eval/source source
                    :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :panic})
           elapsed (/ (- (System/nanoTime) started) 1e6)
           allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) before)]
       {:source source :elapsed-ms elapsed :allocated-bytes allocated
        :success? (and (not (:seon.cluster.eval/error result))
                       (map? (:seon.sci.admit/value result)))}))
   ["(doc my.message/send)" "(dir my.message)"
    "(doc my.message/send)" "(dir my.message)"]))
```

### Metadata/cache/origin probe

```clojure
(let [connection (seon.operator/connection "default")
      database @connection
      state (get-in (get @seon.operator.runtime/running-instances "default")
                    [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
      carried (vary-meta database assoc :seon.sci.eval/projection-state state)
      query '[:find [?f ...] :in $ ?name
              :where [?n :seon.ns/name ?name] [?f :seon.fn/ns ?n]]
      first-response (datahike.api/q-with-evidence query database 'my.message)
      second-response (datahike.api/q-with-evidence query carried 'my.message)]
  {:state-present (some? state)
   :same-connection (identical? connection (:seon.db/connection @state))
   :same-identity (= (datahike.db/committed-value-identity database)
                     (datahike.db/committed-value-identity carried))
   :same-result (= (:datahike.query/result first-response)
                   (:datahike.query/result second-response))
   :copied-cache (:datahike.query/cache-evidence second-response)
   :temporal-origins
   (mapv #(identical? carried (seon.db/schema-database %))
         [(datahike.api/history carried)
          (datahike.api/as-of carried (:max-tx carried))
          (datahike.api/since carried 0)])})
```

Result: every boolean true, three true temporal origins, and copied cache
`:datahike.cache.outcome/hit` with `stored? true`.

### Supplier-pull and documentation-owner after probe

Use the baseline's `measure` function with these bindings and calls:

```clojure
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      environment @(:seon.sci.eval/projection-state (meta database))
      ids (datahike.api/q
           '[:find [?f ...] :in $ ?name
             :where [?n :seon.ns/name ?name] [?f :seon.fn/ns ?n]
                    [?f :seon.fn/private? false]] database 'my.message)]
  (mapv
   (fn [_]
     [(measure :nested
               #(seon.db/pull-many (seon.db/supplied-database-value environment)
                                   @#'seon.sci.eval/program-documentation-selector ids))
      (measure :wildcard
               #(seon.db/pull-many (seon.db/supplied-database-value environment) '[*] ids))
      (measure :doc #(seon.sci.eval/documentation-value database 'my.message/send 'my.message/send))
      (measure :dir #(seon.sci.eval/directory-value database 'my.message true))])
   (range 3)))
```
