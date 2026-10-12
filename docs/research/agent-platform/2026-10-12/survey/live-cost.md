---
title: Survey live-cost
type: research
status: measured; numbers are cumulative since default's JVM started (see Notes)
basis: HEAD 45bfab18e when the survey started (901de6afe at commit time); default runs b14dc2b3f per assignment (not re-verified with runtime_status)
method: "mcp__seon__eval_clj jvm mode as root on cluster default; forms described below"
---

# Survey live-cost: where the running default spends time and memory

Method: `mcp__seon__eval_clj` in jvm mode as root on cluster "default", read-only queries: `(seon.cache/status)`; a Runtime heap read; `seon.profile/cells` through `seon.profile/observation`, aggregated per symbol into rankings by total ms and by calls; `(seon.health/report {:seon.boot/cluster-name "default"})`, its `:seon.health/findings` filtered to `*warning` schemas; `seon.db/q` over `:seon.eval/duration-ms` and `:seon.eval/allocated-bytes` on `(seon.cluster.boot/connection "default")`, pulled with `[*]`, the run's `:seon.turn/agent`. Two eval_clj calls ran as recorded runs (one transaction each), as every jvm-mode run does.

## For root

1. `seon.sci.eval/program-context` (cache kind): 157 MB weighed, 4,948 ms load over 5 misses (about 1 s per build), hit 44%. Largest single byte holder after `seon.schema.edn/packaged-population`. E7 (bc347d583) is not yet live on default.
2. `seon.cache` kinds `projection` (70 MB, 2.8 s load) and `declaration-projection` (62 MB, 2.4 s load): 132 MB together, hit 90% and 98%.
3. `seon.health/derived` cache kind: 8.9 s load over 50 misses (177 ms per miss), hit 38%. Health report itself 115-183 ms warm.
4. `seon.health/problems` cache kind: 6.7 s load over 3 misses (about 2.2 s each), hit 62%.
5. `seon.cache` `reach-graph`: 4.5 s load for 2 misses (about 2.2 s each); `reach-index` 247 ms for 2 misses.
6. `seon.db/transact-call`: the one transaction per eval request; settle stage 26-72 ms on ordinary replies (e.g. `(+ 1 2)`: evaluate 6 ms, settle 30 ms). Cumulative 744 ms self per call over 207 calls (boot-skewed).
7. `seon.cluster/rehearse-source!` and `seon.cluster/full-source-refresh!`: mean 12.4 s and 9.7 s per call over 36-37 calls (max 16.6 s, 14.8 s). Reload path, not per request.
8. `seon.fn.analyzer/analyze-dependencies!` one call 6.5 s; `seon.fn.analyzer/analyze` mean 0.8 s over 108 calls (max 2.1 s).
9. Instrumentation volume: 1.9k-35k armed calls per eval reply (`(+ 1 2)`: 6,084 calls); `seon.cache/cache?` 417,950 calls, `seon.profile/adder?` 38,445, `seon.profile/counts?` 20,750 (cumulative).
10. `my.agent/retire-branch!` 2.4 s (24 MB) and 1.6 s (146 MB) on stored root evals.

Already filed: `seon.db/transact-call-takes-five-seconds-at-its-maximum`, `seon.db/the-checked-writer-is-slow-on-a-fresh-branch`, `seon.sci.eval/forms-just-after-boot-cost-hundreds-of-ms`, `seon.sci.eval/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`, `seon.cache/one-weighted-cache-under-a-memory-cap`, `seon.fn.analyzer/analysis-runs-over-the-one-second-bound-without-a-task`, `seon.instrument/hot-internals-pay-armed-validation`, `seon.instrument/loading-source-arms-tens-of-thousands-of-calls`.
Not filed: `seon.health/derived` and `seon.health/problems` cache load cost; `seon.cache` reach-graph 4.5 s; `my.agent/retire-branch!` latency and allocation (the filed `retire-branch-refuses-a-finished-agent-as-admission-closed` is a refusal, not cost).

