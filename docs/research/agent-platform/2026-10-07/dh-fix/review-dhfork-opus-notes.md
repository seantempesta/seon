---
title: Review of the Datahike fork change d9121bbb..6f3b61f6 (Opus)
type: research
status: reviewed
date: 2026-10-07
lane: review-dhfork-opus
---

# review-dhfork-opus notes (2026-10-07)

Independent review of fork `reference-code/datahike`, branch `seon/identity-join-first`, `d9121bbb..6f3b61f6`
(nine commits: `5258679c`, `49e681db`, `f3d01c16`, `eb404fa1`, `9e229d31`, `84790323`, `77c1228e`, `5fcfc6a7`,
`6f3b61f6`), per [review-dh-fork-spec.txt](review-dh-fork-spec.txt). Astra reviews the same range in parallel.
Lane `dh-audit` holds `query.cljc`, `execute.cljc`, `logical.cljc`, `CHANGELOG.md` and
`query_planner_temporal_test.clj` with uncommitted edits, so **this lane made no fork commits**: every fix below goes
to that owner or root. Seon's gitlink is not bumped. No RESET NEEDED from this lane; RESTART NEEDED after root bumps
the gitlink (default loads the fork only at boot).

## Broken things first

1. **Regression in `84790323`: a tuple-valued rule argument now throws.** Rule-call substitution resolves every
   keyword-headed two-vector with `dbu/entid-strict`. With `:p/pair` a `[:db.type/keyword :db.type/long]` tuple and
   rules `[(has-pair ?e ?p) [?e :p/pair ?p]]`:
   - `[:find ?e :in $ % :where (has-pair ?e [:k1 1])]`: at `d9121bbb` the planner and the base engine answer
     `#{[1]}`; at `6f3b61f6` the planner throws `Lookup ref attribute should be marked as :db/unique: [:k1 1]`.
   - The same value passed as an input `?p`: the pin's planner answered every entity (the input-left-unbound class
     the commit meant to fix), HEAD throws. Base engine `#{[1]}` in both.
   - A real lookup ref compared as a value, `(same ?e [:p/id "a"])` with `[(same ?x ?y) [(= ?x ?y)]]`: planner
     `#{[1]}`, base `#{}` (the pin's planner matched base).
   Same class as dh-audit's task `seon.db/rule-arguments-resolve-lookup-shaped-data-as-entities` (linked, not
   duplicated); its fix ("resolve only at a database pattern's entity or ref position") must also cover these three
   shapes. Smallest correct alternative I would weigh: substitute every input constant into the call as the literal
   it is (vectors included; this fixes the unbound-input class for tuples too) and resolve a lookup ref only where
   the rule body uses that position as an entity or ref value. Resolving "only when the first element is a unique
   attribute" fixes the tuple error but keeps the `same` divergence; not recommended.
2. **Pre-existing, both engines wrong (not from this range):** a variable repeated within one clause is not joined to
   itself. Entities 2→1, 3→2, 4→4 by ref `:p/friend`: `[:find ?a :where [?a :p/friend ?a]]` answers planner
   `#{[2] [3] [4]}`, base `#{[1] [2] [4]}`, correct `#{[4]}`. Through rules too: `(edge ?a ?a)` `#{[1] [2] [4]}` on
   both; `(reach ?a ?a)` on an acyclic chain answers every entity instead of none. The new
   `invariant-head-positions` correctly refuses a repeated call arg (`once?`), but the executor's final projection
   (`output-vars` by `.indexOf`) never checks equal positions. Task `seon.db/a-repeated-query-variable-is-not-joined-to-itself`
   (5f8f03a38dd9).
3. **Pre-existing planner bug:** a ground `false` added position is dropped in an entity group on a current db.
   Entity 1 `:t/tags ["tag1" "x"] :t/n 0`, retract `"x"`:
   `[:find ?e ?t :where [?e :t/tags ?t ?tx false] [?e :t/n 0]]` → planner `#{[1 "tag1"]}`, base `#{}` (the single
   pattern alone is right). Task `seon.db/a-ground-added-false-is-ignored-on-a-current-db-join` (35d5a750f62b).
4. **Pre-existing, history:** a shared fifth-position `?added` var is not compared in fused history merges (151 vs 140
   rows in my matrix). Already dh-audit's task `seon.db/history-joins-ignore-shared-added-flags`; their uncommitted
   `logical.cljc` change targets it. `77c1228e` fixes v and tx only.
