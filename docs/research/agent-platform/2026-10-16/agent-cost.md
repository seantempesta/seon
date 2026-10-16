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

Slice 1: `61fa266164142dff7f55cb969ba789b2ba18f2e6` → `5b71e4670`. Slice 2 is the commit carrying
this revision of the note, with `5b71e4670` as parent.
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

The original constant-proof probe on parent and Slice 1, before committing. K=3 and K=6 entries are per-request ranges;
old-generation peaks are for the whole group. Allocation is decimal MB, retained memory MiB.
Raw measurement values are in [agent-cost-measurements.edn](agent-cost-measurements.edn), with parent/candidate
sections for each slice. They were copied from the value printer's output; all records parse as EDN.

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

## Slice 2: task-reading proofs

The original placeholder proof was `(is false)`. It did not exercise `seon.test.runner/task-identity`, a missing
case in the first probe. The current probe uses `(is (nil? (test/task)))`, with `test` aliased to `seon.test`:
it is red while judging the assigned task and remains current on an unchanged request. Its installed input
identity includes the whole branch program, so a declaration change runs this proof as well as the narrow test.
The repeat assertion therefore changes from one bounded test to two; the member list remains 26.

On Slice 1, the task-reading repeat cost 598.8 ms / 866.2 MB. `seon.test.runner/program-content-digest` keyed its
cache by commit, so result transactions rehashed unchanged program content. Slice 2 keys that existing derivation
by the declared identity-family and definition-digest revisions. The returned digest is unchanged. The narrow
regression proves a bookkeeping write reuses it and a declaration edit changes it.

Quiet paired run: the parent check finished before the candidate check began; no edit-hook evaluations overlapped
either measurement. Both use the task-reading probe. Runs `f2048c12d606` and `4145a9ef01e7`, each 2 tests / 18
assertions green. Check time 55.9 / 59.3 s, test execution 29.0 / 28.5 s; candidate publication 2.86 s.

| K | Stage | Slice 1 ms | Slice 2 ms | Slice 1 allocation MB | Slice 2 allocation MB |
|---|---|---:|---:|---:|---:|
| 1 | First | 1364.5 | 1232.7 | 1570.3 | 1371.6 |
| 1 | Repeat, two tests | 598.8 | 496.5 | 866.2 | 670.6 |
| 1 | Unchanged | 36.1 | 35.8 | 37.9 | 37.9 |
| 1 | Verdict | 5.30 | 7.34 | 6.04 | 6.04 |
| 1 | Root | 91.5 | 85.5 | 146.7 | 146.7 |
| 3 | First | 808.0 | 683.6–695.0 | 1048.5–1052.7 | 808.2–876.2 |
| 3 | Repeat | 526.3–553.4 | 398.9–406.8 | 700.6–710.3 | 482.2–502.4 |
| 3 | Unchanged | 47.3–48.6 | 39.5–40.2 | 39.6–39.9 | 38.8–38.9 |
| 3 | Verdict | 4.80–5.23 | 4.92–6.19 | 6.06–6.07 | 6.00–6.27 |
| 6 | First | 463.8–513.3 | 316.6–344.7 | 616.5–684.4 | 411.2–467.2 |
| 6 | Repeat | 374.5–393.1 | 295.1–323.1 | 484.9–499.7 | 285.0–299.3 |
| 6 | Unchanged | 44.7–49.7 | 44.0–49.3 | 40.1–40.8 | 39.0–40.4 |
| 6 | Verdict | 4.60–6.59 | 4.39–6.44 | 6.04–6.05 | 5.99–6.78 |

Old-generation peak MiB: K=1 398.25 → 394.24, K=3 442.56 → 450.68, K=6 496.54 → 501.63. No retained-memory
improvement is claimed. Verdict and no-change costs are essentially unchanged; the K=1 verdict wall time is
higher, with the same allocation. The gain is on changed requests, not every stage.

History matters here: `064809752` introduced the content digest to fix task evidence disappearing across
connections and as-of reads. Its regression, `seon.task-test/a-task-reading-result-is-current-on-every-value-of-its-content`,
is in the second commit gate. Replacing the returned digest with an attribute revision would reintroduce that
identity error. Only the cache key changes. A declaration change still hashes the full program; eliminating that
last whole-program dependency requires an incremental content identity or task-proof evidence keyed by what it
actually read. The current test path does not record database read evidence for task proofs. Dropping the program
input now would wrongly reuse proofs that inspect the branch through the task reader.

