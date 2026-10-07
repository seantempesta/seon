---
title: Datahike fork answer defects after the dual review (dh-fix2)
type: research
status: landed-on-fork
date: 2026-10-07
lane: dh-fix2
---

# dh-fix2 notes (2026-10-07)

Lane `dh-fix2`: the wrong answers and the cost claim both reviews of `d9121bbb..6f3b61f6` raised
([Opus](review-dhfork-opus-notes.md), [Astra](dh-audit-notes.md)), plus root's `or` join item from
[q-orjoin](../q-orjoin/q-orjoin-notes.md). Fork `reference-code/datahike`, branch `seon/identity-join-first`, base
`ee5ac0bd`. Seon's gitlink is **not** bumped (root decides after review). Default needs no reset; it needs a
restart only after root bumps the gitlink.

Which step of the one happy path this serves: every agent turn reads through these queries (context, receipts,
analysis); a wrong join answers the agent wrongly. Every fix sits in its producer (the planner, the merge executor,
the base engine) and adds no cache, path or fallback.

## Broken things still open

- **A lookup ref reaching an entity position through a relation stays a vector** in the planner (function binding,
  a rule argument used both as an entity and as a value, multi-source collection input); the base engine resolves it
  per pattern. Pre-existing; task `seon.db/planner-leaves-a-lookup-ref-unresolved-where-a-relation-binds-it`
  (88c4178179c4). Fixing it means resolving at the executor's probe, not before planning.
- **Budgets still exceeded by existing costs** (not from this range): cold `analysis` 110.6 MB / 293 reads, cold
  `affected` 206 MB / 614 reads for 0 rows, cold `receipts` 19.6 MB / 54 reads, cold `latest-turn` 10.4 MB (27.5 MB
  on a 23,000-turn store, where it reads the whole `:seon.turn/closed-tx` attribute). Tasks
  `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` (6046ef5943f0),
  `seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows` (ab50a8acaedd),
  `seon.db/planner-estimates-load-every-node-of-the-counted-range` (e55e210a7997).
- **Receipts cold reads 53 → 54 against the pin** (+1 node, +0.45% bytes; warm 615-624 KB → 583 KB). Bisected by
  Opus to `5258679c`: the bound unique lookup now runs first, which reads its root-to-leaf path. The pin's order
  (groups first) is the plan that read 678 nodes on Astra's older store; I did not restore it. Root rules whether +1
  read on this store is acceptable for the bound order.
- Rule-call literals that are maps, sets or vectors of other than two elements are still refused by
  `lower/plan-rule-op` ("Bad format for value in pattern"), as `query_rules_test` expects; relaxing it would make the
  planner accept a shape the base engine refuses for non-tuple attributes.

## Defects: cause, fix, test

Each regression test failed on `ee5ac0bd` (fork sources loaded from `git show HEAD:` into my fork JVM) and passes
after; counts are failing assertions before.

