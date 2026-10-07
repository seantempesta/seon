# review-dhplanner-opus notes (2026-10-07)

Independent review of Datahike fork commit `5258679c` (branch `seon/identity-join-first`, parent `d9121bbb`), per
[review-dh-planner-spec.txt](review-dh-planner-spec.txt). Astra reviewed the same commit as lane `dh-planner-review`.

**Fork commit landed: `49e681db`** on origin `seon/identity-join-first` and `main` (fast-forward from `5258679c`).
Seon's gitlink is not bumped (root does it). **RESTART NEEDED** after the bump.

## Broken things first

1. **Answers did change (claim 1 contradicted, now fixed).** Two executor defects that predate the commit are reached by
   the plans it now prefers (a bound unique lookup first, then entity groups probed by it):
   - **Wrong-arity tuples.** `execute.cljc` `execute-plan-direct`, multi-group path: a consumer group with attached
     predicates emits wide tuples; with no probe-map combine and no post-ops nothing projects them. Form, on a
     2,000/20,000/100,000-run store:
     `[:find ?form ?ordinal :in $ ?run-id :where [?run :turn/id ?run-id] [?form :eval/run ?run] [?form :eval/ordinal ?ordinal] [(> ?ordinal 0)]]`
     answered `[?form ?ordinal ?run]` 3-tuples on BOTH parent and change at all three sizes (`:find ?n` answered
     3-tuples too). Default runs the parent today, so Seon callers of this shape get wrong-arity rows now: task
     `seon.db/fused-joins-with-a-predicate-answer-unprojected-tuples` (57bc14161427).
     Fix: project to `emit-vars` unless the probe-map combine or post-ops read the wide layout.
   - **History merges drop rows.** `execute-group-direct`: the history merge walks a `[current temporal]` EAVT cursor
     pair that only moves forward (`history-versions!`), but an AVET scan (pushed range predicate) or value seeks
     (bound value collection, relation) emit entities in value order, so every entity below the last one merged was
     skipped. Parent: `[:find ?p ?t :where [?p :p/name ?n] [(> ?n "name3")] [?p :p/team ?t]]` on history 10 rows vs base
     24. The change made it reachable at a smaller store: `:in $ [[?id ?st]]` relation query on history, 2 rows vs base
     4 (parent right at that size). Fix: the cursor fast path serves only entity-ordered slices (EAVT/AEVT, entity
     seeks); others take the per-entity slow path. Same class as Astra's task `seon.db/history-joins-drop-matching-datoms`
     (fd3957971e6d); its other symptom (versions belonging to the scan version) is not covered by this fix.
2. **Pre-existing, not fixed:** a recursive rule over a history db disagrees with the base engine (1 or 19 rows vs 6)
   on parent, change and fix alike: task `seon.db/planned-recursive-rules-on-history-disagree-with-the-base-engine`
   (e2b874aff166).
3. **Stale plans are catastrophic, before and after.** A plan cached on a 10-run store, run after growth to 50,000 runs:
   receipts query 3,629 node reads / 1.2 s on the change (3,635 / 11.3 s on parent) vs 9 reads for a fresh plan. The
   plan bakes the index choice (AEVT below the seek break-even) and `:estimated-card`, which gates probe-driven seeks
   at run time. The parent's replan re-estimation did not rescue it either, so removing it lost nothing measurable.
   Existing task `seon.db/query-plans-are-reused-across-database-sizes` (3523ddcf8a1a). Note the plan cache is
   JVM-global and keyed by schema hash, so a small branch or test db with the same schema can seed default's plans.

## Claims