5. **Base engine wrong, planner right (pre-existing):** a function binding to an already-bound var rebinds instead of
   joining: `[?e :t/tags ?v] [?e :t/n ?n] [(str "tag" ?n) ?v]` base answers `[2 "tag7"]`, a value stored nowhere.
   Task `seon.db/base-engine-function-binding-overwrites-a-bound-variable` (c1184aa274fd). The planner falls back to
   this engine for nested temporal wrappers.
6. **Lint config hides real findings (`9e229d31`).** `:unresolved-symbol {:exclude [(macro)]}` silences every
   unresolved symbol inside the macro call. Injecting `souce` into `resolve-pattern-lookup-refs`'
   `with-destructured-vector` body and `cur-dd` into a `sorted-merge-inner-loop` call: committed config 0 errors;
   naming the locals (`(datahike.tools/with-destructured-vector [e a v tx added])`,
   `(datahike.query.execute/sorted-merge-inner-loop [first-attr cur-d si cur-a target-a])`) reports both and nothing
   else. dh-audit's uncommitted diff makes exactly this change; it should land.

## Claims

| claim | verdict | evidence |
|---|---|---|
| 1 answers equal the base engine | **contradicted** for lookup-shaped rule args (item 1, a regression); **supported** for every other changed path | Matrix ([probe](review-dhfork-opus-probe.clj)): 86 shapes × current/history/as-of × sizes 40 and 1,500 × three plan-cache modes (fresh per query; shared small→large; shared large→small) = 1,548 comparisons. `d9121bbb`: 361 mismatches; `6f3b61f6`: 120, all in seven shapes: the four lookup/tuple rule-arg shapes (item 1, two of them new), `reach-self` (item 2, also on the pin), `added-false` (item 3) and `added-shared` (item 4), `tags-shared-v` (item 5, base wrong). Fixed by the range: recursive rules (`reach` with outer, collection, lookup-ref, rule-first, twice, inside `or`, `swapped`, `wrapped`), `guarded`, `mixed`, shared-tx history joins, `pred-on-join`, history `input-compare`. Left-linear rules time out (>20 s at 40 entities) in the base engine, so `anc`, `swapped`, `mixed`, `guarded`, `labelled` and `mutual` were checked against right-linear equivalents run on the base engine: all equal at HEAD. The plan-cache modes produce no mismatch the fresh mode lacks, so the size-class key causes no cross-world reuse error. |
| 1a size-class key (`5fcfc6a7`) | **supported** | Key-only change; `-max-eid` is O(1) on DB and delegates for filtered/as-of/history/since (`db.cljc` impls); the planner keys on `planner-origin-db`, always a DB. Costs: the LRU stays 100 entries, keyed with substituted constants, and small test dbs no longer share default's plans (correct, but they compete for the same 100 slots; existing task `seon.db/planner-estimates-load-every-node-of-the-counted-range` covers the per-constant re-plan). dh-audit's docstring correction (a high-water mark, not a population) is right. |
| 1b rule head vars (`84790323`) | **supported** except item 1 | `invariant-head-positions` is sound: a position passed through unchanged by every recursive call takes its values from base tuples, so restricting it by an outer relation is magic-set style; it is disabled for repeated call args and multi-rule SCCs. Probed: left/right-linear, swapped spellings, three-head label, mixed passing/non-passing branches, head var in a body predicate, mutual recursion, nesting inside a non-recursive rule, `not`/`or` around the call, outer relations binding extra call args. |
| 1c temporal merge (`77c1228e`, `49e681db`) | **supported** for v and tx; shared `?added` still wrong (item 4, pre-existing) | `tx-shared` (history) fixed; `card-many-shared-v`, `two-card-many-shared-v`, `tx-var`, `added-var`, `added-false` (history) equal base. |
| 2 thirteen stale tests | **supported** | Each traced to its commit: `407e9328` (CHANGELOG: without a db, bare forward attributes widen to `:all`; every widened test pulls a bare attribute; test text dates from `41764938`, before it); `cc2b2bc7` (`:keep-history?` in `create-time-fixed-config-keys`; I checked the dropped coverage: `(d/connect (assoc cfg :keep-history? false))` refuses `:create-time-fixed-index-config-mismatch` and the reference count stays 1); `591c825d` (`(dissoc :data :trace)`); `41c79c1a` (complete collections). None hides a bug. The seven touched/new test namespaces at HEAD: 128 tests, 835 assertions, 0 failures. |
| 3 cost | **supported**, one node worse on receipts | Below. |
| 4 lint | **contradicted** | Item 6. The two value edits are equivalent: `'Exception` resolves to `java.lang.Exception` in the expansion namespace; `pattern-probe-set` always returns `:field`, so `(int (or (:field probe) 0))` equals the old branch. |

