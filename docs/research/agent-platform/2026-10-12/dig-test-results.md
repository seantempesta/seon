---
title: "Dig: which tests are red, and which reds a change caused"
type: research
status: dig, input to the planners
created: 2026-10-12
---

# Dig: which tests are red, and which reds a change caused

Question (owner, 2026-10-12): "What is the seon agent version of this, how do we surface it in seon, and only then how do we
surface it to your agents using the same mechanism?" The mechanism is a red baseline: stored facts that say which tests
are red on main and which reds a branch's change made. This dig finds what already exists, what the owner and AGENTS.md
already ruled, what main holds today, and the open design questions. It proposes no implementation.

Basis: HEAD 64668ecae. Read-only checks on default's JVM through `mcp__seon__eval_clj` (mode jvm, queries only), 2026-10-12.

## 1. Answer in one paragraph

Seon already has most of the mechanism, at the agent's side. A run is data (`:seon.test.run/*`, members
`:seon.test.member/*`), keyed by reach digests. `seon.test.runner/result-at-reach` reads a test's newest result at an exact
reach digest on any database value. `seon.test/fork-point-red` and `seon.test/change-reds` answer "red on the branch and
not red at main's head at the same reach", and `seon.cluster.branch-evidence/branch-evidence` already sorts a branch's
reaching tests into verified, red-before and unproven. What is missing is the baseline itself: main holds zero runs
(confirmed), so every "is this red mine?" answer is either a fresh run in a fork at the fork commit (`fork-point-red`,
bounded by a time limit) or unknown. The Seon-native version is therefore: record main's runs once per commit (a round
or a merge) as ordinary admitted runs on main, derive "caused" and "red before" by reach-digest comparison on read,
render only the non-green rows in the agent's current-state block, and have `bin/lane commit --branch-evidence` and
`bin/seon check` ask the same `seon.test` question through the same entrance. No baseline file. Unknown stays unknown.

## 2. What exists (verified at code)

### 2.1 Storage: runs, members and results

- Schemas: `resources/seon/schemas/seon.test.run.edn` (`:seon.test.run/run`, `:seon.test.run/result-row`, `:seon.test.run/admission`,
  `:seon.test.run/provenance`, `:seon.test.run/policy`, `:seon.test.run/evaluator`, `:seon.test.run/stopped`). Member,
  selection and report schemas: `seon.test.member.edn`, `seon.test.selection.edn`, `seon.test.report.edn`,
  `seon.test.failure.edn`, `seon.test.runner.edn`, `seon.test.timing.edn`.
- Run identity: `:seon.test.run/id`, `:seon.test.run/program-digest`, `:seon.test.run/input-digest`, `:seon.test.run/basis-t`,
  `:seon.test.run/git-sha` (present only on platform runs, read by `seon.cluster.branch-evidence/platform-run?`),
  `:seon.test.run/branch` (destination) and `:seon.test.run/tested-branch`.
- Admission writes the selection atomically: `seon.test/admit-run` (`:seon.test.run/members` component,
  `:seon.test.run/covered-by` for reused members, `:seon.test.run/exclusions` for decisions not executed).
- Recording: `seon.test.runner/record-tx`, `complete-members`, `commit-results!`. Interrupted or unfinished members are
  recorded as unknown, never green (`:seon.test.run/stopped`: `:deadline`, `:member-unfinished`, `:recording-refused`,
  `:platform-red`, `:program-unknown`).
- Read side: `seon.test.runner/run-results` (total results of one run, incl. reused members),
  `seon.test.runner/latest-results` (newest admitted execution per test; an incomplete latest is `:seon.test/result-unknown`,
  never an older green), `seon.test.runner/result-at-reach` (added in 3fe23b2c0: newest execution recorded under exactly one
  reach digest, however many later runs exist), and the private `newest-results` both share.
- Reach digests: `seon.test.runner/reach-digests` (static closure of each test, refuses `:seon.test/unknown-error` when a
  declaration has no stored `:seon.program/definition-digest`), `seon.test.runner/reach-graph`, `tests-reaching`,
  `reach-memberships`. Member field: `:seon.test.member/reach-digest`.
- Input roots for selection: `seon.test.cache/input-roots` (declared deps.edn roots, local/root deps, gitlinks).
  `seon.test.cache/input-paths`, `changed-inputs` and `widening-path?` decide what a file change can widen.
