---
title: Datahike stability (dh-fix)
type: research
status: landed-on-fork
date: 2026-10-07
lane: dh-fix
---

# dh-fix notes (2026-10-07)

Lane `dh-fix`: the Datahike stability step before S1 of the
[live-agents plan](../root-plan/live-agents-plan-notes.md). Fork `reference-code/datahike`, branch
`seon/identity-join-first`, base `eb404fa1`. Seon's gitlink stays at `d9121bbb`; root bumps it after a dual review of
the combined fork diff. **RESTART NEEDED** after that bump (default loads the fork only at boot). No reset is needed for
anything fixed here (see the subtree-count section).

Which step of the one happy path this serves: every agent turn reads through Datahike queries (context, receipts,
analysis). Wrong answers and multi-second stale plans there break the loop; the fixes are in the owner of each
defect (the fork's planner and executor), and add no Seon code.

## Method

- Never in default's JVM. Two plain fork JVMs with socket REPLs: `base` (a `git archive` of `eb404fa1` plus the
  compiled `target/classes`) and `work` (the submodule working tree). Full suites ran from `git ls-files` snapshots.
- Store measurements on an APFS clone of default's store (`cp -c -R data/store`, 823 MB, branch `:cluster-default`,
  no subtree counts), opened in the fork JVM. Each measurement: plan cached, then 5 samples of one execution on a
  fresh connection with `:store-cache-size 1` (cold: every node load is a storage read) and one repeat on the same
  value (warm); medians. Allocation is `ThreadMXBean/getThreadAllocatedBytes` of the calling thread.
- Answers: planner versus base engine (`*disable-planner*`) on current, history and as-of values, the review's
  126-observation matrix ([probe](../dh-planner/dh-planner-review-probe.clj)) plus the task reproductions.
- `bin/seon status` at start: default PID 41301, state unknown, 13 over-bound warnings (top: `seon.batch/evaluate-step`
  mean 1.05 s max 43.6 s, `seon.batch/evaluate-batch` mean 2.35 s max 43.7 s), heap used 2.99 GB. Existing tasks named
  in the status; not this lane's slice.

## Wrong answers (fixed)

### 1. Recursive rules disagree with the base engine (`seon.db/planned-recursive-rules-on-history-disagree-with-the-base-engine`)

Not history-specific: two defects, both on the current db too.

- **Head vars spelled like outer vars.** `lower.cljc` `plan-rule-op` (`free-call-args`, ~:446 at `eb404fa1`) kept each
  head var's own spelling in the branch plans, and `execute.cljc` `execute-recursive-rule` (`scoped-ctx`, ~:3690)
  keeps every outer relation that shares a var with the branch plans (its comment assumes "rule branch bodies are
  var-renamed", false for head vars). With `[?a :p/id ?id] (reach ?a ?b)`, the outer `?a` relation restricted every
  recursive step to p6, so the closure stopped at depth one. On a never-retracted 20-person chain the current db
  answered `#{[6]}` against the base engine's six rows (the task saw it only on history because there the depth
  mattered).
- **A lookup ref passed to a rule call.** `query.cljc` `substitute-consts-with-lookup-refs` rule-call branch
  (~:3340) inlined only scalar consts; a lookup-ref input stayed an unbound var, so `(reach ?a ?b)` with
  `?a = [:p/id "p6"]` answered for every entity (19 rows against 6, current db too).

Fix (`84790323`): head vars are private to the rule op (`?a__head__r123`), except a position every recursive call
passes through unchanged (`invariant-head-positions`), which takes the call arg's name so the caller's values still
restrict the closure. That exception is what keeps cost: with private names alone, Seon's `affected` rule
(`seon.cluster` contract-findings, `(affected ?ref ?changed)` passes `?changed` through) went from 7.8 ms / 7.4 MB
warm to 85 ms / 92 MB because the closure ran over every schema; with the exception it measures as before (table
below). Lookup refs in rule-call args, input or literal, resolve with `dbu/entid-strict` like an entity pattern
position; a missing one refuses with the base engine's message, and a lookup-ref var in `:find` still answers the
ref.

