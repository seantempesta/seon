# Pre-brief: TASK-CHAIN slices S2a, S2b-1, S2b-2, S2c-1, S2c-2

Dated evidence, 2026-10-14. Checkout HEAD 09062eec4 with a dirty working tree (uncommitted edits in
`src/seon/fn.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/my/task.clj`, `resources/seon/help.md`,
`test/seon/cluster/db_flow_test.clj`, `test/seon/context_test.clj`, and others listed by `git status`). Counts were taken
over a `git archive HEAD` copy and, for tags, over the working tree. Scanner: a scratch script (not committed) that
tokenizes `.clj`/`.cljc` for `:seon/tasks`, separating code from string literals. Gathering only; no recommendations.

Legend: VERIFIED = read or counted at HEAD or working tree in this session. UNVERIFIED = not checked.

## 0. Headline facts

- **None of the named red proofs exist.** `a-tasks-proof-is-recorded-and-read-under-its-task` (S2a),
  `done-refusals-judges-every-task-alike` (S2b-2), and the S2b-1 proof, which names no `ns/name` at all, are absent from
  `test/`, `src/` and the working tree (`git grep -F`). No stored run exists for them (VERIFIED). The rows S2c-1 and S2c-2
  name no deftest; the TASK-CHAIN §7 proof form (a deftest carrying `ns/name`) is therefore unmet for all five rows
  except S2a and S2b-2, whose names are given but whose deftests are missing (VERIFIED).
- **`seon.cluster.source/done-refusals` does not exist** at HEAD or in the working tree (VERIFIED). Its inputs do:
  `:seon.source/judged` and `:seon.source/refusals` (schema, `src/seon/cluster/source.clj`, `resources/seon/schemas/seon.context.edn`).
- **§6 tag numbers do not reproduce.** The document says 344 symbols in test tag sets naming 247 tasks (39 inside strings),
  33 in `src`. Re-counted at HEAD (test code plus test strings): 387 symbol tokens, 264 distinct names; test code alone
  286 symbols / 207 distinct; test strings 101 symbols / 57 distinct; `src` 36 sites / 33 distinct (VERIFIED). The
  working tree gives test code 292 / 212 and test strings 102 / 58. The doc's method is not known; the S2c-1 acceptance
  must use the one committed script's count, not these numbers.
- **The proofs field is empty of data.** `:seon.task/proofs` is a schema attribute (`resources/seon/schemas/seon.task.edn`
  lines 28, 42, 148, 158, 165) and `src/seon/task.clj` (`obligations`, the `tagged` queries near lines 581-606) derives
  proofs from tags. No task entry in `resources/seon/tasks/*.edn` carries a `:seon.task/proofs` key; the only
  occurrences there are inside `:plan` prose (`seon.task.edn`, `seon.test.edn`) (VERIFIED). So every task's proof today
  is a tag.
- **Tasks at HEAD:** 881 entries (`#:seon.task{:name "..."}` in 101 files) (VERIFIED). 204 entries have at least one
  deftest code tag, so 204 tasks have "only proof is a tag" (VERIFIED). 677 entries have no deftest tag; of those, 32 entries
  carry only subject tags on `defn`/`ns` in `src` (VERIFIED: 652 entries carry no tag at all).
- **Orphans:** 1 code deftest tag names no entry (`seon.fn/a-file-proof-two-compiled-callers-away-never-sees-the-branch-redefinition`,
  also a subject tag). 26 valid-shaped names occur only inside test strings (fixtures such as `tq.base/one`,
  `rc.tags/next-step`) and none is an entry (VERIFIED). The sweep has nowhere to write the orphan proof.
- **Schema members:** `:seon.task/tasks` is a member of `seon.fn.edn:165`, `seon.ns.edn:33`, `seon.task.edn:156`,
  `seon.test.edn:128` (VERIFIED). Ingestion attribute asserted in `test/seon/task_ingestion_test.clj:103` (VERIFIED).

## 1. Per row

### S2a: one task input; proof recorded under its task

