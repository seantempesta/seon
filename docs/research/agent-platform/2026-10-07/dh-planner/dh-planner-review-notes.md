---
title: Datahike planner review
type: research
status: reviewed
date: 2026-10-07
lane: dh-planner-review
---

# Datahike planner review — dh-planner-review

Review target: fork `5258679ccb7baa584be6554d25dd880a40fae8a7`, parent `d9121bbbad`, upstream
`314d4a4d5b6f0d91b51a58cdbde5052126aa1623` (#974). This review used separate JVMs and Git archives,
never loaded fork code into default, never made a worktree, and made no model/provider calls.

## Findings and limits first

1. **ANSWERS UNCHANGED IS FALSE.** The first 126 differential probes matched, but a targeted scalar
   identity join with four attributes and an attached predicate returns five-column internal tuples
   on 5258679c instead of the parent's correct two-column answer. Exact counterexample below. The
   owner's later `49e681db` repairs this projection path and is preserved beneath this lane's commit.
2. **Both revisions have incorrect history answers and a recursive-rule exception.** These were reproduced
   independently of the reported suite failures. A fix for symbolic predicate bounds is included in this lane;
   the more general recursive answer defect remains. A history fix was rejected by the source-edit hook; a later owner commit independently fixes
   scan ordering. The shared-value buffer reuse defect remains pending, with the proposed patch below.
3. **Stale plans can remain expensive after growth.** At 50,000 added entities the target's cached plan used
   3,948,168 allocated bytes versus 121,960 with a fresh plan for the same 100 answers, a 32.4x difference.
   This also happens on the parent. Retaining estimates did not introduce this demonstrated failure.
4. **Identity-join execution reads improved and did not grow across the measured sizes:** parent 152 → 593,
   target 15 → 8, for 2,000 → 8,000 runs. Tree seeks still have tree-height cost; the claim is not a theorem
   of constant node reads for arbitrarily large stores or arbitrarily large input collections.
5. **The reported 13 full-suite reds and five focused-suite reds were not rerun.** The final assignment
   explicitly reserves all test runs to root. No test runner was started by this lane. The five base-engine
   disagreements in this review's matrix are NOT evidence identifying those five reported focused-suite reds.

## Installed system and foreign boundary

Read lane-rules first, then the Datahike, data-oriented-clojure, REPL and testing skills, the original lane
notes, the complete target diff and upstream patch, the touched planner/lowerer/estimator, their execution
and cache callers, and the persistent-set count/slice adapter.

Initial `bin/seon status` and MCP `runtime_status`: default PID 12491, faulted, 16 open faults, 174 errored
receipts, 43 unknown tests; pull-call and pull-logical regressions, cache discipline warning, MCP mean
1,152,689 us / max 4,257 ms. Heap used 1,539,720,048 bytes, live 1,237,730,160; ready 16,659 ms. These are
existing system findings, not measurements of the fork candidate. The known shown-failure task was
`7f5dfb6ad7a2`. Runtime-status presentation elided fields (roughly 800 estimated tokens shown); the shell
status supplied the full values. The lane SCI probe in `scratch.dh-planner-review`, `(+ 1 2)`, returned 3,
9 ms, result `result/e04da9d5c6471`.

During review MCP once reported no live default JVM. This lane did not restart it. Later status showed
PID 21582, start `2026-10-07T07:05:55.585Z`, state unknown, ready 26,080 ms, live heap 841,121,128 bytes.
Task recording resumed successfully. Other lanes' dirty Seon sources/configuration/styles and the
untracked original dh-planner notes were not edited, staged or restored. The gitlink was not staged.

**Source-edit hook boundary:** an attempted edit of the history cursor owner was refused for six existing
error-level findings in `execute.cljc`: unresolved macro locals `first-attr`, `cur-d`, `si`, `cur-a`,
`target-a` near lines 1198–1201, and an inferred nil/number mismatch in `execute-temporal-group-rel`
near line 3146. A first attempt to put the predicate regression in `query_rules_test.cljc` was refused
for the existing `deftest-async`-introduced `test-rule-arguments` symbol. That file was left unchanged;
the regression uses a small ordinary JVM test namespace instead. No hook or lint check was disabled.
The history patch was not installed by another route. Task:
`seon.fn.analyzer/fork-source-lint-misreads-local-macro-bindings` (`5213fa25ae98`).

## Answers: evidence and counterexamples

[The differential probe](dh-planner-review-probe.clj) builds its database through Datahike's ordinary
`db/empty-db` and `d/db-with` entrances. It checks 42 queries against both planned and base execution
on current, history and as-of values, in each revision. Query-result caching is disabled, and each
planned query starts with an empty plan cache in its isolated JVM.

Coverage: unique identity and unique value attributes; literal, scalar-like substituted constants,
collection and relation bindings; empty collections and absent constants; lookup refs in collections;
indexed and non-indexed attributes; cardinality-many refs; predicates; aggregates; pull in find;
not, not-join, or, or-join; ordinary and recursive rules; disconnected groups; transaction/added
positions; and all 24 clause permutations of a four-pattern identity/ref/value join. Not exhaustively
covered: attribute-ref storage mode, every recursive/boolean nesting, CLJS, >16-group greedy plans,
all possible collection cardinalities, or production Seon's 770k-datom clone.

The complete parent and target result files were byte-identical. Neither equality of these files nor
agreement with the base engine establishes universal correctness: both engines can share defects.

The unique estimate rule is correctly **per input value**, not one entity for a whole collection:
`estimate-pattern-with-bindings` (target estimate.cljc:278–282) returns `max(1,min(v-card,base))`.
Collection/relation seeds are shape estimates (normally 100), not actual argument cardinalities.
Lookup refs are resolved by query input handling/execution; an absent value still gets a positive
estimate and an empty actual answer. Cardinality estimates never impose a result limit here.

**History caveat:** uniqueness is a current-state property. The probe retracts entity 1's unique
`:u/id 1` and assigns that value to entity 81. History can legitimately match both entities.
Temporal planning uses the origin DB (`query.cljc:4518`), so its current counts and uniqueness
heuristics are not historical upper bounds. Both revisions nevertheless agreed in every matrix case.

### Counterexample to parent/target equivalence

On the file probe's 2,000-run store, this exact query changes its result:

```clojure
[:find ?x ?o :in $ ?id
 :where [?run :turn/id ?id]
        [?x :eval/run ?run]
        [?x :eval/ordinal ?o]
        [?x :eval/source ?source]
        [?x :eval/shown ?shown]
        [(> ?o 0)]]
;; input: "run-12"
;; d9121bbb and base engine: #{[61 2] [60 1]}
;; 5258679c: #{[1 58 "1" 60 "(+ 1)"] [2 58 "2" 61 "(+ 2)"]}
```

This was run on pristine Git archives of both revisions. The wide fused group can lead on the
parent; the corrected cost model makes the identity lookup lead and exposes a consumer-projection
bug. Internal fields retained for attached predicates escape into the answer. This is a correctness
regression caused by reaching an existing executor defect through a different plan. The original
claim that ordering/estimates can never change answers assumes a correct executor and is disproven.
The edge probe includes this case plus a smaller two-pattern scalar case (already wrong on BOTH
revisions) and a collection-input control (correct on both).

While review was running, origin advanced to `49e681db`. Its multi-group `execute-plan-direct`
change projects attached-predicate consumer tuples to `emit-vars` when no later combine/post-op
needs the wide layout. This lane keeps that independent owner fix. The regression to retain in
root's central run is the new wide-group shape in addition to its existing `:with-predicate` row.

### Concrete failures present in parent and target

- Recursive rule `[(path ?e ?x) [?e :u/ref ?y] [(< ?e ?y)] [(< ?y 8)] (path ?y ?x)]`
  throws `ClassCastException: Symbol cannot be cast to Number` under the planner, in all three worlds.
  The current base engine returns `#{[2 8] [81 3] [2 11] [2 7] [2 10] [2 6]}`.
- The history predicate case returns `#{[3 79] [1 80] [2 78] [1 77] [3 81]}` instead of also
  including `[2 1]` and `[3 2]`.
- The history-versions case returns
  `#{[1 536870914 false 1] [1 536870913 true 1] [81 536870914 true 3]}`,
  omitting `[2 536870913 true 2]`, `[2 536870913 true 8]`, `[3 536870913 true 3]`.

All input data and exact queries are in the committed differential probe. The original unbounded
80-node cyclic recursion probe was stopped after about 60 s; a thread dump placed it in the BASE
engine's `solve-rule`/`hash-join`. The final bounded rule above avoids that probe-design blowup.
Initial scratch schema/read-delimiter mistakes were corrected before the recorded differential run;
they were author errors, not dependency findings.

### Predicate fix (BEHAVIOR CHANGE)

`analyze/pushable-pred?` treated membership in `bound-vars` as if it supplied a literal value, storing
that variable SYMBOL in `:const-val`. An index range cannot use that symbol as the numeric value
of the runtime binding. Removing those two branches leaves variable-to-variable comparisons as
ordinary predicate ops. Literal pushdown is unchanged. No new evaluator, cache, fallback engine or
restriction was added. This deletes 12 net source lines.

[The small edge probe](dh-planner-review-edge-probe.clj) makes a four-node ref cycle and runs both
`(< ?lo ?z)` and `(> ?z ?lo)` with `:in $ [?lo ...]`, value `[1]`, plus `(< ?z 4)`.
Both parent executions throw; after the fix both return exactly `#{[1] [2]}`, as the base engine does.
Regression: `datahike.test.query-predicate-pushdown-test/bound-range-predicates-preserve-answers`.

Removing the exception exposes a **separate recursive answer defect**: the larger bounded-rule
probe's planned current result becomes `#{[81 3] [2 6]}`, still short of the six correct rows above.
Do not call the general recursive-rule task resolved. Task:
`seon.db/recursive-rule-range-predicates-throw` (`399ed5551ac4`), with this note as continuing evidence.

### History owner diagnosis, owner landing, and pending buffer patch

`history-versions!` (execute.cljc:1434) advances two ForwardCursors only forward.
`execute-temporal-merge-fast` caches their matched versions per entity and explicitly assumes the
scan emits ascending entity ids. `fast-eligible?` does not establish that assumption. A value-driven
probe can visit entity 81 before entity 2. The setup also clears the probe-set after it builds those
seeks, allowing the nominally “probe-less” fast path to run.

There is a second, independently reproduced buffer error: with entity 1 carrying cardinality-many
`:item/a [1 2]` and `:item/b [1 4 5]`, history query
`[:find ?v :where [?e :item/a ?v] [?e :item/b ?v]]` returns `#{[1] [2]}` instead of `#{[1]}`.
The buffer matched the first scan version and is incorrectly replayed for the second shared-value
comparison. This is also on both revisions; see the edge probe.

The smallest proposed repair preserves the existing fast path for its actual preconditions and
routes the other shapes through the existing general merge owner. It needs these changes:

```diff
@@ fast-eligible? temporal-ctx destructuring
+ ^objects merge-check-scan-v (aget tc 6)
+ ^objects merge-check-scan-tx (aget tc 7)
@@ eligibility conjunction
+ (not (aget merge-check-scan-v 0))
+ (not (aget merge-check-scan-tx 0))
@@ execute-group-direct temporal-cursors construction, after temporal-eavt-pss
+ (#{:eavt :aevt} index)
+ (not (and probe-set (== 2 (int probe-datom-field))))
```

This lane's patch was refused before modification by the hook described above. Before push,
origin had advanced to owner commit `49e681db`, which independently fixes the scan-order half at the
dispatch boundary (`entity-ordered-slice?`) and also repairs attached-predicate output projection.
This lane preserves that commit and rebases its own changes on it. The first two eligibility checks
above, for scan-version-correlated buffers, remain proposed and uninstalled. The four-entity edge probe's value-order case happens to pass (probe cardinality
is not below the estimate and the seek optimization is not selected); use the 81-entity matrix for
that regression, not the smaller passing fixture. Required tests after lint recovery:
`datahike.test.query-planner-temporal-test/history-joins-preserve-every-matching-version` and
`datahike.test.query-planner-temporal-test/history-merge-matches-belong-to-the-scan-version`.
Task: `seon.db/history-joins-drop-matching-datoms` (`fd3957971e6d`).