| # | defect | cause (at `ee5ac0bd`) | fix | test (fails before) |
|---|---|---|---|---|
| 1 | lookup-shaped rule/input data resolved as entities (`seon.db/rule-arguments-resolve-lookup-shaped-data-as-entities`, 59567e8e05bc) | `query.cljc` `substitute-consts-with-lookup-refs` rule-call branch ran `dbu/entid-strict` on every keyword-headed two-vector (84790323); `resolve-lookup-ref-bindings` resolved every lookup-shaped input value regardless of use | `entity-vars` names vars a clause list uses at an entity, tx or ref-value position; `rule-entity-positions` closes it over rule calls; both run only when lookup-shaped data is present. Other constants, tuples included, are substituted as literals (the var no longer stays unbound) | `query-planner-temporal-test/rule-arguments-preserve-lookup-shaped-data`, 25 |
| 2 | history joins sharing `?added`: 12 rows for 9 (`seon.db/history-joins-ignore-shared-added-flags`, 411bc7407db3) | `execute.cljc` merges compared only `check-v?`/`check-tx?` against the scan datom; the added flag was never compared. Same gap: a var two merges share (wrong on the **current** db too: `[?e :x ?x] [?e :b ?v] [?e :c ?v]` 2 rows for 0), and a folded NOT naming a merge's var (`(not [?e :c ?v])` excluded every entity) | temporal `merge-added-filter` takes `:scan-added` for an added var shared with the scan, compared per scan datom (at replay in the history fast path, like v/tx); `plan/separate-uncompared-vars` gives every other repeat a fresh var plus an attached `[(= ?v ?fresh)]`; an anti-merge naming a group var the scan does not hold at that position stays a standalone NOT (`:unfolded-antis`) | `shared-vars-in-an-entity-group-are-joined`, 9 + 3 |
| 3 | a var repeated in one clause not joined to itself, both engines (5f8f03a38dd9) | base: `var-mapping` keeps one index per var; planner: the same in scans, and the recursive-rule projection never compares repeated call args (`invariant-head-positions` already refuses them) | `analyze/separate-repeated-vars` / `separate-repeated-call-args`: the later position takes a fresh var plus an equality predicate, in `logical/build-logical-plan` and in the base engine's pattern branch | `a-repeated-variable-joins-a-clause-with-itself`, 9 |
| 4 | ground `false` added dropped in an entity-group join on the current db (35d5a750f62b) | `execute-group-direct`: `merge-added-filter` exists only for temporal dbs, so a current-db merge ignored its ground flag | on a non-temporal db a merge asking for a retraction matches nothing; such an anti-merge is dropped (excludes nothing) | `a-ground-retraction-flag-matches-nothing-on-a-current-db`, 4 |
| 5 | base engine function binding to a bound var rebinds (c1184aa274fd) | `query.cljc` `bind-by-fn`: `prod-rel` appended the output as a second column of a var already in the production | an output var the inputs' relation binds gets a fresh var and an equality predicate, resolved through `resolve-context` on the caller's context | `a-function-binding-a-bound-variable-joins-it`, 1 |
| 7 | `or` joined to a pattern scans the joined attribute (`seon.db/datahike-joins-an-or-relation-by-scanning-the-joined-attribute`, f5b587ddd04f) | `lower/plan-or-op` summed each branch's `:estimated-card` (attribute extent, 60,000) ignoring the bound `?sym`, so the digest pattern ran first over its whole attribute | a pattern branch contributes its bound-aware `:scan-card`; the union runs first, the join probes per `?e` | `query-identity-join-test/a-join-after-an-or-reads-the-nodes-of-its-answer`: 126 reads before, ≤ 16 after |

Defect 2's earlier repair (Astra) split groups and raised allocation 55.7%. Same probe
([dh-audit-temporal-cost.clj](dh-audit-temporal-cost.clj), median of 21 after 200 warm-ups, my JVM):
`ee5ac0bd` 83,080 B / 0.11-0.12 ms (12 rows, wrong); this fix 82,584 B / 0.12-0.18 ms (9 rows). A first version of
mine (fresh var + attached predicate for the scan-shared `?added` too) cost 103,040 B (+24%) and was replaced by the
executor comparison before committing.

### 6. The latest-turn cost regression: not reproduced as work

Astra saw latest-turn cold 56-64 → 69-99 ms with equal reads (530/528) and bytes (174.3 / 173.5-174.6 MB). The plan
changed at `5258679c` (the `:seon.agent/id` lookup now runs before the `?turn` group, which still scans
`:seon.turn/closed-tx`), but on equal reads and bytes the wall time is load: the machine's load average was 27-45
during my runs (`uptime`), and a raw `d/q` at `ee5ac0bd` once measured 235-365 ms against 26-37 ms on a rerun of the
same JVM script. I measured thread CPU time instead:

- default's clone (root has 36 turns): cold pin 5.0-6.1 / HEAD 5.6-6.1 / new 4.4-4.6 ms CPU; 31 / 30 / 30 reads;
  10.71 / 10.40 / 10.40 MB.
- a synthetic store of 23,000 turns, 3,000 of them root's ([dh-fix2-latest-turn.clj](dh-fix2-latest-turn.clj),
  median of 11, two rounds, pin/HEAD/new/pin/new): 50 reads and 27.49-27.51 MB cold in every version; CPU pin
  17.9-20.3, HEAD 19.2-20.2, new 17.4-21.9 ms; warm 3.80 MB and 5.2-5.9 ms in all.