## Cost on default's store clone, `d9121bbb` → `6f3b61f6`

APFS clone of `data/store` (890 MB) opened in my own fork JVMs, never default's. The first clone, taken while default
was writing, failed with `Node not found in storage` (a root copied after its nodes; a copy race, not a store
defect); the second clone opened cleanly. [Bench](review-dhfork-opus-bench.clj) = dh-fix's `bench.clj` (cold: fresh
connection, store cache 1, plan cached; warm: repeat) plus `plan-cold` (empty plan cache). Two JVM runs per version,
alternating base/head/base/head, round 2 of each, medians of 5.

| shape | reads cold base → head | bytes cold | ms cold | warm ms / bytes | plan-cold reads / bytes |
|---|---|---|---|---|---|
| receipts | 41 → 42 | 15.57-15.59 → 15.62 MB | 5.1-5.3 → 4.6-4.7 | 0.4 / 485-494 → 469 KB | 45 → 44 / 17.2 → 16.5 MB |
| analysis | 2,872 → 293 | 995 → 110.5 MB | 266-297 → 31.4 | 9.4-10.4 → 3.3 / 12.4 → 8.1 MB | 2,875 → 295 / 996 → 111 MB |
| next-ordinal | 40 → 9 | 15.0 → 3.5 MB | 4.4 → 1.2 | 0.3 → 0.2 / 268-277 → 168 KB | 44 → 10 |
| latest-turn | 18 → 17 | 6.72 → 6.43 MB | 2.4 → 2.3-2.4 | 0.2 / 120-129 → 119 KB | 20 → 19 |
| affected (rule) | 614 → 614 | 206.4-206.5 → 206.2 MB | 53-54 → 54-55 | 5.0-5.3 → 5.3 / 7.44 → 7.43 MB | 617 → 617 |

Answers (hash) identical. The one worse number: receipts cold reads 41 → 42 (+45 KB). Bisected: it appears at
`5258679c` (lookup first instead of the entity groups first; 42 at `5258679c`, `49e681db`, `eb404fa1`, HEAD), not in
the dh-fix commits; plan-cold for the same query improves by one read and 0.7 MB. `affected` ms overlap within noise.
Not a blocker in my view; root decides against "nothing worse". Note `d/explain` still prints the bound unique
`:seon.turn/id` lookup at `est. 26584 rows`, and both entity groups drive from `:eval/source` / `:eval/at` AEVT scans,
not from `:eval/run` as dh-planner's notes say; the reads show probe-driven seeks keep it cheap, but the explain text
disagrees with the claim.

## Design check

- The review's question was whether each change sits at its producer and is equivalent elsewhere. Held for
  `5fcfc6a7`, `77c1228e` and the invariant-head part of `84790323`. Not held for the lookup-ref half of `84790323`:
  it decides entity-ness from the value's shape at the call, before knowing the position's use; the base engine
  decides by position. That is the design error behind item 1.
- I fixed nothing in the fork: every defect lives in files dh-audit holds with uncommitted edits, and items 1, 4 and 6
  are already in its diff or tasks. Items 2, 3 and 5 are pre-existing, outside the reviewed range, and filed.
- Which step of the one happy path this serves: every agent turn reads through these queries; the review gates the
  gitlink bump that takes the analysis query from 995 MB / 2,872 reads to 110 MB / 293 cold.

## Seon health seen

`runtime_status` (default): `:degraded`, 11 over-bound warnings: `seon.schema/call-with-projection-state` mean 78 s
(max 3,944 s), `seon.batch/evaluate-batch` mean 1.3 s (max 95 s), `seon.sci.eval/acquire-program!` mean 1.2 s, and
`seon.turn/code-currency` mean 6.0 s (max 41.9 s) with **no linked task**, heap used 7.05 GB of 11 GB. Not this lane's
slice; `seon.turn/code-currency` needs a task from its owner. `(doc seon.task/create!)` repeats each `:map` entry's
schema twice (1,628 tokens), already reported by dh-fix.

## Process

Probes ran only in my own fork JVMs from `git archive` snapshots (`d9121bbb`, `5258679c`, `49e681db`, `eb404fa1`,
`6f3b61f6`) with the fork's `target/classes`; every JVM exited (`System/exit`), none left running. The store clone
and snapshots were deleted after measuring. Tasks created on default as agent `review-dhfork-opus`: 5f8f03a38dd9,
35d5a750f62b, c1184aa274fd. Repros: [minimal](review-dhfork-opus-minimal.clj).