The stopped-wip patch carried the head-var rename (kept, extended with the invariant exception after measuring the
regression) and a `bind-rule-arg-consts` relation route for lookup refs (replaced by the smaller in-place
resolution, which also lets magic sets use the ground eid).

Regression: `datahike.test.query-planner-temporal-test/recursive-rules-answer-like-the-base-engine`, 10 shapes ×
current, history, as-of and the unretracted db, planner versus base engine, plus one explicit answer. On `eb404fa1`:
32 failing assertions. After: green.

### 2. History joins drop or invent datoms (`seon.db/history-joins-drop-matching-datoms`)

The scan-order half was fixed by `49e681db` (`entity-ordered-slice?`). The buffer half remained: `execute.cljc`
`execute-temporal-merge-fast` (~:1500) filled an entity's buffer with versions checked against the FIRST scan
datom's v and tx (`check-v?`/`check-tx?` closed over `scan-d`), then replayed it for every later scan datom of the
entity. `[?e :item/a ?v] [?e :item/b ?v]` on history with a:[1 2], b:[1 4 5] answered `#{[1] [2]}` (base `#{[1]}`);
a shared tx var dropped `[4 1]`.

Fix (`77c1228e`): the buffer holds the entity's versions independent of the scan datom (the two cursors read exactly
the same nodes as before), and the shared v/tx check runs per replayed datom. Regression:
`datahike.test.query-planner-temporal-test/history-merge-matches-belong-to-the-scan-version` (3 shapes plus an
explicit answer); 4 failing assertions on `eb404fa1`, green after.

### Differential matrix

The review's 42 shapes × current, history, as-of (126 observations), planner versus base engine, empty plan cache per
query: `eb404fa1` 3 mismatches (the recursive shape in each world); after the fixes 0, in a fresh JVM.

## Catastrophic cost

### 3. Plans reused across database sizes (`seon.db/query-plans-are-reused-across-database-sizes`)