## Table

### Cache kinds (`seon.cache/status`), sorted by hit % ascending; flags: hit < 50 or bytes > 20 MB

| kind | hit % | hits | misses | evictions | MB | load ms | flag |
|---|---|---|---|---|---|---|---|
| file-schema-keys | 0 | 0 | 612 | 0 | 2 | 40 | hit 0 |
| reads | 0 | 0 | 18 | 0 | 0 | 0 | hit 0 |
| replaced-roots | 0 | 0 | 8 | 0 | 0 | 62 | hit 0 |
| overlay-plan | 0 | 0 | 3 | 0 | 0 | 195 | hit 0 |
| refusal-recording | 0 | 0 | 1 | 0 | 0 | 48 | hit 0 |
| reach-attribute | 0 | 0 | 6 | 0 | 1 | 26 | hit 0 |
| cluster-attributes | 0 | 0 | 1 | 0 | 0 | 0 | hit 0 |
| reach-attribute-head | 25 | 3 | 9 | 0 | 0 | 0 | hit < 50 |
| file-declarations | 26 | 241 | 682 | 0 | 14 | 155 | hit < 50 |
| derived | 38 | 31 | 50 | 0 | 5 | 8866 | hit < 50 |
| loaded-digests | 33 | 1 | 2 | 0 | 3 | 32 | hit < 50 |
| reach-graph | 33 | 1 | 2 | 0 | 0 | 4497 | hit < 50 |
| reach-index | 33 | 1 | 2 | 0 | 0 | 247 | hit < 50 |
| behavior-digest | 49 | 3000 | 3061 | 0 | 1 | 2962 | hit < 50 |
| reach-node | 49 | 4018 | 4090 | 0 | 6 | 57 | hit < 50 |
| reach-schema-node | 49 | 3010 | 3029 | 0 | 2 | 50 | hit < 50 |
| program-context | 44 | 4 | 5 | 0 | 157 | 4948 | hit < 50; bytes > 20 MB |
| program-key | 50 | 3 | 3 | 0 | 0 | 208 | |
| problems | 62 | 5 | 3 | 0 | 0 | 6734 | load |
| untested | 62 | 5 | 3 | 0 | 0 | 1906 | load |
| derivation-world | 90 | 67 | 7 | 0 | 3 | 164 | |
| file-derivation-key | 90 | 36723 | 3871 | 0 | 10 | 1288 | |
| population-targets | 95 | 214 | 9 | 0 | 0 | 101 | |
| projection | 90 | 67 | 7 | 0 | 70 | 2808 | bytes > 20 MB |
| population-predicate-symbols | 90 | 74 | 8 | 0 | 0 | 30 | |
| program-attributes | 78 | 32 | 9 | 0 | 0 | 162 | |
| branch-attribute-changes | 80 | 12 | 3 | 0 | 0 | 1 | |
| authored-namespace | 98 | 2205 | 45 | 0 | 0 | 11 | |
| declaration-predicates | 98 | 406 | 8 | 0 | 0 | 32 | |
| declaration-projection | 98 | 406 | 8 | 0 | 62 | 2434 | bytes > 20 MB |
| decoded-edn | 93 | 81 | 6 | 0 | 0 | 0 | |
| task-basis | 94 | 35 | 2 | 0 | 0 | 33 | |
| tasks-tx | 86 | 62 | 10 | 0 | 0 | 627 | |
| canonical-keys | 99 | 110 | 1 | 0 | 0 | 0 | |
| authored-shapes | 99 | 124507 | 7 | 0 | 1 | 15 | |
| vector-pull | 99 | 51926 | 150 | 0 | 2 | 44 | |
| packaged-population | 99 | 125157 | 30 | 0 | 39 | 988 | bytes > 20 MB |

Totals: 388 MB weighed of a 488 MB budget. Zero evictions in every kind.

### Profile counters: top 25 by total ms (cumulative, all cells aggregated per symbol)

