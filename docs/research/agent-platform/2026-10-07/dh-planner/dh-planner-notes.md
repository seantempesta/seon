# dh-planner notes (2026-10-07)

Owner decision: fix the Datahike fork's planner at the producer, not Seon's queries. Task
`seon.db/an-identity-join-scans-the-whole-ref-attribute` (a952d921f468); evidence in
[growth-notes](../growth/growth-notes.md) §1.

Fork commit `5258679c` (branch `seon/identity-join-first`, fast-forwarded onto the fork's `main`, both pushed to
origin `seantempesta/datahike`). Seon bumps the `reference-code/datahike` gitlink from `d9121bbb` to it.
**Default sees the change only after root restarts it.**

## Cause (fork `d9121bbb`)

The query shape is `[?run :turn/id ?id] [?form :eval/run ?run] [?form :eval/ordinal ?o] ...` (the identity value comes from
an `:in` binding). Four defects worked together. The first is the one growth saw; the other three surfaced once it was fixed:

1. **A group was costed by its output, not its work.** `plan.cljc` `group-effective-card` (`:970`) gives an entity group its
   `:estimated-card`. `assemble-entity-group` (`:813-825`) computes that as the driving scan's extent multiplied by each
   merge's pass rate, `merge-est / total-datoms`. Over a large store three patterns estimate a handful of rows (default:
   96). `dp-order-groups` (`:1135-1140`) seeds the DP with that number as the group's cost. So the group beat the bound
   unique lookup (`:scan-card` 100 for a collection input, 4,100 under heuristic estimates), ran first with nothing
   bound, and read its whole driving scan.
