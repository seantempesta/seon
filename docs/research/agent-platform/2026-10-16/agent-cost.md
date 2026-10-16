---
type: research
status: evidence
created: 2026-10-16
---

# Agent request cost — 2026-10-16

The eval recording already holds before/after database values. Test selection derives changed declarations,
reaching members and current results from them; the done rule consumes those same results. This slice folds
changed declaration identities with Datahike's immutable index diff, retains content-valid membership/results,
and batches result reads before running only stale tests. It does not complete the requested incremental fold
of membership and verdict when graph edges change.

## Probe and authority

Parent: `61fa266164142dff7f55cb969ba789b2ba18f2e6`. The candidate is the change-cost commit carrying this note.
Both were published and reloaded on disposable live-store branches, then measured in separate committed-archive
check JVMs with the same probe and a 4 GiB heap. Only one check ran at a time. Default was neither stopped nor
restarted. Its existing memory-pressure health finding was reported at entry; no open model turns were observed.

`seon.agent-cost/group-run` creates independent own-branch agents through `seon.test-support/own-branch-agent!`.
Canonical task writers put linked tasks on each branch; no main chain is created, so root cannot advance the
measurement while it runs. One value function reaches 24 tests; a narrow function reaches one; a deliberately
red proof keeps the task open. The merge list has 26 members. No model call is made. Branch retirement is in
`finally`. Concurrent requests start together and every future is joined before retirement.

Exact measured requests, through the ordinary MCP entrance and `seon.profile/measure`:

1. First: redefine `value` from `[x] x` to `[x] (+ x 0)`; 24 tests reach it.
2. Repeat: redefine `narrow` from `[x] (+ x 0)` to `[x] (+ 0 x)`; one test reaches it, call edges stay equal.
3. Unchanged: `(+ 1 2)`.
4. Verdict: `seon.context/step-verdict` on the held branch.
5. Root: `(+ 1 2)` as root **only inside the disposable test cluster**. Never root SCI on default.

Request times and allocations are the complete reply's `:seon.cost/ms` and `:seon.cost/alloc-mb`, including its
automatic test step. The surrounding measure includes MCP delivery; its thread-local allocation does not include
work on other threads, so it is not substituted for request allocation. Verdict runs synchronously and uses
measure's time/allocation. Profile call windows are JVM-wide; K=1 is the attribution probe. Old-generation peaks
come from MemoryPoolMXBean, reset after preparation and sampled after the request groups. This is a small
synthetic chain, not proof of every 64-member production chain or steady-state heap retention.

Pinned seams inspected: Datahike `ee3f8ec817e5310b3a0ea5adb8904214183568c0`, `datahike.db/datoms-diff`
(shared persistent index subtrees are skipped); Seon's `seon.db/datoms-diff`, `seon.db/content-revisions` and
`seon.db/read-at`. Revisions name the latest modifying commit for each attribute; absent authority bypasses
reuse. Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, `malli.core/validator` and `malli.core/-instrument`;
all changed/new production functions retain contracts. No atom, schema dial or lifecycle attribute was added.

## Measurements

The same probe on parent and the final slice, before committing. K=3 and K=6 entries are per-request ranges;
old-generation peaks are for the whole group. Allocation is decimal MB, retained memory MiB.
Raw measurement values are in [agent-cost-measurements.edn](agent-cost-measurements.edn), parent first, candidate
second. They were copied from the value printer's output; all records parse as EDN.

| K | Stage | Parent ms | Slice ms | Parent allocation MB | Slice allocation MB |
|---|---|---:|---:|---:|---:|
| 1 | First, 24 reached tests | 1137.1 | 1026.7 | 1183.3 | 1064.0 |
| 1 | Repeat, one reached test | 420.4 | 285.8 | 500.3 | 353.7 |
| 1 | Unchanged | 120.8 | 35.6 | 123.2 | 37.4 |
| 1 | Verdict | 19.67 | 5.37 | 30.40 | 6.03 |
| 1 | Root arithmetic | 84.2 | 86.3 | 139.0 | 138.1 |
| 3 | First | 318.1–351.0 | 266.1–288.1 | 407.1–414.4 | 280.6–298.7 |
| 3 | Repeat | 324.5–339.3 | 168.0–196.8 | 377.9–388.9 | 172.0–180.4 |
| 3 | Unchanged | 120.4–141.6 | 39.3–42.7 | 127.4–130.6 | 37.7–38.0 |
| 3 | Verdict | 16.91–18.20 | 4.67–5.62 | 30.12–30.14 | 5.99–6.00 |
| 6 | First | 311.2–357.8 | 261.2–285.0 | 391.2–433.6 | 299.5–344.6 |
| 6 | Repeat | 277.6–336.8 | 166.3–194.3 | 315.1–409.1 | 169.0–176.9 |
| 6 | Unchanged | 132.8–163.7 | 51.1–62.0 | 132.0–136.6 | 38.2–39.0 |
| 6 | Verdict | 15.69–24.37 | 4.38–4.82 | 30.10–38.59 | 5.98–5.98 |