The live edit hook also found that Slice 1's new `selection-revisions` and `result-revisions` contracts used bare
nilable returns, which authored admission refuses although archived boot accepts them. Slice 2 returns an empty
collection when revision authority is absent, as the admission rule permits, and retains the uncached behavior.
`seon.test.reach-test/content-revision-contracts-are-admitted-for-agents` checks both through
`seon.schema/projection-with-function-contract` with agent admission. Together with the bookkeeping regression,
run `ebee995a2abd` passed 2 tests / 6 assertions. No entry-policy relaxation was made.

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
| `seon.test.runner/program-content-digest` | Whole-program read after every new commit for task-reading proofs | Same content digest retained across bookkeeping writes by declaration revisions | Still a whole-program read/hash after an actual declaration change |
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

Checks, with final gate results also retained in the commit output:

- Slice 1 commit gate `12457a428a48`: 38 tests / 221 assertions green, 50.2 s execution, 2.94 s publication;
  full lint zero errors / 1816 warnings.
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

The current probe includes the task-reading proof. The constant-proof version is committed in `5b71e4670`.
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
also cost the change. The first request, the whole-program dependency of task-reading proofs, and production
slow-root reproduction remain open measurements. The first probe alone understated the task-reading path.

## Slice 3: fold call-edge changes (`6f1d9b0cc`)

Default was restarted by root onto `d76806d39` before this window. At entry it was ready but faulted, with existing
malformed-contract and recorded-error findings. A fresh `change-cost-fold` agent read its current-state block
(160.3 ms request, 194.2 MB); no root SCI request, model request, live-chain modification or restart was made.

`seon.fn/nearest-tests` already propagates each seed independently and stops at its nearest tests. The fold keeps
that algorithm: `seon.fn/selection-walk` returns its tests and visited targets, and `seon.test/reaching-selection`
resolves definition/namespace/schema inputs before that same walk. `seon.cluster.source/member-contributions`
holds each changed identity's contribution, a reverse map from visited targets to contributors, and shared-test
counts. The diff of call and subject attributes replaces only contributions that read a changed target. Removing
or reverting a changed identity subtracts its contribution; a test shared with another contributor stays held.
Long tests are filtered when the contribution is produced; named proofs/changed tests retain their old semantics.
No new atom or lifecycle fact is involved. A missing fold derives through the same owner from its inputs.

The auxiliary conservative graph, `seon.fn/gate-edges`, does not read ordinary calls or subjects. It is now held
by the identity and declared-edge attributes it actually reads. `seon.fn/declared-edge-attributes` owns the shared
declared-attribute discovery previously nested in `seon.test/selection-revisions`. A call edit no longer rereads
all program identities for host/deferred classification. The latter still traverses its conservative reach.

`seon.cluster.source-test/member-fold-equals-the-full-merge-list` covers edge removal/restoration, a shared test,
identity removal/revert and eviction. It compares the fold with the same owner's full derivation after eviction.
Its no-rewalk assertion was red on parent (`25fd6e125cc2`, 9 passes / 1 failure: one nearest walk instead of zero),
then green with done/merge and nearest-seed union (`261643a66ce1`, 3 tests / 21 assertions). The complete probe plus
fold law passed `044d83b4af5b` (3 tests / 31 assertions).

This is a call/subject-edge fold, not a completed fold of every selection input: changes to declaration identities,
namespace/schema expansion, declared invocation attributes or host declarations still acquire a new contribution
fold. Host/deferred classification and source's changed-identity set comparison also remain. These limits are
explicit; the full first-principles target is not claimed complete.

The probe now also changes `narrow` from `(+ 0 x)` to `(- x 0)` after the original repeat. This adds/removes
call edges and reaches one test plus the task-reading proof. The unchanged request follows that edit.
The final quiet pair ran parent first, then candidate, without overlapping owned check JVMs or edit-hook evaluations.
Its actual Git parent was `d2d1caf91` (which includes the earlier render merge `225a66566`), not the running JVM's
`d76806d39` named in the commit message. Parent/candidate runs were `171bf2f5b8b8` / `64742ce56a04`.
The commit gate `c9a50ce72df5` passed 36 tests / 267 assertions, 54.5 s test time, 85.7 s total, 2.57 s publication.