So no commit in the range makes latest-turn do more work; nothing was changed for it. Its real cost is linear in all
turns (existing task 6046ef5943f0).

## Answer matrices

- Opus's [probe](review-dhfork-opus-probe.clj), 86 shapes × current/history/as-of × sizes 40 and 1,500 × three
  plan-cache modes: `ee5ac0bd` **120** mismatches; final fork `2ff3bd4d` **0 of 1,548**. Since the base engine's
  defects 3 and 5 are fixed too, planner-vs-base equals planner-vs-correct on these shapes (`reach-self` and
  `tags-shared-v` were base-wrong before).
- Astra's [probe](dh-audit-probe.clj): `ee5ac0bd` 18 differences; final **0 of 300**. [Edges](dh-audit-edges.clj):
  all equal; the sparse-id stale plan is unchanged (existing task 3523ddcf8a1a).
- [Repros](dh-fix2-repro.clj) for every shape above, including the still-open lookup-through-relation shapes.

## Cost: pin `d9121bbb` vs HEAD `ee5ac0bd` vs new `2ff3bd4d`

APFS clone of `data/store` (957 MB, `cp -c -R`, default idle of turns but other lanes writing; it opened cleanly),
my own fork JVMs only, run alternating pin/HEAD/new twice. [Driver](dh-fix2-cost.clj) = dh-fix's
[bench.clj](bench.clj) with thread CPU time; cold = fresh connection, store cache 1, plan cached; medians of 9;
ranges over the two runs. Inputs fixed in `cost-inputs.edn` (root agent; last three runs; `seon.db`, `seon.turn`).
Answers (hash) identical in every version.

| shape | reads cold pin / HEAD / new | cold MB pin / HEAD / new | cold CPU ms pin / HEAD / new | warm KB pin / HEAD / new | warm CPU ms |
|---|---|---|---|---|---|
| receipts | 53 / 54 / 54 | 19.50 / 19.59 / 19.59-19.60 | 9.2-10.2 / 9.1-10.2 / 7.8-8.2 | 615-624 / 583 / 583 | 0.7-0.9 / 0.9-1.0 / 0.8 |
| analysis | 2,872 / 293 / 293 | 994.2 / 110.6 / 110.6-110.7 | 356-370 / 44-47 / 39-40 | 12,368-12,376 / 8,114 / 8,110-8,114 | 10.6-11.8 / 5.2-5.7 / 4.4-4.7 |
| next-ordinal | 51 / 9 / 9 | 18.67-18.68 / 3.43 / 3.42-3.43 | 7.6-8.8 / 1.9-2.1 / 1.7 | 315-324 / 172 / 172 | 0.6 / 0.3-0.4 / 0.3-0.4 |
| latest-turn | 31 / 30 / 30 | 10.70-10.71 / 10.40 / 10.40-10.41 | 5.0-6.1 / 5.6-6.1 / 4.4-4.6 | 164-173 / 168 / 168 | 0.4 / 0.4-0.5 / 0.3-0.4 |
| affected | 614 / 614 / 614 | 206.41 / 206.45-206.48 / 206.40-206.58 | 91-97 / 87-93 / 64-73 | 7,434-7,443 / 7,437-7,439 / 7,438-7,440 | 7.7-8.2 / 7.5 / 5.9-6.0 |

New against HEAD: reads equal, bytes equal within 0.1%, CPU equal or lower on every shape. New against the pin: the
only worse number is receipts (+1 read, +0.45% cold bytes; see Broken things). The CPU decreases are not claimed as
improvements: the load average was 27-45.

`or` join ([raw-orjoin-bench.clj](../q-orjoin/raw-orjoin-bench.clj), memory store, 60,000 digests, inputs 1 / 10 /
142): pin 27-46 ms, 60.6-61.5 MB; HEAD 26-37 ms, 57.7-60.5 MB; new **1.9 / 3.2 / 21.3 ms, 0.14 / 0.18 / 2.87 MB**
(proportional to the inputs). The other three spellings (`or-join`, digest pattern first) move the same way: 0.47 /
0.18 / 2.86 MB.

## Verification