2. **The binding vanished one clause later.** `lower.cljc` computed every scan node's output cardinality in an unbound
   pre-pass (`node->output-cards`). The lookup advertised `?run` at the full `:turn/id` extent, so `[?form :eval/run ?run]`
   did not look selective, and the group's driving scan was picked among ties: default drove from `:eval/source` and
   `:eval/at`, which cannot use the bound `?run` at all. Upstream already fixed this in 314d4a4d (#974).
3. **Unique values had no unique estimate under heuristic counts.** Default's index roots carry no subtree counts
   (task `seon.cluster.store/the-store-index-roots-carry-no-subtree-count`), so `estimate-pattern` uses heuristics. A bound
   unique value then estimated `v-card × base/distinct` = 41 rows per value (`estimate.cljc` `estimate-pattern-with-bindings`
   v-card arm). A ground unique value estimated `total/1000` = 769 rows (`estimate-pattern-heuristic`).
4. **Every execution re-counted attributes.** `execute-plan` re-plans when the actual cardinality differs from the estimate
   by 10x, and a selective lookup always does. `plan/replan` (`:1758`) re-ran `estimate-pattern` for each remaining pattern.
   Its DP called `sample-distinct-v-count`, which counted the attribute with `-count-slice`. persistent-sorted-set's
   count-slice loads every node under the range, because a branch stores its own subtree count and not its children's.
   That cost 239 node reads for `:eval/run` on a 1.16M-datom store, on every execution.

Refuted: subtree counts alone. Growth's healed-count copy still ran the group first (defect 1). The plan cache also explains
a confusing reproduction: plans are keyed by shape and schema, so a plan built on a small db ran on a large one (task
`seon.db/query-plans-are-reused-across-database-sizes`).

## The rule

- `plan/group-start-card`: the first group of a plan is costed by what it reads when it starts. An entity group's driving
  scan reads its unconstrained `:estimated-card`, unless the seed binds the scan's entity or value var; then it reads its
  `:scan-card`. Its output estimate stays the rows for joins after it. Used by the DP base case and the greedy first pick;
  `order-plan-ops` passes its seed.
- `estimate`: a bound or ground value of a unique attribute names at most one entity per value, in counted and heuristic
  mode alike.
- `estimate/sample-distinct-v-count` reuses the caller's base count and reads at most 65 datoms; it no longer counts the
  attribute.
- `plan/replan` keeps the planned estimates. An unconstrained attribute count does not change with what the prefix observed.
- Port of upstream 314d4a4d (#974): scan output cardinalities fold in source order under the bindings entering them, and
  a disconnected component's seed is the cheapest.

Results are unchanged: ordering and estimates never change answers (set semantics), and the tests below check that.

## Tests (fork)

- `datahike.test.query-identity-join-test` (new; file store, `:store-cache-size 1`, so store reads count node work):
  - a table of seven identity-join shapes: collection, missing id, scalar, literal, lookup-ref input, lookup written last,
    two groups. Each must equal the base engine.
  - the explicit answers.
  - the plan's first op is the `:turn/id` scan and every group drives from `:eval/run`.
  - node reads: lookup ≤ 4, joined receipts ≤ 16.
  - the unique estimate law under a heuristic base.

  On the parent it fails 4 of 5 tests: the plan led with `ENTITY-GROUP`, the joined receipts read 126 nodes, and the
  estimates came out at 1,900 and 19.
- Focused query suites (`query*`, jobtech, lookup-refs, api, entity, pull, graph-planner): 309 tests / 1,559
  assertions with 5 failures, the same 5 as the parent (304 / 1,544 / 5).
- Full `clj-pss` suite: 918 tests / 4,985 assertions with 13 failures. The parent has the same 13 failures in the same tests
  (913 / 4,970 / 13): task `seon.db/fork-datahike-suite-has-thirteen-reds`.
- `benchmark.planner-regression --assert` prints no shape within 600-900 s on parent or change: task
  `seon.db/fork-planner-regression-bench-never-finishes`.

## Numbers, parent → change

Measure: konserve node reads for one execution on a fresh connection with its plan cached, `:store-cache-size 1`, in a
plain fork JVM. Per-thread allocation is `getThreadAllocatedBytes`; cold reads include node decompression.

Synthetic file stores (`[?run :turn/id ?id]` + two groups + get-else):

| store | parent reads / ms | change reads / ms |
|---|---|---|
| 2,000 runs, 116k datoms | 139 / 55 | 13 / 4.5 |
| 20,000 runs, 1.16M datoms | 244 / 44 | 6 / 2.1 |

The 20,000-run store needs fewer reads than the 2,000-run one. The remaining cost depends on the answer, plus the seek
break-even: an attribute under 2,500 × k datoms is scanned, not sought (task
`seon.db/a-probe-below-the-seek-break-even-scans-the-attribute`).

Seon's own data: a file copy of default's store (`data/store`, 770k datoms, branch `:cluster-default`, no subtree counts:
the same heuristic world as default), opened read-only in the fork JVM. The queries are verbatim from the Seon callers:

| caller query | parent reads / ms / MB | change reads / ms / MB |
|---|---|---|
| `seon.eval.drive/run-receipts` | 664 / 188 / 225 | 10 / 4.0 / 4.0 |
| `seon.fn/runtime-analysis-batch` (2 namespaces) | 2,908 / 664 / 993 | 56 / 11.8 / 20.8 |
| `seon.turn/next-ordinal` | 572 / 98 / 184 | 29 / 4.7 / 9.1 |
| `seon.turn/continuing-reply?` latest turn | 514 / 80 / 166 | 511 / 74 / 165 |

The latest-turn query now drives from `:seon.turn/agent`. It still reads all of root's 923 turns, because every turn on
this store is root's: its work is proportional to the agent's turns.

**Not measured yet: `(+ 1 2)` and a one-line defn through `seon.mcp/call` on the change.** The fork reaches default only
after a restart. A `bin/seon check` JVM boots a fresh store, where the shape costs nothing, so it cannot show the
difference. Parent numbers from growth: `(+ 1 2)` 117-130 ms, 144-162 MB JVM-wide, 54,000 node visits; defn 232-264 ms,
335-397 MB. After root's restart, rerun growth's probes (`growth.probe`, node-cache watch) and append them here.

## Design check

- **Held:** the producer is the planner's cost model, and the rule is general. Starting work is read from the
  driving scan, and the unique law holds for any attribute. Neither rule names Seon's attributes, and no Seon query
  changed.
- **Wrong in the assignment's framing:** the fix had to cover four places, not one. Fixing the order alone (defect 1)
  left the driving scan on `:eval/source` (defect 2) and the per-execution recount (defect 4). Each reached O(store)
  separately, and the table above was measured at each step.
- **Awkward:** `replan` keeps a now-unused `actual-card` parameter for its callers. `group-start-card` trusts
  `:scan-card` when the seed binds the scan var, even though `:scan-card` may also assume source-order bindings from
  other nodes. That assumption is pre-existing and conservative here.
- **Upstream** still carries defects 1, 3 and 4 at `upstream/main` (23692d77). The fork commit is self-contained for an
  upstream PR (not opened).
- **Remaining, outside this slice:**
  - plan creation still counts attributes at O(range) node loads, and the plan key embeds constants, so scalar and
    literal queries re-plan per value (task `seon.db/planner-estimates-load-every-node-of-the-counted-range`);
  - default's missing subtree counts (`seon.cluster.store/the-store-index-roots-carry-no-subtree-count`).

## Findings

- `runtime_status` before the change: default `:faulted`, 16 open faults, 174 errored receipts, 43 unknown tests, and
  `seon.batch/evaluate-batch` mean 1.49 s (max 78 s). These are pre-existing and tracked under that subject's existing tasks.
- Tasks created (agent dh-planner, cluster default):
  - `seon.db/planner-estimates-load-every-node-of-the-counted-range` (6a3635f38a01)
  - `seon.db/query-plans-are-reused-across-database-sizes` (3523ddcf8a1a)
  - `seon.db/fork-datahike-suite-has-thirteen-reds` (8eda6b0710f4)
  - `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` (2d3141cad3fa)
  - `seon.db/fork-planner-regression-bench-never-finishes` (256debe08d13)

**RESTART NEEDED** (root): default must load the bumped fork. Afterwards, re-measure `(+ 1 2)` and the defn. Default's
plan cache is in memory, so the restart rebuilds the cached plans too.