| K | Stage | Parent ms | Slice 3 ms | Parent allocation MB | Slice 3 allocation MB |
|---|---|---:|---:|---:|---:|
| 1 | first | 1221.40 | 1268.60 | 1360.70 | 1316.30 |
| 1 | repeat | 461.70 | 452.70 | 660.90 | 667.00 |
| 1 | edge | 491.30 | 462.90 | 733.70 | 689.90 |
| 1 | unchanged | 36.10 | 37.10 | 38.10 | 38.80 |
| 1 | verdict | 4.94 | 4.80 | 5.88 | 6.05 |
| 1 | root | 75.00 | 77.20 | 138.00 | 138.50 |
| 3 | first | 609.40–609.60 | 593.00–608.60 | 795.20–854.30 | 762.10–818.40 |
| 3 | repeat | 377.60–391.00 | 393.50–426.20 | 494.20–500.90 | 480.00–505.30 |
| 3 | edge | 394.40–412.40 | 372.90–381.30 | 533.40–558.30 | 516.20–523.10 |
| 3 | unchanged | 35.00–40.30 | 36.10–39.00 | 37.50–38.30 | 39.60–40.40 |
| 3 | verdict | 4.09–4.38 | 4.12–4.59 | 5.83–5.85 | 6.02–6.04 |
| 6 | first | 286.50–318.40 | 273.70–307.10 | 410.30–427.60 | 366.10–443.00 |
| 6 | repeat | 227.30–254.90 | 214.60–249.20 | 282.70–290.40 | 293.90–303.70 |
| 6 | edge | 255.40–292.70 | 223.20–247.50 | 338.70–390.20 | 305.00–315.10 |
| 6 | unchanged | 36.60–41.80 | 46.20–50.20 | 38.30–39.20 | 40.10–41.90 |
| 6 | verdict | 3.85–9.76 | 3.89–5.81 | 5.82–6.21 | 6.01–6.02 |

Old-generation peaks (MiB), K=1 / 3 / 6: parent 350.17 / 407.27 / 469.14; candidate 397.08 / 454.93 / 526.44.
The retained auxiliary graph costs memory; K=6 still fits comfortably inside 4 GiB. No memory saving is claimed.
The edge-changing request is the gain: K=1 491.3 → 462.9 ms and 733.7 → 689.9 MB; K=6 255.4–292.7 →
223.2–247.5 ms. First-request wall time and K=3 repeat did not improve in this pair; K=6 unchanged requests were
slower (46–50 ms versus 37–42 ms). The candidate does not meet the final changed-input target: program hashing,
current-result misses and host classification still cost more than the changed inputs.

Foreign boundary includes the dirty konserve gitlink, unrelated hook evidence, and in-flight edits to
`src/seon/context.clj` and `test/seon/context_test.clj` that appeared during this window. They are excluded from every
candidate. The pre-existing three-argument host-exclusions test remains excluded; green host vars are named
individually. Done/merge, context, test-step, db-flow, runner and nearest-selection proofs are named in the commit gate.

## Slice 4: indexed result-cache misses

The miss in `seon.test/carried-results` calls `seon.test/recorded-outcomes` on source branches. That owner scanned
all `:seon.test.member/reach-digest` and `:seon.test.run/members` datoms, then filtered the requested identities.
The smaller composition is two AVET seeks: the missed reach digests, then those members' owning runs. The existing
reach-digest attribute gains `:seon.db/index true`. The follow-up below uses the native pattern-search owner so older immutable values remain readable.
No result entity or lifecycle attribute is added. Datahike's `datahike.schema/find-invalid-schema-updates` accepts
monotonic index addition, and the transaction backfills AVET before publishing the new value.

Own-branch installation evidence: `change-cost-fold` called `seon.schema/register!` with the exact new form in
`resources/seon/schemas/seon.test.member.edn`. `result/e39d1aed1f650` returned the new declaration and a usage check
of 0 stored values / 0 failing, 46 ms / 48.9 MB for the form. The automatic test step was **not green**: 13 reds
and a pending tail, including `seon.cluster.host-test/an-sci-send-from-the-cluster-supplies-its-handle-and-resumes-a-stopped-agent`
whose test thread remained live at its bound. Complete request `result/caaa31bebfa1f`: 12,924.7 ms, 7,236.9 MB
allocated, 1,838,066 calls. No further SCI requests were made on that branch while this pending work was unresolved.
The recorded `seon.agent-call-edges-test/admitted-declarations-retain-call-dependencies-across-turn-order` error
calls `seon.test/reach-digest` with string `"my.agents.edge-probe/target-test"` where the installed contract requires
a qualified symbol. Other reported reds concern older turn/host fixtures and need separate classification;
this broad automatic result is not claimed as proof of the index slice. The candidate archive's named tests
and publication/reload are its proof. Default was not stopped or restarted.