## Cost and stale estimates

[File-store probe](dh-planner-review-file-cost.clj): file backend, cache size 1, three fresh-connection
executions per cell after warming the plan with result caching disabled. Same stored bytes on parent
and target. Measurements are storage `:reads`, elapsed ms and per-thread allocated bytes from
`com.sun.management.ThreadMXBean/getThreadAllocatedBytes`. No Seon armed calls occur in these isolated
fork JVMs (armed-call count is not an applicable metric). Allocations include result construction and
node decoding; they are not retained heap. Configuration adds one non-unique indexed attribute to the
original fixture, so these counts should not be substituted for the original lane's fixture numbers.

Median of three; MB is decimal allocated MB:

| runs / shape | parent reads / ms / MB | target reads / ms / MB |
|---|---:|---:|
| 2,000 identity receipts | 152 / 48.546 / 78.139 | 15 / 14.605 / 8.077 |
| 8,000 identity receipts | 593 / 167.141 / 311.889 | 8 / 3.698 / 3.607 |
| 2,000 indexed non-unique, three selected values | 36 / 8.782 / 17.027 | 36 / 17.934 / 17.023 |
| 8,000 indexed non-unique, three selected values | 5 / 1.601 / 1.888 | 5 / 1.457 / 1.886 |
| 2,000 dense non-unique, 6,000 answers | 148 / 48.621 / 78.931 | 149 / 101.181 / 79.292 |
| 8,000 dense non-unique, 24,000 answers | 588 / 182.877 / 319.587 | 589 / 193.912 / 320.306 |
| 2,000 entity-collection seed, three answers | 148 / 48.104 / 83.696 | 148 / 89.678 / 83.587 |
| 8,000 entity-collection seed, three answers | 557 / 180.375 / 325.319 | 557 / 198.186 / 325.449 |