- Selection: `seon.test/select`, `seon.test/green-members`, `seon.test/reaching` (for `:seon.test/changed`),
  `seon.test/verified?` (latest result green, reach digest equal, input digest equal, source present, no fixture observation).
- Recorded-result helper: `seon.test/recorded-result` (one test's latest result or `unknown`).

Verified live (2026-10-12, `mcp__seon__eval_clj`, mode jvm, cluster default, queries only):

| Read | Result |
|---|---|
| runs on main (`:seon.test.run/id` on the default head) | 0 |
| members on main (`:seon.test.member/symbol` on the default head) | 0 |
| agent branches (`:seon.agent/branch` values) | 37 |
| branches holding at least one run | 7 |
| runs across those 37 branches | 33 (largest branch 9) |
| query cost per reply | `:ms` 23 to 90, reads 1 to 38, settle 26 to 50 ms |

The 33 branch-held runs are the lanes' own runs. The 2,832-test central round did not land on default: it ran on an isolated
root (`tmp/test-runs/central-round3`, per `docs/research/agent-platform/2026-10-12/central-round.md`), which was then nuked.
Its answers survive only as `tmp/` text and commit messages (aee027806, a997a81ef, 5d7567be1). That is why main holds
nothing.

### 2.2 What an agent's reply already shows

- `seon.context/step-observation`, `step-status`, `step-section`: the agent's head entries, broken tests
  (`:seon.context/broken`), new reds (`:seon.context/new-red`), and the fork-point map (`:seon.context/fork-point`, true when
  the test was red there). Rendered as `:seon.context/broken-tests`, `:seon.context/product-tests`,
  `:seon.context/new-red-tests` by `seon.context/current-state`.
- `seon.context/base-results` (context.clj ~line 209): a proof's base result is read on the fork commit via
  `seon.test.runner/result-at-reach`. A "satisfied" prove step opens no model turn (346645d56, M1).
- `seon.turn/record-broken!` (turn.clj ~5475): judges the agent's step against its fork point. Heads are its obligations
  and the tests reaching its whole change; reds there go to `seon.test/fork-point-red` in the agent's own world. Writes
  `:seon.agent/broken` and `:seon.agent/new-red` on the agent's row on its branch. Nothing is written on main.
- `seon.turn/test-step` (turn.clj ~5516): runs the held task's proofs, then the reaching tests, then `record-broken!`, within
  `:seon.config.test/step-time-limit-ms`. Its summary rides in `:seon.turn.loop/broken`. Failure becomes a diagnostic
  beside the values, never a replacement.
- `seon.test/fork-point-red` (test.clj ~3042): for each test present at the commit, uses the result recorded at its reach
  (inherited evidence), else runs it once on a branch forked at that commit, recorded on that fork only, released after,
  bounded by `limit-ms`. This is the one place an agent pays a run per red to learn its base.

### 2.3 Main-side judgment and the gates

- `seon.test/change-reds` (test.clj ~3076): of the reds on a branch, the ones whose same test is not red at main's head.
  Main's side is `fork-point-red` at main's commit. Caller: `seon.cluster.source` (around lines 967 and 1047), in the merge
  acceptance `red-test-verdicts`, which gives each red the verdict `:red` (the change's) or `:red-on-target`.
- Task `seon.test/change-reds-judges-a-test-main-does-not-hold` (6d76525e2) is the closest prior fix. The task `change-reds`
  itself was deleted in 82315155b.
- `seon.cluster.branch-evidence/branch-evidence` (src/seon/cluster/branch_evidence.clj): per file, declarations differing
  from the branch; changed declarations by behavior (`seon.db/behavior-digest`, so a docstring-only change demands no
  test); reaching tests of each change sorted by `test-evidence` into `::verified`, `::red-before`, `::unproven`,
  `::unreached`, `::platform-proven`. A test is red-before when red on the branch AND `seon.test.runner/red-at-current-reach?`
  on the head. `verdict` is proven only with every bucket empty except red-before, and red-before must be named in
  `:seon.source/accepted-red-before`.
- `bin/lane commit --branch-evidence AGENT [--accept-red-before TEST]...` (bin/lane lines ~10 to 61, 412 to 461) asks that
  question over the live root's prepl. Exit codes: 0 proven, 2 not, 3 unavailable.
- `bin/seon check` and `bin/lane commit --test` (host-bound slices) publish the candidate archive on disposable branches, boot a
  throwaway JVM and run the named tests through the one runner. Results are recorded on those disposable branches and
  discarded; no baseline is kept.
- `script/seon/central_round.clj`: one `seon.test/run` per batch of 25 (`:seon.test/policy :named`, `:seon.test/rerun? true`,
  `:seon.test/include-long? true`, bound from `seon.test/declared-bound-ms`), answers appended to an `OUT` file through the
  value printer. Writes nothing to main by itself.

### 2.4 Central round numbers (for scale)

From `docs/research/agent-platform/2026-10-12/central-round.md` and commit aee027806: 2,832 tests; 2,486 green, 229 red,
117 error (round 3, HEAD 2319152dc). Bulk pass 114 batches, 61.2 min, 5 at the 240 s limit. Round 2 (e0bd37443): 254 red, 120
error. The number changes per round because the test population and the program change; the per-test result is what must
be stored.

## 3. Prior attempts, rulings and where they came from

### 3.1 Owner rulings that bind this design

| Ruling | Source | Consequence here |
|---|---|---|
| "one full test run saved in the database, then only changed runs"; "test results that are tied to the program graph are part of the program graph" | memory `feedback_test_evidence_persists_2026_09_23` (owner, 2026-09-23) | Main's baseline must be stored, not re-derived per request. **Conflicts** with the current schema: `seon.test.run/run` carries `:seon.program/partition :seon.data`, so runs are data, not program. The planners must decide which the owner means; the schema is the newer fact. |
| "a cost regression is an ordinary red test result, compared with the test's last green run on main" | memory `feedback_cost_regressions_fail_tests_2026_10_09` (owner, 2026-10-09) | "Main's result at the same reach" is the comparison base for cost reds too. |
| "a test already red on main stops it for root" | AGENTS.md, Proof and performance; commit b2f1b8b29 (2026-10-10) | Root needs main's red set as a fact, not a judgment per merge. |
| "Require current positive proof. Unknown or red cannot reuse green." | AGENTS.md, Proof and performance | An absent main result is unknown, never green and never red. |
| "Classify each red from stored runs by the commit that changed its behavior" | AGENTS.md, "Prove each slice before it is committed" | The stored per-commit runs are what classification reads. |
| "Test-selection inputs are `seon.test.cache/input-roots`" | AGENTS.md (same section) | Baseline reach keys use the same inputs. |
| "Steer by totals, not reds" (2026-09-28); "Don't just chase reds. See the forest." | memory `feedback_steer_by_totals_not_reds_2026_09_28` | A baseline count per commit (red, error, unknown, green) belongs in the reply's headline, not a list. |
| "Never print huge text; stop and fix" and "ugly output is a defect" | memory feedback 2026-10-07 and 2026-09-25 | Rendering shows the changed rows only, with counts. |
| NO GUARDS OR FENCES | AGENTS.md, How to think | `--accept-red-before` is an exemption list. If main's baseline is stored, the list is unnecessary and should go. |
| "Test with the same mechanism humans and agents use" | memory `feedback_one_test_system_for_humans_and_agents_2026_09_25`; `feedback_one_shared_jvm_no_parallel_systems_2026_09_23` | Lanes read the same `seon.test` answer through eval_clj; no parallel runner or JVM for the question. |
| "AGENTS.md is evergreen" and "Fable audit #2: store the central round per batch and let `bin/seon check` report transitions against the merge base" | `docs/research/agent-platform/2026-10-12/fable-audit.md` lines ~38 to 42, 155 to 158, 177, 232 to 233 | Root's proposal and the audit's recommendation. The audit asked the owner for decision 3 (authorize a Sonnet triage lane and a stored baseline per batch, "Recommend yes"). I found no recorded answer in the repo. |

### 3.2 Earlier attempts (and why they stopped short)

- 2026-09-23 (`hybrid`, `project_*` notes): per-test digests as program rows; resets wiped evidence (73 of 2,078 tests had green
  evidence). Fixed by never resetting (`bin/seon reset` keeps runs).
- 2026-10-09: `seon.health/regressions` existed as a health finding only; the owner asked for failing tests (COST-REG card).
- 2026-10-11: `change-reds` introduced (f68dd4086: "one predicate for the merge gate and the turn's test step"). Its main side
  runs tests on a fork because main stores nothing: the cost of the missing baseline, paid per call.
- 2026-10-12 M1 (85b8203a3, 3fe23b2c0, 346645d56): base result per proof derived on read via `result-at-reach` at the base reach,
  "never stored" (hybrid-done-and-merge.md line 25). **This is the owner's current rule for the agent side.** A main baseline
  stored as runs on main fits it: it is a recorded run, read by reach digest, never a copied value.
- 2026-10-12 central round (aee027806 and earlier): reds counted by hand in commit messages; "33 vs 34" (172b23e7c) was the
  only cross-check. No stored per-test baseline.

### 3.3 Docs to read before planning

- `docs/research/agent-platform/2026-10-12/central-round.md`, `fable-audit.md` (sections 4 to 6), `hybrid-done-and-merge.md`,
  `dig-done-and-merge.md` (lines 51, 108, 309: fork-point judgment).
- `docs/research/agent-platform/2026-10-11/revert-verification.md` and `revert-baseline.edn` (a prior stored-red comparison,
  done by hand on isolated roots).
- `docs/research/agent-platform/2026-10-10/astra-test-run-cost.md` (run cost per test; not re-measured here).
- Tasks: `seon.test.runner.edn` (line ~37 `verified-recomputes-reach-digests-for-the-whole-program`, line ~43 the reach graph
  rebuild), `seon.cluster.source` tasks for red-test acceptance.

## 4. Design questions the planners must answer

1. **How does main get its results?** Options, to be priced: (a) root runs the compiled tier on default at each restart batch
   and records it as one run per batch on main (the central round does this today on an isolated root; the change is only where
   it runs); (b) each landed merge records its own reaching run on main, so main's baseline grows by merge; (c) derived only,
   from merged branches' runs at equal reach digests (no full-tier round). (a) gives full coverage but a 35 to 61 minute
   round; (b) is cheap per merge and leaves untouched tests unknown; (c) is cheapest and leaves gaps. The owner decides.
2. **What is "red because of my change" as a query?** Proposed definition, from the code that already exists:
   for test T on branch B with reach digest R_B(T): caused = red on B at R_B(T), and main has a recorded result for T at
   exactly R_B(T) that is green. red-before = red on B and red on main at R_B(T). unknown = main has no result at R_B(T)
   (includes the case where the branch changed T's closure, so main's reach differs). unknown never reuses green and is never
   counted as caused without saying so. This is `red-at-current-reach?` applied to main with the branch's reach, not the
   head's. It removes the accepted list.
3. **Where is it rendered?** Headline counts first (main baseline: green, red, error, unknown; branch: caused, red-before,
   unknown), then only the caused and unknown rows, each as test symbol plus one line of the failure identity. Red-before is
   counted, not listed. Token budget to be measured by the render owner; target a fixed small block.
4. **How do lanes read it?** The same function through the same entrance: `seon.cluster.branch-evidence/branch-evidence`
   already runs in the live root. A lane asks it through `mcp__seon__eval_clj` (mode jvm) or `bin/lane commit
   --branch-evidence`, which is that same call over the prepl. `bin/seon check` should call `seon.test` for the transition
   against the merge base and stop booting a separate JVM for the verdict (host-bound slices only).
5. **Cost.** The fork-point run per red is the cost to remove. Measure, before choosing: (i) `fork-point-red` per red test on
   a warm JVM; (ii) the reach-digest read `result-at-reach` on default (23 to 90 ms per reply observed here, no reach
   recomputation); (iii) the cold reach digest (see section 5). A stored baseline turns (i) into (ii).

## 5. Edge cases found so far

- **Main holds nothing.** Confirmed: 0 runs and 0 members on default. Any design that reads main's baseline reads unknown
  until a run is recorded there. The central round's runs disappear with their isolated root (`nuke --force`).
- **Flaky tests.** Root reports `seon.task-ingestion-test` (its census call count varies). I did not reproduce it. A test whose
  result differs for identical inputs at the same reach must read unknown, not red or green; the planners need a rule
  for repeated identical runs (count, or unknown).
- **Red at HEAD with identical counts.** Equal pass/fail counts do not prove the same red. Compare failure identities:
  `seon.test.runner` builds `report-identity` and `failure-identity` (runner lines ~212 to 230) and results carry
  `:seon.test/failing-assertions`. A test red on main with a different failing assertion is a new red.
- **Timeouts and bounds.** A bound that fires is not termination (`:seon.test.run/terminated?` false). The member reads unknown.
  The step limit (`:seon.config.test/step-time-limit-ms`) can leave tests `pending`. Pending is not red.
- **Red evidence is reused.** `:seon.test.run/policy` documents that matching red evidence on the same program is reused under
  every policy unless `:seon.test/rerun?`. Correct for cost, but a stored main baseline must say which reds are stale.
- **Host-bound tests.** A branch's change to a host-bound declaration cannot run interpreted. `branch-evidence` demands a
  platform run (`:seon.test.platform/proven`, `platform-run?`). A main baseline keyed by `:seon.test.run/evaluator` (:compiled
  on root, :interpreted on agents) must not compare across evaluators. 205 tests were red only when run interpreted
  (bff46841f, interpreted-tier.md).
- **Deleted machinery.** A test deleted on the branch (`:seon.test.run/retracted-definitions`) has no reach on the branch. Its
  main red should be listed as removed, not as caused. `branch-evidence` already routes gone tests through `reach` on head.
- **Unknown reach digest.** `seon.test.runner/reach-digests` refuses when a declaration has no stored definition digest.
  Unknown evidence must never key reuse (the code says so). The baseline's keys inherit this refusal.
- **Cold reach digests.** Recorded costs disagree: `seon.test.runner/verified-recomputes-reach-digests-for-the-whole-program`
  (task in `seon.test.runner.edn`) and its neighbour give 5.9 s / 4.2 GB cold for `reach-digests` (the task figure); the
  Fable audit gives 2.1 s / 4.0 GB; root's brief says 3.1 s / 6 GB. These are unreconciled. I did not measure a cold reach
  digest. Warm reads on default are 23 to 90 ms. A planner must measure cold before choosing a design that depends on it.
- **Stored green is not verified.** `seon.test/verified?` also checks input digests and the source; a green row recorded
  before an input change is not current. A baseline read must apply the same test.

## 6. Library seams

- **clojure.test report maps.** Runner captures reports through its own `capture-event!` / `report-event!` (test runner
  lines ~235 to 350) and the test's report counter. Use the report maps as the failure identity source. Do not add a second
  report format.
- **kaocha.** Not used. No `kaocha` dependency in deps.edn or bb.edn, and no reference in `src`, `test` or `script`
  (checked 2026-10-12). The AGENTS vocabulary table lists it as the test library. Either adopt its result/testable model or
  correct the table; do not add a parallel result shape.
- **Datahike history and as-of.** `seon.db/as-of` (db.clj line ~3592) and `seon.db/history` (line ~3579) answer "result at a
  commit". A recorded run on main is already a datom with its basis-t; `runner/result-at-reach` reads it on any database value,
  so a commit id is enough to name the baseline. Commit ids are the identity; `db/commit-id` gives it.
- **Datahike branches.** `d/branch!` is a pointer; a run recorded on main is visible to every branch forked after it. The
  lineage read in `latest-results` (inherited runs) already gives agents the main baseline without a copy.
- **Malli.** Results and reach digests are validated by `:seon.test/result`, `:seon.test/reach-digests`, `:seon.test/latest-results`.
  A baseline row type would be one more named schema; prefer the existing run schema.

## 7. Discrepancies to resolve before planning

1. Run partition: memory says test evidence is program; `seon.test.run.edn` says `:seon.data` (AGENTS "The program is entities"
   says evaluations, calls and live results are data). Decide: runs are data keyed by digests, with the reach digest on the
   member as the program link (the current design).
2. Cold reach digest cost: three figures (see section 5).
3. `change-reds` task deleted (82315155b) while its function stays and is called by merge acceptance. The function needs a
   task or a test naming it again.
4. `--accept-red-before` exists for a reason the stored baseline removes. Decide whether to delete it with the baseline.
5. Owner decision 3 of the Fable audit (stored round per batch) has no recorded answer in this repo.

## 8. Recommended next step for the planners

Specify the baseline as runs on main only, keyed by reach digest, with one derivation (`seon.test` judgment: caused, red-before,
unknown) called by `record-broken!`, `change-reds`, `branch-evidence` and `bin/seon check`. Measure the fork-point run cost and
the cold reach digest on default first, since they decide between options 4.1a and 4.1b. Do not write a baseline file.