- Focused fork namespaces (20: query, rules, lookup-refs, identity-join, planner, planner-temporal, predicate
  pushdown, or, not, fns, get-else, cartesian, find-specs, IR, cljs-recursive-rule, aggregates, input-pull, interop,
  pull, stats) in my fork JVM: **184 tests, 957 assertions, 0 failures**, planner and with `*disable-planner*`
  both. Each intermediate fork commit's archive (`3cd6d267`, `c757d3d7`) ran the temporal, rules, lookup-refs and
  identity-join namespaces green in a fresh JVM.
- Full `clj-pss` kaocha suite from a snapshot of `ad28eadf` (`git ls-files`, 6 min 38 s): **929 tests, 5,124
  assertions, 0 failures** (dh-fix's HEAD count was 923 / 5,046). `2ff3bd4d` came after it and touches only the
  anti-merge folding, covered by the focused run above.
- clj-kondo on every changed fork file: 0 errors, warnings equal to `ee5ac0bd` (131 on the same file set).
- Not run: CLJS (`bb node-cljs-test`), the `clj-hht` profile, Seon's own tests (no Seon code changed), default's
  installed candidate (gitlink not bumped).

## Design check

- **Held:** each fix is at its producer. Rule/input resolution moved from "shape of the value" to "use of the var",
  which is the base engine's rule; entity-group joins now compare every shared var, by the executor where it already
  compares (v, tx, now added) and by an ordinary attached predicate elsewhere; repeated vars become an explicit join
  in both engines through one helper; the union estimate uses the bound-aware number the branch plan already had.
- **Wrong in the first attempt:** treating a scan-shared `?added` like other repeats (fresh var + predicate) was
  correct but +24% allocation; comparing it in the executor costs nothing. Relaxing `plan-rule-op`'s two-element
  vector check broke a test that pins the base engine's refusal; reverted.
- **Awkward:** a lookup ref whose var is used both as an entity and as a value is resolved (the entity use wins), as
  before this lane; both choices are wrong for the value use until the executor resolves at its probe (task
  88c4178179c4). Fresh vars use `gensym`, so plans are not textually reproducible; cache keys are computed before
  planning and are unaffected.
- What it lets go: nothing in Seon yet; after the bump, `seon.db/query-index-patterns`' `or` work (q-orjoin) no longer
  sits beside a 21 MB Datahike scan.

## Seon health seen

`bin/seon status` at start: default PID 83524, `:degraded`, 6 over-bound warnings. Later `runtime_status`:
`:faulted`, `seon.db/call-with-custody` max 173,981 ms (task 5a8b119ea14a), `seon.batch/within` and
`seon.batch/evaluate-batch` max 174 s (tasks 53758c92fcb5, 85796bb75795), `seon.fn/index!` max 6.3 s (tasks
12037fb1e077, 84fdc131a599, 8b62adf64442), `seon.fn/analyzed-files` mean 1.9 s max 4.0 s with no task: filed
`seon.fn/analyzed-files-takes-two-seconds-per-call` (d0022e8fb20b). `(my.task/open (seon.db/db))` from my SCI agent
answered `()` (the queue ignores created tasks: existing finding in persist-tasks' notes); I searched task names on
default's connection instead. My first `seon.task/create!` omitted `:seon.task/kind` and was refused (my mistake).

## Commits

Fork (`seantempesta/datahike`, `seon/identity-join-first`, then `main` fast-forwarded and pushed):

| commit | subject | src +/− | test + |
|---|---|---|---|
| `3cd6d267` | lookup-shaped data names an entity only where it is used as one | query +66/−22 | 39 |
| `c757d3d7` | entity-group merges join every variable they share | execute, plan, lower +64/−13 | 50 |
| `470c911a` | a variable repeated in one clause, or bound twice, is a join | query, analyze, logical +62/−5 | 38 |
| `ad28eadf` | a union is estimated by what its branches read given its bindings | lower +4/−1 | 17 |
| `2ff3bd4d` | a NOT naming a merge's var stays a standalone negation | plan, lower +20/−6 | 2 |

Process: one fork socket-REPL JVM on the working tree (port 5591), separate JVMs for matrices, cost and the suite,
all from `git archive` snapshots or the clean working tree; every JVM exited. The store clone and archives live in my
scratchpad and are deleted after measuring.