A later candidate repeat (also carrying the predicate fix and inert comment deletion) had medians:
identity 11.238/3.186 ms, sparse non-unique 12.298/1.736 ms, dense non-unique 50.321/224.350 ms,
entity-seeded 47.672/186.585 ms for the two sizes. Read counts repeated exactly. Dense 8,000-run
samples ranged 181.683–247.707 ms. Thus elapsed-time noise is material; **no universal “no materially
worse shape” certificate is justified**. The dense path adds one read and about 0.2% allocation;
the entity-seeded input itself grows from 6,000 to 24,000 ids. The latter is not a fixed-input
constant-work claim. No deterministic catastrophic regression was found in these controls.

The reduction in reads at the larger size is the existing 2,500× probe/scan break-even, not evidence
that a larger tree is cheaper in general. Existing task:
`seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` (`2d3141cad3fa`).

### Cached plans across sizes

[Growth/cached-plan probe](dh-planner-review-cost.clj) creates `:g/a` on one entity and 100
`:g/b` refs to it, then adds 1,000/10,000/50,000 other `:g/a` entities. Query:

```clojure
[:find ?e ?x :where [?e :g/a ?a] [?x :g/b ?e]]
```

Warming on the small DB keeps the order `:g/a` then `:g/b` and estimates 1/100. A fresh large-db
plan reverses the order, with counts 50,001/100. Both return 100 rows. At 50,000 added entities:

| revision | stale median ms / bytes | fresh median ms / bytes |
|---|---:|---:|
| parent | 4.758 / 3,939,400 | 0.623 / 113,192 |
| target | 3.431 / 3,948,168 | 0.387 / 121,960 |

The cache key (`query.cljc:3539`) contains clauses, bound-vars, rules, shape-derived input cards and
schema hash, but no data identity or statistics. The direct execution path can use the stale first
scan without entering adaptive replanning. Even the old replanner only refreshed remaining plain
pattern `:estimated-card`, not group/driving `:scan-card`; it could not repair work already done.
Therefore restoring per-execution counting is not the fix for this example. A sound reusable
statistics design is beyond this local cost patch; a blanket db-commit key would reintroduce expensive
planning after unrelated writes. Existing task: `seon.db/query-plans-are-reused-across-database-sizes`
(`3523ddcf8a1a`). Initial planning's range-count cost remains separately tracked by
`seon.db/planner-estimates-load-every-node-of-the-counted-range` (`6a3635f38a01`).

## Original new test: assessment and changes

- Its seven result comparisons do check those seven shapes against the base engine; the separate
  explicit-answer assertion prevents all of them being the only oracle.
- It does not cover rules, boolean operators, temporal worlds or every binding kind. Nor did its
  single-size read ceiling demonstrate the store-growth claim.