| symbol | calls | total ms | self ms | ms/call (total) | self ms/call | max ms |
|---|---|---|---|---|---|---|
| call-with-projection | 697 | 479707 | 18 | 688.2 | 0.026 | 16062 |
| request! | 43 | 457115 | 3 | 10630.6 | 0.070 | 16579 |
| rehearse-source! | 36 | 448233 | 13036 | 12450.9 | 362.1 | 16579 |
| full-source-refresh! | 37 | 357108 | 168 | 9651.4 | 4.5 | 14836 |
| analyze-entities | 72 | 226407 | 81 | 3144.5 | 1.1 | 5475 |
| analyzed-files | 72 | 226324 | 294 | 3143.4 | 4.1 | 5473 |
| analyzed-artifacts | 180 | 200457 | 6227 | 1113.7 | 34.6 | 5336 |
| with-branch | 122 | 188130 | 6 | 1542.0 | 0.05 | 5182 |
| call-with-forms | 212 | 174665 | 3 | 823.9 | 0.01 | 5059 |
| transact! | 207 | 154486 | 41 | 746.3 | 0.20 | 3548 |
| transact-call | 207 | 154381 | 154030 | 745.8 | 744.1 | 3546 |
| publish! | 36 | 111659 | 60 | 3101.6 | 1.7 | 5186 |
| populate-source! | 36 | 109074 | 0 | 3029.8 | 0.0 | 5059 |
| index! | 36 | 106303 | 99 | 2952.9 | 2.8 | 4843 |
| analysis-entities-by-file | 108 | 98672 | 5455 | 913.6 | 50.5 | 2952 |
| definition-digest | 121914 | 93081 | 364 | 0.76 | 0.003 | 134 |
| used-resolver-context | 119533 | 89893 | 1358 | 0.75 | 0.011 | 134 |
| var-entity | 92368 | 88233 | 1655 | 0.96 | 0.018 | 134 |
| analyze | 108 | 85515 | 13623 | 791.8 | 126.1 | 2102 |
| lookup | 326151 | 75922 | 609 | 0.23 | 0.002 | 1897 |
| adopt-entities! | 35 | 73498 | 7 | 2099.9 | 0.2 | 3832 |
| rehearsal-adoption! | 34 | 69855 | 1 | 2054.6 | 0.03 | 3020 |
| with-declarations | 71767 | 63899 | 554 | 0.89 | 0.008 | 1226 |
| shape | 119804 | 58683 | 147 | 0.49 | 0.001 | 133 |

### Profile counters: top 25 by calls

| symbol | calls | total ms | self ms | ms/call (total) | self ms/call | max ms |
|---|---|---|---|---|---|---|
| contains-position? | 18633114 | 3321 | 3321 | 0.000 | 0.000 | 10 |
| transaction-report-datom? | 12417885 | 1245 | 1245 | 0.000 | 0.000 | 8 |
| projection-cache-value | 11309403 | 4718 | 3585 | 0.000 | 0.000 | 28 |
| database-value? | 6614994 | 550 | 550 | 0.000 | 0.000 | 8 |
| write-attribute-plan | 3875689 | 1930 | 781 | 0.000 | 0.000 | 11 |
| vector-shaped-attr-in? | 3554608 | 1867 | 677 | 0.001 | 0.000 | 7 |
| vector-attribute-in? | 2149466 | 1632 | 535 | 0.001 | 0.000 | 30 |
| edn-round-trip-symbol? | 2060877 | 782 | 392 | 0.000 | 0.000 | 2 |
| edn-symbol-text? | 2060876 | 390 | 390 | 0.000 | 0.000 | 2 |
| ref-attr-in? | 1550142 | 725 | 279 | 0.000 | 0.000 | 0 |
| row-identity | 1373742 | 680 | 680 | 0.000 | 0.000 | 10 |
| input-path? | 1315605 | 4087 | 4087 | 0.003 | 0.003 | 11 |
| decode-pulled | 1270354 | 1905 | 1185 | 0.001 | 0.001 | 31 |
| compare-values | 1200562 | 301 | 301 | 0.000 | 0.000 | 8 |
| reference-id | 896999 | 152 | 152 | 0.000 | 0.000 | 4 |
| schema-database | 823597 | 394 | 280 | 0.000 | 0.000 | 0 |
| compiled-storage | 813923 | 184 | 184 | 0.000 | 0.000 | 6 |
| projection-builder | 714104 | 1364 | 831 | 0.002 | 0.001 | 70 |
| projection-cache-key | 713993 | 661 | 608 | 0.001 | 0.001 | 8 |
| projection-value | 713517 | 2486 | 146 | 0.003 | 0.000 | 85 |
| carried-projection | 713489 | 10033 | 1750 | 0.014 | 0.002 | 131 |
| stored-collection | 619861 | 5878 | 1026 | 0.009 | 0.002 | 8 |
| logical-collection | 458697 | 933 | 601 | 0.002 | 0.001 | 7 |
| widening-path? | 440353 | 2666 | 755 | 0.006 | 0.002 | 9 |
| declared-storable? | 421781 | 256 | 145 | 0.001 | 0.000 | 6 |