Cause: `query.cljc` `get-or-create-plan` (~:3539) keys the JVM-global plan cache by
`[clauses bound-vars rules in-cards schema-hash]`: no data size. A plan embeds the index choice (AEVT below the seek
break-even) and the `:estimated-card` that gates probe-driven seeks at run time, so a plan made on a small database
(a test store, any fresh db with Seon's schema in the same JVM) ran unchanged on a large one.

Fix (`5fcfc6a7`): the key also carries `size-class` of `dbi/-max-eid` (its bit length). `max-eid` is O(1) on every db
value; history and as-of delegate to their origin. A growing database re-plans each shape once per doubling of its
entity count, never per write. Considered and rejected: a commit id in the key (re-plans after every write, and
planning reads O(range) nodes today); re-validating estimates at run time (needs counts default does not have).

Probe ([stale.clj](stale.clj)): receipts join planned on a 10-run file store, executed on a 50,000-run store with the
same schema; fresh connection, `:store-cache-size 1`, median of 3.

| shape | `eb404fa1` stale plan | `eb404fa1` fresh plan | `6f3b61f6` plan made on small first |
|---|---|---|---|
| receipts (collection input) | 3,629 reads / 1,097 ms / 2.23 GB | 8 / 2.0 ms / 3.1 MB | 8 / 2.5-5.3 ms / 3.1 MB |
| scalar identity join | 596 / 114 ms / 253 MB | 8 / 1.7 ms / 3.1 MB | 8 / 2.3-3.5 ms / 3.1 MB |

Regression: `datahike.test.query-identity-join-test/a-plan-made-on-a-small-database-is-not-reused-on-a-large-one`
(plan made on a one-run memory db, second execution on the 2,000-run fixture store must read ≤ 16 nodes): 148 reads
on `eb404fa1`, passes after.

### 4. Index roots lose their subtree counts (`seon.cluster.store/index-roots-lose-their-subtree-counts`)

NOT FIXED: the producer is persistent-sorted-set 0.4.137 (a Maven jar, not in this fork). Its Branch split
(`branch.cljs` `try-compute-subtree-count-from-children`, the JVM `Branch.tryComputeSubtreeCountFromChildren` has the
same signature) computes each half's count from its in-memory children and returns -1 when any child is not loaded;
`(if (>= old-sc 0) (inc old-sc) -1)` then keeps -1 forever and it climbs to the root. On a store, children are loaded
lazily, so the first split of a branch whose children are not all resident loses the count. Reproduced in a fork JVM:
a fresh file store (128/32, keep-history) had counts on all six roots after a 3,000-run bulk load; after 60 small
commits, each on a fresh connection, AEVT had lost its count.

**Existing stores do not need a reset for anything in this lane.** A reset would restore counts only until ordinary
writes split a branch again (minutes on default), so it is not a fix. Options for the owner (all in PSS, a separate
library; the change is not small, so this lane stopped here):

1. **Store each child's count in its branch** (simplest correct; recommended). A branch then knows every child's count
   without loading it, splits compute both halves exactly, and `count-slice` becomes O(log n) instead of loading every
   node under the range, which also closes `seon.db/planner-estimates-load-every-node-of-the-counted-range`. Cost: one
   long per child slot (128 per branch, about 1 KB per branch node before LZ4), a format version with a reader for
   old nodes (-1 counts until rewritten).
2. Load the smaller half's children at a split to count it (no format change): up to 64 node reads per branch split,
   amortized over the ~64 inserts between splits; counts stay O(range) to query.
3. Recount on demand (a reindex job): restores counts until the next splits; operational, not a fix.

## The thirteen fork-suite reds (`seon.db/fork-datahike-suite-has-thirteen-reds`)

Every one was a stale expectation of behavior the fork deliberately landed (setup, fixed in the test; no source bug):

| tests | cause | change (`6f3b61f6`) |
|---|---|---|
| query-test dependencies ×4 | `407e9328`: without a db a plainly pulled attribute may be a component (pulls its whole entity), so a pull widens to `:all`, as `query-attribute-dependencies`' docstring says | expect `:all` |
| connector-release ×2, query-cache ×1 | `cc2b2bc7` made `:keep-history?` store-fixed, refused as `:create-time-fixed-index-config-mismatch`; the tests used it to provoke the generic mismatch | provoke it with `:schema-flexibility :read` |
| writer-error ×5 (one test, five evidence rows) | `591c825d` drops the stack trace from the write-error log (one printer) | assert no `:trace` and a `:via` location |
| attribute-refs pull-limit ×1 | `41c79c1a` pulls complete collections; the attribute-refs copy missed the update | expect 2000 |

Also: `query_test.cljc` `concept-id` used `format`, absent from `cljs.core` (a lint error); replaced with padding.

Suite counts (kaocha `clj-pss`, full): `eb404fa1` 920 tests / 4,994 assertions / 14 failures (the 13 plus a flaky
`optimistic-test/tx-report-happy-path-converges-to-conn`, which asserts a listener event before it is delivered;
task `seon.db/fork-optimistic-tx-report-test-races-its-listener`, e560b904b7f9). Source fixes alone: 923 / 5,041 / 13
(the same 13). HEAD `6f3b61f6`: **923 tests / 5,046 assertions / 0 failures.**

## Fork lint

`9e229d31`: the six `execute.cljc` errors (macro locals `first-attr`, `cur-d`, `si`, `cur-a`, `target-a` of
`sorted-merge-inner-loop`; `(int (:field probe))` on the cljs branch where `probe` is nil) and six `query.cljc`
errors (`with-destructured-vector` locals; a bare `Exception` class in a cljc macro). The anaphoric macros are
declared in each namespace's inline clj-kondo config (`:unresolved-symbol :exclude`), so the hook lints them from any
working directory; the two value fixes are equivalent. Both files lint with 0 errors under the Seon hook's
invocation. Remaining known: `query_rules_test.cljc` `test-rule-arguments` (the `deftest-async` macro), not touched.

## Cost on default's store clone, `eb404fa1` → `6f3b61f6`

Seon's verbatim hot-path queries ([bench.clj](bench.clj)): run-receipts (3 latest runs), runtime-analysis-batch
(`seon.db`, `seon.turn`), next-ordinal, continuing-reply?'s latest turn, and contract-findings' recursive
`affected` rule. Cold = fresh connection, store cache 1, plan cached; warm = repeat on the same value. Medians of 5;
two alternating rounds after JIT warm-up, ranges shown.

| shape | reads (both) | cold ms base / HEAD | cold bytes base / HEAD | warm ms base / HEAD | warm bytes base / HEAD |
|---|---|---|---|---|---|
| receipts | 14 | 3.3 / 2.8-3.2 | 5.46 / 5.44 MB | 0.5-0.6 / 0.4-0.8 | 272 / 264 KB |
| analysis | 295 | 49.1-50.1 / 49.5-57.7 | 111.6 / 111.2 MB | 5.0-5.3 / 5.3-6.5 | 8.25 / 8.11 MB |
| next-ordinal | 10 | 2.1 / 2.2-3.0 | 4.19 / 4.17 MB | 0.3 / 0.3-0.5 | 176 / 168 KB |
| latest-turn | 525 | 77.1-77.4 / 78.3-88.9 | 172.0 / 171.6 MB | 2.6-2.8 / 2.6-2.8 | 2.12 / 2.11 MB |
| affected (rule) | 942 | 120.7-128.6 / 122.4-143.5 | 303.0 / 302.1 MB | 7.8-8.4 / 7.6-8.4 | 7.57 / 7.43 MB |

Answers (hash) identical. Node reads identical, allocation equal or lower on every shape; elapsed times overlap
within run-to-run noise (the second HEAD round ran while nothing else did, yet varied 10-17%). No number got worse
beyond noise. The intermediate rule fix without the invariant exception made `affected` warm 85 ms / 92 MB; that
version was never committed.

Existing costs these numbers show (not this lane's change; tasks named): cold `analysis` reads 295 nodes / 112 MB and
`latest-turn` 525 / 172 MB (`seon.db/a-probe-below-the-seek-break-even-scans-the-attribute`, and latest-turn reads
all of root's turns as dh-planner noted); `affected` reads 942 nodes / 303 MB cold for 0 rows, a variable-attribute
pattern `[?arity ?attribute ?key]` over three attributes. That last one is new evidence: task
`seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows` (ab50a8acaedd).

## Left for later (tasks exist)

- `seon.db/planner-estimates-load-every-node-of-the-counted-range`: closed by option 1 above, not before.
- `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute`: unchanged.
- `seon.db/fork-planner-regression-bench-never-finishes`: not run.

## Design check

- **Held:** each fix sits at its producer (the rule op's names, the rule-call substitution, the history buffer, the
  plan key) and adds no mechanism: no new cache, path or fallback. Net fork delta from `eb404fa1`: 11 files, +228 / −40
  (source +79 / −24, tests +143 / −16, CHANGELOG +6).
- **Wrong in the first attempt:** private head vars alone were correct but made Seon's `affected` rule 11x slower and
  12x larger; the measurement caught it. The pass-through exception is the classic sound case of sideways binding.
- **Awkward:** `invariant-head-positions` applies to single-rule SCCs only; mutually recursive rules always get
  private names (correct, possibly slower; none in Seon). The size class keys on entity count, not per-attribute
  counts, so skew inside one size class can still pick a poor plan; the catastrophic tiny-to-large case is gone.
- **The stopped-wip patch:** its history-buffer hunk was kept as is; its head-var hunk was kept and extended; its
  `bind-rule-arg-consts` route was replaced by in-place resolution.

## Findings

- `(my.task/open (seon.db/db))` took 1,079 ms / 18,406 armed calls (`seon.schema.datahike/query-variable?` 8,895):
  existing task `my.task/open-takes-over-a-second-validating-query-variables`.
- `(doc seon.task/create!)` shows each `:map` entry's schema twice in `:in` (1,628 tokens): ugly output, reported
  here for the doc/lookup slice (S3).
- `my.task/open` lists 12 tasks; the planner tasks live in `resources/seon/tasks/seon.db.edn`, uncommitted under lane
  persist-tasks.

## Commits

Fork (`seantempesta/datahike`, `seon/identity-join-first` and `main` both at `6f3b61f6`, pushed, verified with
`git ls-remote`): `9e229d31` lint, `84790323` recursive rules, `77c1228e` history buffer, `5fcfc6a7` plan size class,
`6f3b61f6` suite reds. Seon's gitlink is NOT bumped (root, after the dual review). Measurement scripts:
[bench.clj](bench.clj), [stale.clj](stale.clj), [repro.clj](repro.clj).