- A store cache of one does not mean every logical node visit is a storage read: the resident node
  and live index objects still exist. The misleading comment is corrected to say storage reads.
- The warm-up now explicitly disables the result cache as the measured call already did. Connection
  release currently fences result-cache generations, so no result-cache contamination was reproduced;
  this makes the measurement's intent explicit rather than claiming a demonstrated cache bug.
- The existing read-ceiling test now grows the SAME store from 2,000 to 8,000 runs, verifies the
  population, and checks the joined read ceiling again. Other named lookup fixtures are unchanged.
  An independent clone/grow probe returned 8 reads, three rows, 3.307–3.531 ms and about 3.624 MB after
  growth. The test runner remains root's responsibility.
- These are deterministic work assertions for two concrete layouts, not elapsed-time assertions or
  a proof of asymptotic behavior for all layouts.

## Port fidelity and design check

Every added and removed upstream #974 line in `plan.cljc` and `lower.cljc` appears in the 5258679c
patch, adapted to the older surrounding code. Bound-aware `source-cards`, the source-order fold and
the disconnected-component cost tie-break are faithful. The `(number? card)` guard for function hints
is an additional fork change, not in #974.

The upstream patch itself left a long comment in `assemble-entity-group` claiming a second reduction
from a bound-aware scan count. The actual code only cast `group-card-final` to long. This review deletes
that misleading comment and redundant alias and retains the original value (EQUIVALENT). Upstream's
commit message explicitly says it tried and reverted a group-level bound-aware `:scan-card` because
it broke the array-valued probe seek control. That named upstream test is absent from this fork;
its existence in the upstream commit message is not installed test evidence. This review does not
revive that group-card change.

The original optimization's principal composition holds: start work and output selectivity are
separate estimates; bounded sampling reuses the caller's count; prefix observations do not require
recounting unchanged attribute extents during one execution. The persistent-set adapter delegates
`-count-slice` to `psset/count-slice` (persistent_set.cljc:217–220); its range-node cost must not be
called O(log n) merely because the entry lookup is logarithmic. Source-order cardinalities remain
heuristics, and `group-start-card` trusting a `:scan-card` when only some assumed bindings are seeded
remains a plan-quality limitation. No execution limit was substituted for an estimate.

The smallest predicate repair was deletion of an invalid optimization. The history fast path's
assumptions do not hold for all entrants; it needs the separately documented owner repair, not query
rewrites. The stale-cache problem needs statistics lifetime work, not restoring the removed recount.

## Verification and landing

- Seon HEAD `439401ac31ef1533314e6bd667d289c45ff6857f` archive plus this lane's documentation-only diff:
  `clj-kondo --lint src script test --cache false --parallel` returned **0 errors**, 1,481 existing
  warnings, 7,467 ms. An earlier archive returned 0 errors, 1,487 warnings, 7,580 ms. An initial
  accidental working-tree lint also returned 0 errors (1,481 warnings, 8,975 ms); it is not the
  candidate evidence. Another lane's source changes were not included in the archive.
- Fork archive plus the exact changed Clojure files: 0 errors, 44 warnings, 139 ms. This is the changed
  files' check; it is NOT a clean bill for all dependency source, as the execute.cljc hook findings show.
- Installed `seon.contracts-compile-test/check` read the four changed fork source/test files against
  the archived packaged Seon projection and returned `[]`, result `result/ea7df6051d5d6`. No fork code
  was loaded into default. The fork functions carry no Seon Malli declarations; this is a static check,
  not instrumentation or proof of runtime contracts.