| K | Parent old-generation peak MiB | Slice peak MiB |
|---|---:|---:|
| 1 | 393.88 | 393.72 |
| 3 | 415.18 | 422.44 |
| 6 | 471.06 | 477.45 |

K=6 fits the 4 GiB heap with headroom in this probe. Retained old-generation memory did not improve. K>1 follows
K=1 and can reuse content-valid results across branches: first-request times across different K are not a
scaling comparison of equal executed work. Compare parent to candidate at the same K.

K=1 repeat: one `seon.test/run`, one `seon.test/bounded-result`, zero `seon.fn/gate-edges`, zero
`seon.db/entities-changed-after`; nine `seon.test.runner/newest-results` calls. The unchanged request runs no tests,
rebuilds no graph and makes two newest-result calls (down from 50 in the intermediate unbatched candidate).
Remaining repeat self-time includes `seon.db/vector-query-call` (271 calls, 54 ms), `seon.db/transact-call`
(5 calls, 53 ms), `seon.db/pull-logical` (275 calls, 18 ms), and the one bounded test (13 ms). These profile
windows include nested and async work; do not sum them into a request total.

The first K=1 request remains just over one second and allocates over 1 GB for 24 reached tests. This is a finding,
not a justified steady-state target. Root's reported 1.2–1.7 s cross-lineage case did not reproduce here: the
live parent branch-change lookup was 6.79 ms / 8.1 MB. The code path that walked history has nevertheless been
removed, and the regression asserts no history walk. No root speed-up is claimed.

## What changed, and what is still proportional to too much

| Owner / derivation | Before | This slice | Required next step |
|---|---|---|---|
| `seon.program/own-changed-identities` | History from fork/base, then compare every touched declaration to base on each call | Held identity set, immutable diff from held digest revision, compare only touched identities to base; exact repeat reuses the set | A missing cache entry still compares base to head; alternating branches sharing a base can make the held revision farther away |
| `seon.test/branch-changes` | Extra fork-history scan in `untouched?`, then branch delta | Deletes that scan; reads/release the base and uses the same fold | `behavior-changed` still visits accumulated changed identities |
| `seon.turn.batch/entity-facts` | Equal namespace refs could be retracted/reasserted during a body edit | Uses `seon.fn/reconcile-tx`; unchanged refs retain their datoms and graph revision | None for this reproduced churn |
| `seon.cluster.source/merge-members` | Re-derive whole accumulated member list on every call | Same derivation held by selection revisions, changed identities and proofs | A graph change or newly accumulated identity still rebuilds the full list; needs per-change contribution fold |
| `seon.test/selection-revisions` | Destructive/host graph key included every program-body revision | Key contains graph/selection input revisions and declared edge attributes | Graph rebuild still scans program identities and declared-edge attributes on a miss |
| `seon.test/stale-in` | One current-result lookup and input-digest query per member | One batch lookup and input-digest query, then fold requested results | Still traverses requested members; use affected identities to update held status |
| `seon.test.runner/current-results` | Re-read member execution histories and re-derive reaches each call | Hold the existing answer by program/task/result revisions and loaded platform; a repeated judgment reuses it | A relevant revision invalidates the whole requested set; needs per-reach result accumulation |
| `seon.turn/test-step` | Pass every member/proof into the runner when anything was stale | Run only stale reaching tests; run proofs only when a proof is stale | Membership/staleness acquisition still inspects the accumulated list |
| `seon.context/step-observation`, `seon.context/step-verdict`, `seon.cluster.agent/done-judged` | Independently invoke the shared full derivations | Reuse the owners' content-valid answers; observed verdict 4–6 ms | No held verdict fold yet; judgment still walks held members |
| Root request | Could compare a transaction number from another lineage | Immutable database comparison, no transaction-number lineage walk | Reproduce the owner's slow production root case before claiming its cost fixed |