| Name | Where (HEAD) | Callers (HEAD, file: count) | Status |
|---|---|---|---|
| `seon.test.runner/result-at-reach` | `src/seon/test/runner.clj` defn; doc "Each test of `reaches` (test to reach digest) with the result of its newest admitted execution recorded on…" | `src/seon/test/runner.clj` 2, `src/seon/test.clj` 1, `test/seon/test/runner_test.clj` 2 | VERIFIED |
| `seon.task/effective` | `src/seon/task.clj` defn (1170); doc "The effective task of `handle` in `database`…" | `test/seon/ref_test.clj` 5, `src/seon/test/runner.clj` 3, `test/seon/step_test.clj` 2, `src/seon/context.clj` 2, `test/seon/design_prototype_test.clj` 1, `src/seon/task.clj` 1, `src/my/task.clj` 1, `script/seon/rename_task_intent.clj` 1 | VERIFIED (symbol-qualified count) |
| `seon.turn/task-obligations`, `task-proofs`, `step-tests` (all `defn-`) | `src/seon/turn.clj` 5212/5221/5229; docs say "read on `database`, the agent's branch" | `src/seon/turn.clj` 5 / 4 / 5 | VERIFIED |
| `seon.task/obligations` | `src/seon/task.clj` defn | `src/seon/task.clj` 13, `test/seon/task_test.clj` 22, `src/seon/turn.clj` 9, `src/seon/cluster/source.clj` 7, `src/seon/context.clj` 6, `test/seon/step_test.clj` 4, `test/seon/ref_test.clj` 4, `src/seon/test/runner.clj` 4 | VERIFIED |
| `a-tasks-proof-is-recorded-and-read-under-its-task` | `test/seon/task_test.clj` (intended) | none | ABSENT, no run |

### S2b-1: whole-branch selection

| Name | Where | Callers | Status |
|---|---|---|---|
| `seon.test/changes` | `src/seon/test.clj` defn, doc "What a check of `execution` must reach…" | `src/my/test.clj` 3, `src/seon/sci/eval.clj` 1, `test/seon/sci/branch_execution_test.clj` 1 | VERIFIED |
| `seon.test.selection` / `selection-admission` | `test/seon/test/one_request_test.clj` (existing selection tests) | — | VERIFIED exists; the S2b-1 row names no deftest |
| The S2b-1 red proof | row gives prose only ("a plan-only update runs the task's proof…"; "a deftest defined two requests earlier…") | — | ABSENT; no `ns/name` (TASK-CHAIN §7 form unmet) |

### S2b-2: `done-refusals` (§2.4)

| Name | Where | Callers | Status |
|---|---|---|---|
| `seon.cluster.source/done-refusals` | would be new in `src/seon/cluster/source.clj` | — | ABSENT (no hit for `done-refusals` in src, test or resources) |
| `seon.cluster.source/contract-gaps` | `src/seon/cluster/source.clj` defn (1113) | `source.clj` 8, `src/seon/turn/batch.clj` 5, `src/seon/turn.clj` 2, `test/seon/cluster/source_test.clj` 1, `test/seon/context_test.clj` 1 | VERIFIED |
| `seon.cluster.source/results-verdict` | `src/seon/cluster/source.clj` defn (1146) | final-merge judge (§2.6) | VERIFIED |
| `seon.turn/task-end` | `src/seon/turn.clj` defn (2145) | — | VERIFIED |
| `seon.context/step-status` | `src/seon/context.clj` defn (160) | `src/seon/context.clj` 4, `test/seon/test_failure_facts_test.clj` 2, `src/seon/cluster/source.clj` 2, `test/seon/context_test.clj` 1, `src/seon/turn.clj` 1 | VERIFIED |
| `:seon.source/judged`, `:seon.source/refusals` | schema keys (`source.clj`, `seon.context.edn`) | — | VERIFIED exists |
| `seon.cluster.source-test/done-refusals-judges-every-task-alike` | `test/seon/cluster/source_test.clj` exists; the deftest does not | — | ABSENT |

### S2c-1: tag sweep writes `:seon.task/proofs`

- Rewriter: `rewrite-clj/rewrite-clj` is in `deps.edn` (line 119) and `reference-code/` (VERIFIED). No sweep script exists
  (`script/seon/` has `rename_task_intent.clj` and others; none sweeps tags) (VERIFIED).
- Sweep input at HEAD, from the scanner: 271 deftest code sites (284 valid-shaped symbols, 205 distinct; 204 are entries, 1 orphan),
  26 string-only names, 36 `defn`/`ns` subject sites in `src` (33 distinct, 32 entries, 1 orphan).