- Parent/target differential matrix: 126 matching observations each; three worlds, 42 shapes each.
  The subsequent wide-group identity/predicate probe is a real before/after answer counterexample.
- Combined final production code (owner `49e681db` plus this lane): the two matrix history mismatches
  and both scalar projection failures are fixed. The 126-observation matrix has only three remaining
  disagreements, the incomplete recursive answers in each world. The separate history buffer
  counterexample still fails. The minimal nonrecursive predicate regression has exact positive
  before/after evidence. No test runner was started.
- No default reset is needed for these measurements. Root must choose when to bump the gitlink and
  restart default to install the fork; this lane does neither. Suite acceptance remains pending root.

Root's focused list for this slice:

1. `datahike.test.query-predicate-pushdown-test/bound-range-predicates-preserve-answers`.
2. All five `datahike.test.query-identity-join-test` vars: `an-identity-join-answers-like-the-base-engine`,
   `an-identity-join-has-answers`, `the-bound-unique-lookup-leads`,
   `an-identity-join-reads-the-nodes-of-its-answer`, `a-bound-unique-value-names-at-most-one-entity-per-value`.
3. `datahike.test.query-planner-test/test-collection-binding-join-agreement`,
   `datahike.test.query-planner-test/test-estimate-pattern-with-bindings`,
   `datahike.test.query-planner-test/test-probe-driven-read-keeps-the-planned-value-bounds`, plus namespaces
   `datahike.test.query-rules-test`, `datahike.test.query-planner-temporal-test`,
   `datahike.test.query-or-test`, `datahike.test.query-not-test`.
4. To verify the original five-red claim, run the same full focused namespace selection on BOTH
   d9121bbb and 5258679c: every `datahike.test.query-*`, `datahike.test.jobtech-patterns-test`,
   `datahike.test.lookup-refs-test`, `datahike.test.api-test`, `datahike.test.entity-test`,
   `datahike.test.pull-api-test`, `datahike.test.experimental.graph-planner-test`. Include the
   corresponding attribute-refs variants explicitly when reconstructing that reported selection.
   Match failing test identities and actual/expected values, not just totals. The original notes do
   not list individual red identities, so their equality claim remains unverified here.
5. Also run `datahike.test.query-planner-temporal-test/test-history-merge-over-value-ordered-scan`
   from the preserved owner commit `49e681db`.
6. The full-suite thirteen-red claim requires root's separate `clj-pss` run on both revisions.

Reproduce measurements from an archive of each revision with its compiled Java classes available:

```sh
clojure -M /absolute/path/dh-planner-review-probe.clj /owned/scratch/results.edn
clojure -M /absolute/path/dh-planner-review-cost.clj
clojure -M /absolute/path/dh-planner-review-file-cost.clj /owned/scratch
clojure -M /absolute/path/dh-planner-review-edge-probe.clj /owned/scratch/file-2000.edn
```

The file-cost script creates stores on its first invocation and reopens those same bytes on the next
revision. Release each JVM before running the next; delete only those owned stores after recording
results. Measurement scripts are committed alongside this note so the evidence outlives scratch.

Fork commits authored by this lane:

- `f3d01c16`: five files, +32/-34; source net -27, tests net +23, changelog +2. Removes symbolic
  literal bounds, deletes the inert group-card alias/comment, strengthens the growth measurement,
  and adds the ordinary bound-predicate regression.
- `eb404fa1`: one test file, +5/-0; adds the wide identity/predicate counterexample to the answer table.

Combined lane delta: source -27, tests +28, changelog +2. Owner `49e681db` is preserved and is not
claimed as this lane's work. Main was fast-forwarded; atomic push succeeded. `git ls-remote` verified
both `refs/heads/main` and `refs/heads/seon/identity-join-first` at
`eb404fa1a2e1bb5a12f5fa1398abb5d4ac03e8e5`. The Seon gitlink remains unstaged and unchanged in its
commit. The documentation commit identifier is in the landing response. Claims are released after
that commit.