The fold cache stores values and revision identifiers in `seon.cache`, never database objects. Miss recovery uses
`seon.db/read-at` and releases the read value. It does not stamp a connection. Result and member caches are central,
weighted, and bypassed when revision authority is absent. Merge and done still call the same derivation.

## Proof and rejected explanations

`seon.program-test/declaration-difference-uses-values-not-lineage` compares each accumulated delta, including a
revert to base, with a full history-derived digest comparison and compares their merge member lists. It also
asserts zero lineage-walk calls. The source regression invalidates the held list after an edge change. The runner
regression verifies green reuse, invalidation on a changed callee, and a subsequently recorded red result.
The test-step regression proves the unaffected test keeps its old run while the affected test gets a new one.
The staleness regression was red on parent with two current-result calls, then became one batch lookup.
The probe asserts one bounded test for the narrow change, no graph rebuild, no unchanged-request test run, and
no root history walk.

The namespace-reference regression was red on parent: before/after had the same entity, attribute and value,
but a new transaction retracted and reasserted the ref. `seon.fn/reconcile-tx` already normalizes reference values
and components; reusing it removes the false graph change at its producer.

Two hypotheses were rejected before committing: unchanged contract components were stable in the real MCP path
(the fixture's replacement helper had caused that churn), and wrapping a body in `do` is not a stable-graph edit
because analysis records the new call edge. The final probe swaps the operands of the existing `+` call instead.
No unsupported component-churn fix or claim remains.

Checks before the final commit gate:

- Combined fold/reuse regressions and K=1/3/6 probe: run `3232eadf1414`, 6 tests / 34 assertions, green.
- Context, done/merge, test-step, db-flow and inherited runner evidence: run `513ece4e178d`, 15 tests / 122 assertions,
  green, 18.0 s test execution; candidate publication 4.79 s. The same vars are named again in the commit gate.
- Batched staleness, probe, reach and host tests: run `2f69be6882ff`, 68 passes, no assertion failures, one pre-existing
  host-test error. The matching parent run `8bf2a7dd7c09` reproduces it: the test calls `seon.test/host-exclusions`
  with three arguments after its contract changed to four. That test is not edited; final selection names green
  host vars individually. One declared-long loaded-commit test was excluded in this exploratory namespace run;
  final selection names it explicitly.
- Whole `src script test` clj-kondo with `--cache false`: zero errors, 1816 warnings, 8.5 s before the last probe
  assertions; repeated before commit.

Foreign working-tree boundaries: dirty `reference-code/konserve`, untracked content under `reference-code/http-kit`,
and other lanes' research artifacts were not included. Candidate archives use the pinned submodules. The edit
hook cannot resolve the uncommitted `seon.agent-cost` script namespace in default's loaded program; that probe is
compiled and run in the isolated candidate, not loaded into default. No default restart was used as proof.

## Reproduce and continue

Run the two named probe tests with `bin/seon check`, including `script/seon/agent_cost.clj` and
`test/seon/agent_cost_test.clj` as candidate paths. For parent, include only those two paths; for the slice, include
its production and regression paths too. Name each long test by var:
`seon.agent-cost-test/chain-probe-measures-the-complete-request` and
`seon.agent-cost-test/concurrent-chain-requests`. The probe writes small complete EDN values under
`/tmp/change-cost-probe-1.edn`, `/tmp/change-cost-probe-3.edn`, `/tmp/change-cost-probe-6.edn`.

Next slice: fold member contributions and current results from the actual request's before/after change, rather
than invalidating whole selection/result keys. Its red proof must change an edge after accumulating many changed
identities, assert agreement with the full merge list, and measure the complete request against this slice.
Include deletion, revert, changed task proof, dynamic calls and held-cache eviction. Then fold verdict counts from
those held results. Do not claim the owner's full first-principles design is installed until these miss paths
also cost the change. The 24-test first request and production slow-root reproduction remain open measurements.