- Targets: the `:seon.task/proofs` attribute (writer `seon.task/tasks-tx`, `src/seon/task.clj` line ~317 region) and
  `resources/seon/tasks/*.edn` (101 files).
- Red proof: none named. The row's own acceptance ("before/after task→proof sets equal for retained tests") needs a
  deftest or a script assertion carrying `ns/name`.

### S2c-2: readers switch; tags, tag readers and wrappers deleted

| Symbol to delete / convert | Where | Callers (HEAD, file: count) |
|---|---|---|
| `seon.test.runner/single-tags` (`defn-`, 1323) | `src/seon/test/runner.clj` | runner.clj 3 |
| `seon.turn` wrappers `task-obligations`, `task-proofs`, `step-tests` | `src/seon/turn.clj` | turn.clj 5 / 4 / 5 |
| `seon.context/task-done?` (defn, line 373) | `src/seon/context.clj` | turn.clj 6, `test/seon/context_test.clj` 4, context.clj 1 |
| `seon.context/step-status` (see S2b-2) | | context.clj 4, others as above |
| `seon.cluster.agent/basis-valid?` (`defn-`, 1706) | `src/seon/cluster/agent.clj` | agent.clj 2 |
| `seon.task/obligations`, `assigned-obligations` (566, 617) | `src/seon/task.clj` | `assigned-obligations`: 7 occurrences in src and test (per-file split UNVERIFIED) |
| `seon.task/task-edges` (629), `open-tasks` (646), `task-findings` (654), `candidate-dangling` (666) | `src/seon/task.clj` | task-edges: `test/seon/task_ingestion_test.clj` 2, `test/seon/task_test.clj` 2, task.clj 1; open-tasks: ingestion 2, task.clj 2, task_test 1, `test/seon/cluster/source_test.clj` 1; task-findings: ingestion 2, task_test 1, task.clj 1, source.clj 1; candidate-dangling: ingestion 4, task_test 1, task.clj 1, source.clj 1 |
| `seon.fn/task-links` (`defn-`, `src/seon/fn.clj` 670) | | fn.clj 5 (name-level), `test/seon/task_test.clj` 1 |
| `seon.cluster.agent/render-identity-html` (defn, 316) | `src/seon/cluster/agent.clj` | agent.clj 2; `test/seon/render/web_context_test.clj`, `test/seon/render/entity_pairs_test.clj`, `test/seon/cluster/agent_identity_test.clj` (2 each), `test/seon/task_test.clj`, `test/seon/render_coverage_test.clj`, `test/seon/render/web_debug_test.clj`, `test/seon/html_views_test.clj` (1 each) |
| `:seon.task/annotation` and links | `src/seon/fn.clj` 3, `src/seon/cluster/source.clj` 1, `src/seon/call_preparation.clj` 1 | `test/seon/task_ingestion_test.clj` 11 |
| `:seon.task/tasks` schema members | `resources/seon/schemas/seon.fn.edn:165`, `seon.ns.edn:33`, `seon.task.edn:156`, `seon.test.edn:128` | ingestion test 103 |
| `seon.contracts/task` tag output | `src/seon/contracts.clj` defn (46) | `test/seon/contract_coverage_test.clj` 1 |
| `seon.task/goal-owner` (defn, 1193) | `src/seon/task.clj` | task.clj 4, `src/my/task.clj` 2 at HEAD (0 in working tree: the file is modified) |
| `seon.task/assign!` proof check | `src/seon/task.clj` 862-863 | reads `:seon.task/proofs` from edges (already field-based) |

Tag sites by enclosing form, HEAD (VERIFIED): code on `deftest` 271 sites; code on `defn`/`ns` in `src` 36; code on
`defn` in test 1; string literals on `deftest` 42 (test), on `defn` 36, on `ns` 3. Tag-bearing files: 121 (HEAD).

## 2. Overlap and collisions

