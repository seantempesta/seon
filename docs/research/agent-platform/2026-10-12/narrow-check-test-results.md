---
title: "Narrow check of hybrid-test-results.md"
type: research
status: lane narrow-tr, 2026-10-12. Checks `hybrid-test-results.md` (f8565bffa) against HEAD 73bd74de9 and default (jvm mode as root, read-only; one disposable `fork-point-red` fork of main, released; agent `narrow-tr` in sci mode on its own branch, retired after). No main write, no reset, no reload, no uncommitted source loaded. Labels: DESIGN (the design's content is wrong or missing) and MECHANICAL (a name, count, file, caller or input to correct, design unchanged).
---

# Narrow check: which tests are red, and which reds a change caused

Result: question 1 holds 23 of 27 (2 false, 2 not reproducible); question 2 finds 2 missed or unconverted callers and 1 unplaced behavior in 6 phases; question 3 resolves all 6 proofs (T6 only under Q1-B or Q1-C). The added question: **no phase covers it**. 8 failures: 1 DESIGN, 7 MECHANICAL.

## 1. Live claims

| # | Claim | Result | Evidence |
|---|---|---|---|
| 1 | Main holds 1 run and 1 member | **false** (MECHANICAL, drift) | jvm, default: 5 runs (`764ebff07cf9` 06:34 and four at 06:58, all `:named`, `:compiled`, `:cluster-default`); 167 member entities with a symbol; `latest-results` over them answers 56 tests, 7 red (the three open designed red proofs among them: `my.task-test/current-answers-the-held-steps-instructions`, `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn`, `my.task-test/a-send-back-with-positional-arguments-is-answered-with-its-example`). The conclusion (no baseline for 2,876 tests) stands; the "main holds nothing for the test" case (§3) is now false for those 56 |
| 2 | Run `764ebff07cf9` is `:named`, request `:compiled`, `:cluster-default`; its member ran `:interpreted` | holds | same query; evaluators over members: `:compiled` 109, `:interpreted` 1 |
| 3 | 2,873 test declarations, 50 agent branches | **false** (MECHANICAL, drift) | 2,881 `:seon.test/sym` (2,876 not fixtures); 54 `:seon.agent/branch` values |
| 4 | `designed-red?` accepts a plain `:fail` | holds | `(#'seon.context/designed-red? {… :proof-failures [{:type :fail}]})` → `true`; `seon.context/designed-red?` tests `(= :fail (:type %))` |
| 5 | `red-at-current-reach?` on main for `each-step-kind-is-done-by-its-one-rule` is false | holds | jvm → `false`; for `my.task-test/current-answers-the-held-steps-instructions` it is `true` today (new datum, claim 1) |
| 6 | A reached test has a different reach digest at the head | holds | sci `narrow-tr`: `f-is-one` `66f93e8a`→`5bfb2c9d`, `f-is-two` `b175133e`→`8b6e8be0` |
| 7 | `(result-at-reach base-db head-reach)` → `{}` | holds | same probe → `{}` |
| 8 | The base results stay readable at the base digests | holds | `(result-at-reach d base-reach)`: `f-is-one` pass 1, `f-is-two` fail 1; at head digests the reverse |
| 9 | A no-task agent's block holds only `:seon.context/turns`, and its reply shows `:seon.turn.loop/red [f-is-one]` with no origin | holds | `(keys (seon.context/current-state))` → `(:seon.context/turns)`; reply `{:seon.turn.loop/tests {:seon.turn.loop/reaching {:seon.test/passed? false :seon.turn.loop/red [ntr.probe/f-is-one]}}}`; step 698 ms, `:seon.cost/step` 792 MB for two tests of 24 ms |
| 10 | `(#'seon.context/main-database branch-db)` answers main | holds | `[:cluster-default 5]` |
| 11 | `reach-digests` for three real tests 322 ms / 502 MB first, 1.75 ms after | holds | 390 ms, 600 MB, 6,813 reads first (7,662 over the request); 1.30 ms second |
| 12 | `result-at-reach` on main 0.15 ms, `{}` | holds | 0.151 ms, `{}` |
| 13 | `fork-point-red` per red 294 ms / 249 MB; main unchanged; the child's run is gone | holds | 340 ms, 247 MB, 66 reads, 7 transactions; commit id equal before and after; runs 5 → 5; answer `{each-step-kind-is-done-by-its-one-rule false}` |
| 14 | `fork-point-red` merges `(zipmap present (repeat false))`; `step-observation` builds `(not (broken s))` | holds | `seon.test/fork-point-red`, `seon.context/step-observation` |
| 15 | `release-context!` → `registry/retire-branch!` | holds | `seon.cluster.agent/release-context!` calls it when `:seon.agent/owns-branch?` |
| 16 | `admit-run` refuses a non-snapshot run carrying `tested-branch` (`:seon.test/cluster-mismatch`) | holds (code; not run, as the hybrid) | `seon.test/admit-run` |
| 17 | `:seon.test.run/run` is `:seon.program/partition :seon.data` | holds | `seon.test.run.edn` |
| 18 | 205 tests red only interpreted | holds (MECHANICAL: cited as `interpreted-tier.md` without its path) | `docs/research/agent-platform/2026-10-10/interpreted-tier.md` line 8 |
| 19 | Central round: 2,832 tests, 114 batches, 5 at 240 s, about 61 min | holds | `central-round.md` lines 373-374 |
| 20 | `record-broken!` judges only a task holder; `test-step` sets `:seon.turn.loop/broken` | holds | `seon.turn/record-broken!` returns `[]` when `sym` is nil; `seon.turn/test-step` |
| 21 | `test-evidence` calls `red-at-current-reach? head` per unverified red and `test/reaching` once per changed function | holds | `seon.cluster.branch-evidence/test-evidence` |
| 22 | `verdict` requires `(every? accepted red-before)`; `bin/lane` writes the accepted trailer | holds | `branch-evidence/verdict`; `bin/lane` lines 423, 452, 466, 530 |
| 23 | `run-candidate-tests!` answers from the exit; `bin/test-check` writes members as EDN under `SEON_TEST_RESULT_FILE`, read by `run-check!` | holds | `seon.operator/run-candidate-tests!`, `bin/test-check` (writes before exiting 1), `seon.test.platform/run-check!` |
| 24 | `batch-form` records on default's connection; `run-round!` contract `:nil`; `-main`'s `record!` is a `spit` | holds | `script/seon/central_round.clj` |
| 25 | `test/seon/central_round_test.clj` has three deftests; `operator_test.clj`, `my/test_test.clj` exist | holds | files read |
| 26 | Token sizes 54 / 364 / 48 | **not reproducible** (MECHANICAL) | the sample step section is not stated |
| 27 | `test-evidence`/`change-reds` run "under `:interpreted`"; `fork-point` keyed by evaluator | **not reproducible** (MECHANICAL) | `seon.test/fork-point-red` takes no evaluator and its child's member evaluator is not observable after release; the key is only checkable once T1 names the argument (F5) |

Count: 27 claims; holds 23; false 2 (rows 1, 3, drift only); not reproducible 2 (rows 26, 27).

## 2. Deletions and their last callers

| Phase | Deleted | rg of callers, tests, quoted symbols, schema references | Result |
|---|---|---|---|
| T1 | boolean fork point; `base-results` body; `fork-point-red`'s false default; `:seon.context/fork-point` boolean schema | `step-status`, `step-observation`, `base-results`, `record-broken!`, `fork-point-red`, `change-reds`, `context_test` table (line 311), `test_failure_facts_test` line 186 (passes `{}`, valid under both schemas), schema lines 32/38/47; no skill or `docs/seon` page names `:seon.context/fork-point`; `turn_test.clj:2095` asserts `:seon.agent/broken`, unchanged | ok. Sequencing (F8): until T2 persists `:seon.agent/unknown`, the block path (`step-observation` with `judged` nil) rebuilds an unknown as red-before |
| T2 | nothing | adds keys only; schema files `seon.agent`, `seon.turn.loop`, `seon.context`, `my.test` edn exist | ok. `record-broken!` early-returns `[]` when no task (`sym` nil) and `step-observation` needs `sym` (`task/obligations`); the no-task head is not named (F6) |
| T3 | `red-at-current-reach?`; `--accept-red-before`; `evidence … -- TEST`; accepted trailer; `:seon.source/accepted-red-before`; `verdict`'s `accepted` | `branch_evidence.clj` lines 137/155/188/193; `bin/lane` (13 lines name it: 10, 16-18, 55, 401-411, 423, 452, 461-466, 530); `seon.source.edn` 195/200; `branch_evidence_test.clj` line 73; tasks `seon.turn.edn:194`, `seon.cluster.source.edn:93`, `seon.task.edn:32`, `seon.operator.edn:83`; docs `RF-2.md:29`, `ROOT-AGENT.md:201`, `agent-loop-ledger.md:169` | **1 miss** (F2): `docs/research/agent-platform/2026-10-08/lane-templates/repl-slice.md:23` instructs `--accept-red-before` (a lane template). Dated evidence (`repl-first.md:87`, `design-astra.md:38`) need no conversion. **1 unnamed input** (F4) |
| T4 | task `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head` | only `seon.operator.edn:81`; no deftest tagged with it; its intent names `--accept-red-before`, which T3 deletes (order-independent) | ok |
| T5 | `-main`'s `OUT`, `record!` spit | usage line and `-main` in `script/seon/central_round.clj`; `central-round.md` invocation; `seon.config.edn:30` cites the doc's path only (unchanged) | **1 miss** (F3): `an-unknown-batch-is-recorded-once-and-never-resubmitted` and `terminal-batches-preserve-evidence-and-advance` call `run-round!` with a `record!` function and assert on `recorded`. **1 unplaced behavior** (F7): an unknown answer has no `:seon.test.run/id`, so ids-only output drops the evidence `record!` keeps today |
| T6 | nothing (Q1-A: empty phase) | the files it names, `src/seon/merge.clj` and `merge-tx`, are absent at HEAD (merge is `seon.cluster.squash/squash-merge` and `seon.cluster.source`); `hybrid-done-and-merge.md` (M7) names them | ok after M7 (F9, note) |

Count: 6 phases; clean 4 (T1, T2, T4, T6); misses 2 (T3, T5) plus F4, F6, F7, F8 specification gaps.

## 3. Red proofs resolve

| Phase | Deftest | File | Red on HEAD today | Result |
|---|---|---|---|---|
| T1 | `seon.context-test/each-step-kind-is-done-by-its-one-rule` (table converted to statuses, plus `{p :green r :pending}` → `:working`, `unknown #{r}`) and new `seon.test.runner-test/the-fork-point-reads-a-base-result-at-the-base-digest` | `test/seon/context_test.clj:302`, `test/seon/test/runner_test.clj` (neighbor `a-result-at-the-base-reach-digest-survives-later-runs` at 521) | yes: `step-status` tests `(false? …)` on the fork point and its input schema is `[:map-of sym :boolean]`; `fork-point` does not exist | resolves |
| T2 | `seon.context-test/broken-tests-show-with-their-failures` gains a no-task agent | `test/seon/context_test.clj:342` (declared long, 30 s) | yes: claim 9 | resolves |
| T3 | `seon.cluster.branch-evidence-test/a-branch-proves-its-files-only-with-current-green-evidence`, the "red on H too" `testing` asserts `proven?` true with `#{}` | `test/seon/cluster/branch_evidence_test.clj:50` (declared long, 60 s) | yes: today it asserts `(false? proven?)` and proves only with `accepted` | resolves |
| T4 | new `seon.operator-test/a-check-red-main-holds-red-does-not-refuse` | `test/seon/operator_test.clj` | yes: no `check-verdict` | resolves |
| T5 | new `seon.central-round-test/a-round-answers-its-run-ids-not-a-file` | `test/seon/central_round_test.clj` | yes: `run-round!` returns nil (`:nil` contract); `:seon.test/run-result` carries `:seon.test.run/id` | resolves |
| T6 | new `seon.cluster.merge-test/main-keeps-the-merge-results-after-the-branch-is-deleted` | `test/seon/cluster/merge_test.clj` | yes under Q1-B or Q1-C; under the recommended Q1-A the phase and its proof do not exist | resolves conditionally |

Count: 6 of 6 resolve; 5 unconditionally, T6 conditional on Q1.

## 4. Added question: is a red at an unchanged reach digest reused as red?

**No phase covers it.** The hybrid's read, `seon.test.runner/fork-point`, answers the base side of a judgment. A request's selection is `seon.test/select`, which none of T1-T6 edits (T1 changes `fork-point-red` in the same file, not `select`). `select` reuses a red only while `(= revisions-now (:seon.test.run/program-revisions run))` (`red-holds?`), among runs of the requesting branch, input digest and evaluator; a reach digest alone never reuses a red ("red never stands in for a change that may have fixed it", `seon.test/select`). So any program write since the run reruns every designed red, however unchanged its reach digest. Main now holds red results for the designed red proofs (claim 1), and `result-at-reach` reads them, but selection does not consult them. The open task `seon.turn/a-read-only-request-re-runs-the-branchs-reaching-tests` (must-read `seon.test/select`, `seon.turn/run-reaching`, `seon.turn/test-step`) stays uncovered; the hybrid's §3 row "Stored green that is not verified" leaves `verified?` and selection as they are.

## 5. Failures

| # | Label | Failure |
|---|---|---|
| F1 | MECHANICAL | Claims 1 and 3 drifted: main holds 5 runs and 56 tests with results (7 red), 2,881 declarations, 54 branches. The "main holds nothing" case in §3 and the red-at-current-reach example in §7 P1 are out of date |
| F2 | MECHANICAL | T3 does not convert `docs/research/agent-platform/2026-10-08/lane-templates/repl-slice.md:23` (`--accept-red-before`) |
| F3 | MECHANICAL | T5 deletes `record!` without converting the two existing `run-round!` deftests that pass it |
| F4 | MECHANICAL | T3: `test-evidence` resolves `:pending` through `fork-point-red`, which takes an execution handle (`:seon.agent/context-source`); `branch-evidence` takes `(store request)` and `:seon.source/branch-evidence-request` has no handle |
| F5 | MECHANICAL | T1: the `evaluator` argument of `fork-point` is not named for `step-observation`, `base-results`, `test-evidence` or `check-verdict`; `fork-point-red` takes none (claim 27) |
| F6 | MECHANICAL | T2: how a no-task agent's head is built is unnamed (`step-observation` needs the task `sym`; `head-entries` is private); `:seon.turn.loop/broken` already exists and `test-step` sets it, not `summary` |
| F7 | DESIGN | T5: an unknown or unfinished batch answer carries no run id; with `record!` and `OUT` gone, the evidence `run-round!` records today for those answers has no home |
| F8 | MECHANICAL | T1 before T2: an unknown from `fork-point-red` is not persisted (`:seon.agent/unknown` arrives in T2), so the block path shows it as red-before in between |

Notes, not failures: T6's `src/seon/merge.clj` and `merge-tx` exist only in `hybrid-done-and-merge.md` (F9: dependency on M7 is stated; the names are not at HEAD); claims 18 and 26 carry citation or sample gaps; claim 16 is code-read in both documents.