Allocation is not recorded per armed function (no alloc field in `seon.profile/observation`); allocation exists only per evaluation (`:seon.eval/allocated-bytes`) and in `seon.profile/measure`.

### Health warnings: `seon.health/report` for cluster "default"

| schema | score | subject | calls | total ms | p99 us |
|---|---|---|---|---|---|
| untested-hot-warning | 590 | seon.cache/cache? | 417950 | 96 | 2 |
| untested-hot-warning | 539 | seon.test.runner/class-loader? | 130193 | 44 | 2 |
| untested-hot-warning | 510 | seon.schema/pull-selector? | 67031 | 230 | 65 |
| untested-hot-warning | 483 | seon.print/node-child? | 38144 | 8 | 1 |
| untested-hot-warning | 487 | seon.profile/adder? | 38445 | 3 | 0 |
| untested-hot-warning | 460 | seon.profile/counts? | 20750 | 2 | 0 |
| untested-hot-warning | 444 | seon.batch/stamped-clock? | 14298 | 1 | 1 |
| untested-hot-warning | 444 | seon.profile/thread-local? | 14198 | 1 | 1 |
| untested-hot-warning | 443 | seon.profile/throw-counts? | 13994 | 1 | 1 |
| untested-hot-warning | 441 | seon.profile/accumulator? | 13394 | 1 | 1 |
| untested-hot-warning | 437 | seon.profile/histogram? | 12258 | 8 | 1 |
| cache discipline-warning | 318 | seon.sci.eval/program-context | 4 hits, 5 misses | load 4948 ms | 165 MB |

Report state: `:faulted`. Counts of other findings: open-fault 9, agent-error 18, unknown-test 21, errored-receipt 72, unowned-namespace 22. Only the warnings are tabled above.

### Stored evaluations over one second (`:seon.eval/duration-ms` > 1000)

52 evaluations. 35 are synthetic `(Thread/sleep N)` probes (N from 40 to 85 s); they are not costs. The 20 worst are all sleeps, so they are not listed. Of the 17 others, newest first, all by agent `root`:

| at (GMT+7) | ms | alloc MB | form (first 80 chars) |
|---|---|---|---|
| 2026-10-12 16:54:13 | 2398 | 24 | `(my.agent/retire-branch! {:seon.store/branch :agent-175e7196ca5b})` |
| 2026-10-12 16:51:42 | 1576 | - | `[(str (java.time.Instant/now)) (c4-pass!) (map (juxt :seon.task/assignment :seon` |
| 2026-10-12 16:28:17 | 1164 | - | `(let [db (d/db (seon.cluster.boot/connection "default")) attrs (d/q '[:find [?a ` |
| 2026-10-12 16:09:29 | 1439 | - | `(def r [(assoc (probe "cold-a" "(+ 1 2)") :case :first-request-after-boot) (asso` |
| 2026-10-12 15:56:29 | 1562 | - | `(let [req (fn [agent code] (let [m (seon.profile/measure #(#'seon.mcp/eval-clj {` |
| 2026-10-12 15:56:05 | 1032 | - | `(let [db (d/db (seon.cluster.boot/connection "default")) attrs (->> (d/q '[:find` |
| 2026-10-12 15:21:16 | 5092 | - | `(let [a (seon.profile/summary (seon.profile/cells) 8)] (Thread/sleep 5000) [a (s` |
| 2026-10-12 14:18:31 | 1015 | - | `(let [rows (seon.census/prose (seon.census/files ["src"] [".clj" ".cljc"])) hit ` |
| 2026-10-12 14:10:20 | 1569 | 146 | `(let [out (mapv (fn [b] [b (let [r (my.agent/retire-branch! {:seon.store/branch ` |
| 2026-10-12 13:32:34 | 3018 | - | `(let [main (db/db (seon.cluster.boot/connection "default")) tests (vec (sort (d/` |
| 2026-10-12 13:19:20 | 3108 | - | `(let [conn (seon.cluster.boot/connection "default") d (seon.db/branch-database (` |
| 2026-10-12 11:52:13 | 4795 | - | `(let [m (seon.profile/measure #(dotimes [_ 100] (#'seon.mcp/eval-clj {:seon.mcp/` |
| 2026-10-12 11:13:06 | 6509 | - | `(seon.fn.analyzer/analyze-dependencies!)` |
| 2026-10-12 11:12:02 | 2096 | - | `(let [conn (seon.cluster.boot/connection "default") main @conn br (:seon.agent/b` |
| 2026-10-12 09:51:31 | 3126 | - | `(dotimes [_ 40] (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/agent "` |
| 2026-10-12 09:47:01 | 4216 | - | `(let [t0 (System/nanoTime)] (dotimes [_ 60] (#'seon.mcp/eval-clj {:seon.mcp/code` |
| 2026-10-12 09:34:21 | 1222 | - | `(let [conn (seon.cluster.boot/connection "default") db (datahike.api/db conn) ta` |

Most of the 17 are root's own diagnostic or benchmark forms, several of them reads by this survey's pattern (`(d/q ...)`, `seon.profile/measure`). Only `my.agent/retire-branch!` and `analyze-dependencies!` are ordinary operations. Only 32 evaluations carry `:seon.eval/allocated-bytes` at all; the largest is 146 MB.

### Heap (`Runtime`, at the time of the health and profile queries)

| used MB | committed MB | max MB |
|---|---|---|
| 1671 | 3014 | 4096 |

## Notes

- Counters are cumulative since default's JVM started, so they include boot, reload and publication, not only ordinary requests (`rehearse-source!`, `full-source-refresh!`, `request!`, `publish!` dominate the total-ms ranking). They are not averaged by request. **inferred** from `seon.profile/observation` and `seon.profile/cells` docstrings.
- The profile observation carries no allocation; the table therefore has no alloc column by function.
- My own eval_clj forms were recorded on default during the survey (about 15 jvm-mode runs), so the stored evaluations and the health counts include them. The survey did not run SCI forms or tests on default.
- The cache table changes as queries run; numbers are as read at about the same minute.
- Per-request cost examples from replies: `(+ 1 2)` 41 ms, 6,084 armed calls, settle 30 ms; a pull-heavy read 80-120 ms, 21k-26k armed calls, settle 35-40 ms. These are one reply each, not sampled statistics.
- Health findings other than warnings were not classified; the health state is `:faulted`, which the report attributes to open faults and agent errors. Unverified in detail.
- The default commit b14dc2b3f comes from the assignment, not from runtime_status, and is unverified.
- Hot predicates (`seon.cache/cache?`, `seon.profile/*?`) are counted through the instrumentation's armed wrapper; their per-call cost is about zero (p50 0-1 us), so the cost is volume, not time. **inferred**.