| claim | verdict | evidence |
|---|---|---|
| 1 answers unchanged | **contradicted** (fixed in `49e681db`) | Differential battery (`probe.diff`, 40 shapes: identity, ref, card-many unique, lookup-ref input/coll/value, relation and tuple `:in`, rules, or/or-join/not/not-join, predicates, fn binds, get-else, aggregates, `:with`, pull, disconnected, empty, absent and moved unique values) on current, history and as-of dbs at 20/50 and 400/3,000, planner vs base engine. Parent mismatches: pred (current), relation-in, rule-reach, not-join (history). Change adds relation-in at 20/50 history and pred on the grown db. After fix: only rule-reach on history/as-of remains. The unique-estimate rule is estimate-only; collection, lookup-ref, absent and moved values all answered correctly. |
| 2 cost | **supported**, with item 3 above | Receipts reads, parent → change: 37 → 13 (2k runs), 245 → 7 (20k), 419 → 9 (100k). A shape whose plan flips under `group-start-card` (3-pattern group over 300k junk datoms vs an orders group): 205 → 204 reads, median 40-42 → 41-48 ms (noise). Stale-plan sweep (40 shapes, plan on 20 people, run on 20,000 people + 100,000 orders): no shape regressed beyond a few ms; relation-in 140 → 4 ms, two-groups 46 → 4 ms. Refuted my own suspicion that `sample-distinct-v-count` reusing a heuristic base inflates small-attribute fan-out: the old code divided the same `base` by an exact distinct count, so small slices are identical and large ones are more consistent. |
| 3 same reds | **supported** | Kaocha `clj-pss`: `5258679c` 918 tests / 4,985 assertions / 13 failures; `49e681db` 919 / 4,989 / 13, identical failing tests (attribute-refs pull-limit, connector-release ×2, query-cache config-mismatch, query-test dependencies ×4, writer-error ×5). Focused query suites by `clojure.test` (311 tests parent, 316 change and fix): 1 failure + 1 error, identical on all three. The lane's "5 focused reds" came from a different runner; not reproduced exactly. |
| 4 new test | **supported, with a gap** | Deterministic (fixed data, `:store-cache-size 1`). Its shape table missed both executor defects; added a `:with-predicate` shape (fails on `5258679c`: `#{[1 61 63] [2 61 64]}` vs `#{[63 1] [64 2]}`). Plan assertions parse `d/explain` text. |
| 5 port of #974 | **supported** | `lower.cljc` and `plan.cljc` hunks are verbatim upstream 314d4a4d (comments included). One extra: `(when (number? card) ...)` in `source-cards :bind`, equivalent because `resolve-fn-output-cardinality` returns a long or nil. Upstream's `output-card-bound` binding is a no-op whose comment claims a bound-aware start; ported as is (Astra's working tree removes it). |

## Fix (`49e681db`, +60 −3)

- `src/datahike/query/execute.cljc`: consumer attached-pred projection; `entity-ordered-slice?` gates the history
  cursor pair.
- `test/datahike/test/query_identity_join_test.clj`: `:with-predicate` shape.
- `test/datahike/test/query_planner_temporal_test.clj`: `test-history-merge-over-value-ordered-scan` (range predicate
  and bound value collection on history; on `5258679c` 1 of 6 and 1 of 3 rows).

Both regressions fail on `5258679c` (4 failures) and pass on `49e681db`. All probes ran in scratch fork JVMs, never in
default's.

## Design check

- The fixes are at the producers (projection where tuples are produced; the cursor precondition where the slice order is
  known). The alternative of sorting value-ordered slices by entity would keep the fast path, but at O(k log k) per scan
  and for a probe-sized slice the slow path is already cheap; not measured further.
- The `group-start-card` cost model is asymmetric (the start costs its read, later steps their output). I found one
  plan flip from it and measured no cost difference; not changed.
- Astra (`dh-planner-review`) holds uncommitted edits in the shared submodule tree (`analyze.cljc`, `plan.cljc`,
  CHANGELOG, two new tests) and has claimed `execute.cljc`. It must rebase onto `49e681db`; its
  `query_history_cursor_test` `history-value-probes-preserve-every-entity` reproduces the cursor defect fixed here.

## Seon health seen

`runtime_status`: default `:faulted`, 16 open faults, 174 errored receipts, 43 unknown tests (existing tasks).
`(my.task/open (seon.db/db))` took 1,447 ms (13,748 armed calls, `seon.schema.datahike/query-variable?` 6,004 times):
a slow ordinary call (the repeat took 54 ms): task `my.task/open-takes-over-a-second-validating-query-variables`
(137f0c20f774).