The explicit miss stage evicts only the narrow test's `[symbol reach-digest input-digest]` entry, then measures
`seon.test/carried-results`, the same owner called by an eval request's test runner. Each source branch holds
26 tests plus repeat executions; one requested outcome is recovered. The no-match regression in
`seon.test.branch-reuse-test/a-result-carries-to-another-branch-of-the-same-program` was red on the parent
(`efe8267ab1fa`: 33 passes / 1 failure, three raw reads versus one). Candidate `4a53cdf8487f` passed all 34
assertions, including carried greens, red counts and red reports after cache eviction.

The final pair held Git HEAD at `134d66ed6` throughout both checks; parent `1cff1cb8d1d8`, candidate
`4963a5476170`, each two tests / 24 assertions. Checks ran sequentially; other lanes were active on the host.
Attribute only the miss-stage gain to this small change: the much larger full-request wall-time differences
are not matched by their allocations and are not claimed as this slice's speed-up.

| K | Stage | Parent ms | Slice 4 ms | Parent allocation MB | Slice 4 allocation MB |
|---|---|---:|---:|---:|---:|
| 1 | first | 1780.70 | 1458.60 | 1321.90 | 1322.40 |
| 1 | repeat | 704.00 | 513.80 | 671.80 | 671.40 |
| 1 | edge | 719.50 | 529.60 | 698.70 | 693.20 |
| 1 | unchanged | 55.70 | 48.10 | 39.40 | 38.60 |
| 1 | miss | 2.09 | 1.61 | 0.86 | 0.28 |
| 1 | verdict | 8.91 | 7.82 | 6.10 | 6.06 |
| 1 | root | 130.10 | 117.40 | 141.20 | 139.60 |
| 3 | first | 778.00–786.60 | 755.00–759.90 | 791.50–843.80 | 795.20–819.20 |
| 3 | repeat | 502.70–522.40 | 456.60–465.50 | 489.30–517.30 | 484.20–507.60 |
| 3 | edge | 648.20–698.90 | 635.30–655.20 | 662.50–715.40 | 656.90–706.50 |
| 3 | unchanged | 52.40–57.50 | 43.40–44.70 | 40.30–40.80 | 39.70–40.30 |
| 3 | miss | 1.32–2.17 | 0.66–0.95 | 1.61–1.62 | 0.45–0.45 |
| 3 | verdict | 5.98–8.54 | 4.80–4.99 | 6.08–6.09 | 6.03–6.04 |
| 6 | first | 343.60–373.60 | 284.10–337.80 | 382.20–404.60 | 367.40–381.80 |
| 6 | repeat | 296.40–313.30 | 302.00–338.00 | 301.20–304.60 | 292.50–303.70 |
| 6 | edge | 482.60–527.10 | 460.70–498.00 | 491.40–522.40 | 486.60–511.10 |
| 6 | unchanged | 53.00–63.30 | 45.60–62.20 | 41.70–44.50 | 41.10–43.10 |
| 6 | miss | 1.86–2.50 | 0.57–0.78 | 2.97–2.97 | 0.71–0.71 |
| 6 | verdict | 4.77–6.50 | 4.63–10.58 | 6.06–6.07 | 6.02–6.02 |

Old-gen peaks MiB, K=1 / 3 / 6: 501.22 / 520.75 / 573.04 → 457.38 / 490.61 / 542.08.
The cache-miss recovery stage at K=6 falls from 1.86–2.50 ms / 2.97 MB to 0.57–0.78 ms / 0.71 MB. It seeks only
matching content; it does not enumerate other tests' executions or runs. Repeated executions of the same requested
content can still produce several matches. Source-branch discovery still visits the branch heads it searches.
Candidate publication/reload took 10.57 s in the paired run (one schema index and its consumers); this is a
one-time publication cost, not a sub-second request claim. Own branch registration's broad red test step remains
an unresolved verification boundary, separately recorded above.