Shared files between rows (serial order required):
- `src/seon/turn.clj`: S2a (wrappers and `task-proofs` readers), S2c-2 (deletion). S2a first.
- `src/seon/test/runner.clj`: S2a (`result-at-reach`), S2c-2 (`single-tags`). Also held by lane **b1-durable** and modified in the working tree.
- `src/seon/context.clj`: S2a (`effective`, `task-done?`), S2b-2 (`step-status`), S2c-2 (deletions).
- `src/seon/task.clj`: S2a (`effective`, `obligations`), S2c-2 (tag readers, `goal-owner`, `obligations`). Note S3a/S3e also name it.
- `src/seon/cluster/source.clj`: S2b-2 (new `done-refusals`), S2c-2 (`task-findings`, `candidate-dangling`, `open-tasks` readers). Held by lane **writeback-fix** (4 paths including this file).
- `src/seon/fn.clj`: S2c-2 (`task-links`, annotation). Held by lane **boot-dup-analysis** and modified in the working tree.
- `src/seon/test.clj`: S2b-1 (`changes`). Held by lane **b1-durable** and modified in the working tree.
- `src/seon/sci/eval.clj`: S2b-1 caller of `seon.test/changes`. Held by lane **overlay-p3**.
- `test/seon/ref_test.clj`: S2c-2 (`obligations` 4 callers, `seon.task/effective` 5). Held by lane **s3d-2** (`test/seon/ref_test.clj`, `src/seon/ref.clj`, `resources/seon/schemas/seon.ref.edn`).
- `test/seon/task_test.clj`: S2a's proof lives here; S2c-2 touches 22 `obligations` references. No lane holds it (VERIFIED: not in `bin/lane` claims).

Against **S1d-2** (lane `s1d-2`, 15 paths claimed; the lane status line and `tmp/orchestrator/lanes/s1d-2.paths`):
- Claimed and in S2 scope: `src/my/task.clj` (the `goal-owner` caller and `update!`), `resources/seon/help.md`
  (S2c-2's help text), `test/seon/cluster/db_flow_test.clj` (22 `:seon/tasks` tags and one `obligations` reference, a
  reader S2c-2 converts), `resources/seon/tasks/seon.task.edn`, `seon.context.edn`, `seon.turn.world.edn`, `seon.step.edn`,
  `my.task.edn`, `seon.ref.edn` (S2c-1 writes these task files), `test/seon/context_test.clj`, `test/seon/help_test.clj`,
  `test/seon/cluster/merge_test.clj`, `test/my/task_test.clj`, `resources/seon/schemas/my.task.edn`, `AGENTS.md`.
- Consequence: S2c-1 (sweep writes task files) and S2c-2 both wait for `s1d-2` to commit or release those paths, or
  take a patch from it.

Against **B1** (`b1-durable`, paths: `src/seon/test.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.member.edn`,
`docs/seon/specs/merge.md`, `test/seon/test/branch_reuse_test.clj`, `resources/seon/tasks/seon.test.edn`, `src/seon/cluster/registry.clj`):
- B1 holds both files S2a, S2b-1 and S2c-2 edit. `resources/seon/tasks/seon.test.edn` is in B1's paths and the S2c-1
  sweep writes task files including it. Serial after B1.

Other held files in scope: `test/seon/cluster/db_flow_test.clj` (s1d-2), `src/seon/fn.clj` (boot-dup-analysis, modified),
`test/seon/ref_test.clj` (s3d-2). Lanes teststep-verdict, projections, check-class-race, reconcile-writer hold no S2 file (VERIFIED from `tmp/orchestrator/lanes`).

Current dirty files in S2 scope (`git status --porcelain`, VERIFIED): `src/seon/fn.clj`, `src/seon/test.clj`,
`src/seon/test/runner.clj`, `src/my/task.clj`, `resources/seon/help.md`, `test/seon/cluster/db_flow_test.clj`,
`test/seon/context_test.clj`. Uncommitted work from other lanes must be committed or released before any S2 lane edits them.

## 3. Suggested serial order (facts only)

Dependencies visible in the table: S2a reads `result-at-reach` and `effective` and needs its deftest first; S2b-2 needs
`:seon.source/judged` (exists) and a new function in `source.clj`; S2c-1 needs a sweep script and the target task files
free (s1d-2); S2c-2 needs S2a and S2b-2 (readers switched) and S2c-1 (proofs field populated). The files that serialize
rows are listed in §2.

## 4. Open items (UNVERIFIED or missing)

- The §6 totals (344 / 247 / 39 / 33) are not reproduced; the method behind them is unknown.
- S2b-1 and S2c-1/S2c-2 name no deftest; the TASK-CHAIN §7 proof form is unmet for them.
- Stored run status: no run exists for the named red proofs because the deftests do not exist (VERIFIED by absence).
- `seon.task/task-done?` location was taken from the working tree's `src/seon/context.clj` (HEAD line 373, VERIFIED by grep).
