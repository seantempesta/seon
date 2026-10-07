---
title: Second review of the Datahike fork change d9121bbb..2ff3bd4d (Opus)
type: research
status: reviewed-and-fixed-on-fork
date: 2026-10-07
lane: review-dhfork2-opus
---

# review-dhfork2-opus notes (2026-10-07)

Second independent review of fork `reference-code/datahike`, branch `seon/identity-join-first`, `d9121bbb..2ff3bd4d`,
per [review-dh-fork2-spec.txt](review-dh-fork2-spec.txt), focused on dh-fix2's changes (`3cd6d267..2ff3bd4d`, see
[dh-fix2-notes.md](dh-fix2-notes.md)). Astra (`dh-review2`) reviewed the same range independently. I fixed what I
verified on the fork: seven commits, `2ff3bd4d..dbca76d5`, pushed to `seon/identity-join-first` and `main`
(fast-forward; origin had not moved). **Seon's gitlink is not bumped.** No RESET NEEDED; default needs a restart
only after root bumps the gitlink.

Which step of the one happy path this serves: every agent turn reads through these queries; a wrong join, a crash
or a duplicated tuple answers the agent wrongly. Each fix is at its producer (planner, lowering, executor, base
engine) and adds no cache, path or fallback; one direct-path shape now declines to the existing Relation path.

## Verdict

**Bump to `dbca76d5`, not to `2ff3bd4d`.** `2ff3bd4d` passes both earlier matrices (0 of 1,548; 0 of 300), but
dh-fix2 introduced three answer regressions and one 6x cost regression that the matrices do not reach, and the
review found eight older wrong-answer or crash classes on common shapes. All are fixed at `dbca76d5` with
regressions that fail on `2ff3bd4d`.

## Broken things first

Regressions introduced by `3cd6d267..2ff3bd4d` (all fixed, see Commits):

1. **Base engine: a repeated var inside a union branch throws** (`470c911a`). `[:find ?a :where [?a :p/id]
   (or [?a :p/friend ?a] [?a :p/score 1])]`: pin `#{[1] [3]}`; `2ff3bd4d` base `Can't sum relations with different
   attrs: {?a 0, ?a__repeat72796 1} and {?a 0}`. The fresh var leaked into the branch relation. Same leak for a
   function binding a bound var (`[(identity 1) ?s]` in a branch).
2. **Planner: a non-recursive rule called with a repeated arg answers nothing** (`470c911a`). `(same ?a ?a)` with
   `[(same ?x ?y) [(= ?x ?y)]]`: pin `#{[1] [2] [3] [4]}`; `2ff3bd4d` planner `#{}` (the separated arg is never bound
   by a predicate-only body).
3. **Shapes that were wrong rows became crashes** (`470c911a`'s fresh vars reach older executor defects, items 5-6
   below): `[:find ?a ?b :where [?a :p/friend ?b] [?b :p/friend ?b]]` pin `#{[4 2] [3 3]}` (wrong), `2ff3bd4d`
   `Index 2 out of bounds for length 2`; `[?a :p/nums ?a] (not [?a :p/likes ?e])` pin wrong rows, `2ff3bd4d`
   `Cannot read field "v" because "md…" is null`.
4. **Cost: a union written after the pattern binding its entity var runs first** (`ad28eadf`). The union's
   bound-aware estimate assumed every var bound earlier in source order and `op-cost` used it from the start.
   `[:find ?e ?t :where [?e :p/team :rare] (or [?e :p/tags ?t] [?e :p/nums ?t])]`, 60,000 entities
   ([or-cost](review-dhfork2-opus-or-cost.clj), median of 9 after 5 warm-ups): `ee5ac0bd` 4.8 ms / 7.24 MB,
   `2ff3bd4d` 80.0 ms / 43.66 MB, `dbca76d5` 3.1-4.0 ms / 7.24 MB.

Older defects (pin and `2ff3bd4d` alike), fixed:

5. **A predicate reading a var only the producer group binds** read the consumer's tuple at all-group-vars
   positions: `[:find ?b :where [?a :p/friend ?b] [?b :p/score ?c] [(<= ?c 2)]]` answered `#{[4] [2]}` (the `?a`
   column) for `#{[1] [2]}`; finding `?a` threw. A ref join plus a predicate on the joined entity is an ordinary shape.