Still separate: `seon.test.runner/current-results` misses reconstruct all requested results from member histories.
This slice fixes the cross-branch result-cache recovery owner; it does not claim that the verdict's held-result fold
is complete. That owner and the task-proof whole-program hash are next.

Before Slice 4's gate, shared-tree clj-kondo (`src script test --cache false`) found one foreign error:
`test/seon/test_test.clj` referenced unresolved `sut/red-evaluators` in another lane's in-flight work.
The slice's candidate excludes that file; its archive lint must be zero errors before the commit can proceed.
Other foreign boundaries now include source/turn edits, reference schemas, task resources and competency files.

## Indexed reads on older immutable values

The first index slice incorrectly assumed every source snapshot already held the new AVET index. An older
immutable value does not. The regression `seon.db-test/stored-patterns-read-values-from-before-an-index-was-added`
proves its raw AVET is empty, while native pattern search returns the same matching triple before and after
index adoption. `seon.db/undecoded-search` now composes `datahike.db.interface/search`;
`datahike.db.search/get-search-strategy-impl` chooses AVET when indexed and AEVT plus its value predicate otherwise.
This replaces the extended raw-index API, with no bespoke fallback or snapshot mutation. On older values the
read remains proportional to that attribute's facts until the branch adopts the index; correctness is preserved.

Regression run `1464641a1775`: 2 tests, 16 assertions, green. Same-parent end-to-end pair on `b95b83af0`,
parent `3cb94e62a892` and candidate (see raw operator output), both 2 tests / 24 assertions:

| K | Stage | Parent ms | Native search ms | Parent allocation MB | Native search allocation MB |
|---|---|---:|---:|---:|---:|
| 1 | first | 1056.40 | 1039.10 | 1325.80 | 1325.70 |
| 1 | repeat | 450.30 | 464.60 | 672.40 | 676.30 |
| 1 | edge | 500.90 | 471.00 | 689.90 | 691.10 |
| 1 | unchanged | 35.70 | 35.30 | 38.90 | 38.80 |
| 1 | miss | 1.14 | 0.99 | 0.28 | 0.28 |
| 1 | verdict | 4.73 | 4.57 | 6.06 | 6.06 |
| 1 | root | 74.70 | 73.40 | 139.20 | 139.30 |
| 3 | first | 542.80–556.80 | 518.40–533.30 | 791.20–825.90 | 767.20–826.10 |
| 3 | repeat | 362.50–392.20 | 363.50–382.10 | 499.50–509.50 | 480.00–507.60 |
| 3 | edge | 477.80–488.20 | 476.00–489.20 | 680.10–706.10 | 689.00–710.60 |
| 3 | unchanged | 33.10–36.20 | 33.40–36.90 | 39.90–41.10 | 40.60–41.40 |
| 3 | miss | 0.41–0.56 | 0.43–0.49 | 0.45–0.45 | 0.45–0.45 |
| 3 | verdict | 4.49–4.60 | 4.25–4.46 | 6.03–6.04 | 6.08–6.10 |
| 6 | first | 253.60–289.10 | 272.30–277.80 | 368.50–388.00 | 385.30–403.00 |
| 6 | repeat | 213.50–233.10 | 222.00–254.90 | 292.30–301.40 | 298.60–305.80 |
| 6 | edge | 369.40–408.00 | 419.90–442.00 | 488.30–512.80 | 491.20–520.40 |
| 6 | unchanged | 37.00–48.10 | 36.40–42.90 | 40.80–43.60 | 41.40–43.60 |
| 6 | miss | 0.45–0.65 | 0.46–0.97 | 0.71–0.71 | 0.71–0.71 |
| 6 | verdict | 4.07–4.71 | 3.66–4.23 | 6.01–6.02 | 6.07–6.07 |

Old-gen peaks MiB K=1/3/6: 530.80/543.61/585.19 → 531.75/549.08/590.14.
The indexed miss retains the prior allocation (0.28/0.45/0.71 MB); sub-millisecond timing varies.
This is a correctness repair, not a new performance claim; broader request variation is not attributed to it.
No additional default SCI request or live-chain operation. Foreign uncommitted konserve and test-data evidence
are excluded from the archive.