6. **An anti-merge's own vars were group outputs**: `[:find ?a :where [?a :p/nums ?x] [(> ?x 1)] (not [?a :p/likes
   ?e])]` NPE; `(not [?c :p/team :t2 ?tx])` with a predicate threw `datom-tx` on nil (current, history, as-of).
7. **Two groups sharing two vars joined on one**: `[?b :p/friend ?c] [?c :p/nums ?b]` and `get-else` binding another
   group's var answered every pair.
8. **`get-else` into a bound var** compared nothing (or yielded its default on a value mismatch), and a group with
   `get-else` plus a card-many merge kept one value of the card-many attribute (`[?c :p/tags ?t] [?c :p/nums ?d]
   [(get-else $ ?c :p/age -1) ?a]` lost `[1 2 "x"]`).
9. **A folded NOT read a var bound outside its group as a wildcard**: `[?x :p/id "c"] [?x :p/score ?s] [?e :p/team
   :t0] (not [?e :p/score ?s])` planner `#{}`, correct `#{[4]}`; same with `:in [?s ...]`.
10. **An or-join exposed its branch-local vars**: `[?b :p/score ?d] [?c :p/likes 1] (or-join [?c] [?c :p/friend ?d]
    [?c :p/likes 3]) [(< ?c ?d)]` NPE; a cartesian component chose a local var as its find (`Query for unknown
    vars: [?d]`).
11. **A set result with duplicate tuples**: `[:find ?c :where [?c :p/tags ?t]]` returned a `QueryResult` (a set) of
    count 67 for 37 entities (count 3 for 2 on the small world); `(set r)` returns it unchanged.
12. **Source-prefixed repeated var** `[$ ?a :p/friend ?a]` was not joined in either engine (the substitution turned the
    vector into a seq, so neither separation saw it).

Still open, filed as tasks (not fixed here, outside a 15-minute fix or a semantics decision):

- `seon.db/planner-throws-where-an-entity-position-holds-a-non-entity-value` (631d747797c5): ill-typed joins
  (keyword or string in an entity position) throw `ClassCastException`/`Bad format for entity-id` in the planner where
  the base answers `#{}`. Every one of the 43 remaining fuzz differences in 7,500 random queries is this class.
- `seon.db/planner-ignores-a-constant-reaching-a-negation` (a4f35e1cde79): a var-free NOT after constant substitution
  is dropped inside a rule (`(nf 2)` answers `#{[1]}` for `#{}`) and throws `Insufficient bindings` at top level.
- `seon.db/a-tuple-binding-repeating-a-variable-is-not-joined` (076e13a25e93): `[(vector 1 2) [?x ?x]]` answers
  `#{[2]}` in both engines.
- `seon.db/a-rule-head-with-required-variables-ignores-its-bound-argument` (ce29fea39d84): `[(fr [?e] ?x) …]` called
  with `[:p/id "b"]` answers every friend in both engines.
- Known and linked, not re-filed: lookup ref used both as entity and as value (`[?e :p/id "a"] [(vector? ?e)]` with
  `?e` a lookup ref: planner `#{}`, base `#{[[:p/id "a"]]}`), task 88c4178179c4; Astra's partial NOT binding,
  `seon.db/partially-bound-negation-disagrees-between-query-engines`; the sparse-id stale plan, 3523ddcf8a1a.

## Claims checked

| claim (dh-fix2) | verdict | evidence |
|---|---|---|
| matrices 0 of 1,548 / 0 of 300 at `2ff3bd4d` | **supported** | Rerun: 0 of 1,548; 0 of 300; edges equal. At `dbca76d5`: 0 / 0 again. |
| 1 lookup by use position (`3cd6d267`) | **supported** on every shape I built | entity, tx and ref-value positions; inside `not`, `or-join`, nested rules; collection inputs; pull-only use. Required-var rule heads index wrongly but are wrong on both engines (task ce29fea39d84). |
| 2 entity-group shared vars (`c757d3d7`, `2ff3bd4d`) | **supported** within one group; **contradicted** for NOTs naming a var bound elsewhere (item 9), `get-else` repeats (item 8) and repeats across groups (item 7) | cases file rows A, B; fuzz. |
| 3 repeated vars, both engines (`470c911a`) | **contradicted**: regressions 1-3, source-prefixed patterns (item 12) | rows C2, C6c, C7. |
| 4 ground `false` on a current db | **supported** | matrix `added-false`; no counterexample. |
| 5 base function binding a bound var | **supported** for the base engine except inside a union (regression 1) | row C15/C16. |
| 7 union estimate (`ad28eadf`) | **contradicted** for a union after its binder (regression 4); **supported** for the q-orjoin spellings | or-cost; q-orjoin bench below. |

## Method

- Fork JVMs only, from `git archive` snapshots of `d9121bbb`, `ee5ac0bd`, each dh-fix2 commit and `2ff3bd4d`, and a
  clone of the fork in my scratchpad for the fixes (the shared submodule tree was not touched). One socket REPL per
  revision for probing.
- [Cases](review-dhfork2-opus-cases.clj): 49 hand-answered shapes (get-else, anti-merges, repeated vars in
  `not`/`or`/rules/source prefixes, lookup positions), on a 4-entity world and the matrix world.
- [Fuzz](review-dhfork2-opus-fuzz.clj): random queries (1-3 patterns with repeats, predicates, `get-else`,
  `not`, `not-join`, `or`, `or-join`, function bindings) over current/history/as-of, planner vs base. Pin: 432
  differences in 1,500; `2ff3bd4d`: 28 in 1,500 (seed 7); `dbca76d5`: 43 in 7,500 (seeds 7, 11, 23, 31, 47), all of
  the ill-typed class above. Each difference was classified against the pin and `ee5ac0bd`.

## Cost: pin `d9121bbb` vs `2ff3bd4d` vs `dbca76d5`

APFS clone of `data/store` (1.0 GB, `cp -c -R`, 32 s; opened in my JVMs only), [dh-fix2-cost.clj](dh-fix2-cost.clj)
unchanged, inputs regenerated from this store (root's last three runs), medians of 9, two JVM runs per version
alternating pin/2ff/new. Answers (hash) identical in every version. Cold = fresh connection, store cache 1.

| shape | cold reads pin / 2ff / new | cold MB pin / 2ff / new | cold CPU ms pin / 2ff / new | warm KB 2ff / new |
|---|---|---|---|---|
| receipts | 70 / 11 / 11 | 25.75 / 4.15 / 4.15-4.16 | 11.5-15.7 / 2.2-3.5 / 3.4-3.6 | 269 / 264-274 |
| analysis | 2,881 / 293 / 293 | 997.9 / 110.4-110.5 / 110.4-110.5 | 362-471 / 48-62 / 51-59 | 7,992-8,110 / 8,029-8,115 |
| next-ordinal | 66 / 9 / 9 | 23.89 / 3.55 / 3.55 | 10.8-13.1 / 2.1-2.8 / 2.5-2.8 | 172 / 169-179 |
| latest-turn | 45 / 44 / 44 | 15.50 / 15.21 / 15.21-15.22 | 8.0-8.7 / 7.9-10.0 / 8.7-9.1 | 225 / 221-229 |
| affected | 624 / 624 / 624 | 210.26 / 210.21-210.25 / 210.21-210.25 | 79-96 / 83-108 / 97-103 | 7,450-7,453 / 7,446-7,455 |

New against `2ff3bd4d`: reads equal, cold bytes within 0.03%, warm bytes within the JVM-to-JVM spread (the two
`2ff3bd4d` runs differ by up to 118 KB on analysis). CPU ranges overlap (load average 15-50). Nothing worse. On this
store the pin's receipts read 70 nodes against 11 (dh-fix2's +1 read finding no longer reproduces with these inputs).
Union ([q-orjoin bench](../q-orjoin/raw-orjoin-bench.clj), inputs 1 / 10 / 142): `dbca76d5` `or` 0.13 / 0.18 / 2.87 MB,
`digest-first` 0.47 / 0.17 / 2.87 MB, equal to `2ff3bd4d`; my first attempt at item 4 (bound estimate only when every
pre-bound var is bound) sent `digest-first` back to 60 MB and was replaced by the per-branch rule before committing.
Astra's edge probe sparse-id fresh query: 85,200 → 87,600 B (+2.8%) per call; not attributed further.

Over-budget numbers that remain (not from this range): cold analysis 110 MB, affected 210 MB for 0 rows, latest-turn
15 MB; existing tasks 6046ef5943f0, ab50a8acaedd, e55e210a7997.

## Verification

- New regressions in `test/datahike/test/query_planner_temporal_test.clj` (9 deftests, every case on current/history
  /as-of × both engines, asserting the hand answer and that the result count equals the answer's) and
  `query_identity_join_test/a-union-after-its-binding-pattern-waits-for-it` (plan order). Run against `2ff3bd4d`
  sources: every one fails (four deftests error, the other five fail 2-18 assertions each).
- 22 focused namespaces at `dbca76d5`: planner 271 tests, 1,686 assertions, 0 failures; `DATAHIKE_QUERY_PLANNER=false`
  the same. Each intermediate commit: six query namespaces green.
- Full `clj-pss` kaocha suite at `dbca76d5`: **939 tests, 5,405 assertions, 0 failures** (3 min 44 s).
- clj-kondo on every changed file: 0 errors, warnings equal to `2ff3bd4d` (19/75/0/27/9/0).
- Not run: CLJS (`execute-card-many-merge`'s cljs branch has the same edit, untested), `clj-hht`, Seon's tests (no Seon
  code changed), default's installed candidate (gitlink not bumped).

## Design check

- **Held:** each defect was fixed where the wrong value is produced: the producer-retention test and the
  single-probe precondition in `execute-plan-direct`; anti vars excluded where group outputs and fused columns are
  derived; `get-else` repeats through the same `separate-uncompared-vars`; the base engine's fresh vars dropped by the
  rewrite that introduced them (`resolve-joined`, the engine's own `limit-rel` idiom); or-join vars at the op and at
  component splitting; the union's bound estimate gated in `op-cost` like `group-start-card` gates a scan's.
- **Wrong first:** the dedupe rule ignored blank `_` vars (caught by `query_fns_test`, folded into its commit before
  pushing); the first union gate sent the q-orjoin `digest-first` spelling back to 60 MB.
- **Awkward:** `logical` now requires `plan` for `compute-rule-sccs` (no cycle; plan is pure). Two groups sharing two
  vars now take the Relation path rather than a two-var probe; that path is correct and these joins are rare.
- What it lets go: the matrices' 0 is no longer the only evidence; the fuzz differential is committed and reusable.

## Seon health seen

`bin/seon status`: default `:faulted`; `seon.batch/owned?` untested hot (7,458,662 calls); over-bound
`seon.fn/analyzed-files` (mean 1.6 s, existing task d0022e8fb20b), `seon.cluster/adopt-entities!` (5dbb7de36bdb),
`seon.cluster.source/publish!` (9665c2ed25f0), `seon.fn/index!` (12037fb1e077 and two more); 7 errored receipts, one
open fault. `(seon.task/create!)` from JVM mode as agent `review-dhfork2-opus` created the four tasks above.

## Commits

Fork (`seantempesta/datahike`, `seon/identity-join-first` and `main`, `2ff3bd4d..dbca76d5`):

| commit | subject | src +/− | test + |
|---|---|---|---|
| `9f8a7e1b` | execute: a projection that drops a card-many value deduplicates | +10/−5 | 36 |
| `09d2f10b` | execute: joined groups keep what a predicate reads and join on every shared var | +8/−2 | 17 |
| `5d7459d9` | plan, logical: a NOT binds nothing, and folds only over its group's vars | +17/−5 | 19 |
| `88494088` | plan, execute: get-else into a bound var joins it; card-many merges keep every value beside it | +25/−13 | 12 |
| `c1dbd83e` | query, logical: a join rewrite's fresh vars stay private; non-recursive rules see repeated args | +33/−10 | 17 |
| `f01c4590` | lower, query: an or-join binds only its join vars | +8/−3 | 6 |
| `dbca76d5` | lower, plan: a union's bound estimate applies once its branches' bindings are bound | +17/−3 | 19 |

Process: every JVM I started (two socket REPLs, matrix, probe, cost, test and kaocha JVMs) has exited; the store
clone, archives and fork clone are removed from my scratchpad after this commit.
