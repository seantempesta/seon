---
title: "Pre-briefs for hybrid-test-results R4 to R12 (the key, the base run, one status, the origin of reds, the merge gate, evidence and check, nondeterminism)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Each phase lists what its implementer must know before the first form. Written by lane prebrief-r.
basis: HEAD 1b09d8043 (R1 at 50ca41030 and R2 at 474174b52 landed; M5 slice 1 at 1b09d8043). Phase plan: docs/research/agent-platform/2026-10-12/hybrid-test-results.md §6 (rows R4 to R12). Model: prebrief-m5-m7.md. Lane claims read with `bin/lane status LANE` on each lane. Stored runs on default were not queried in this pass (see "Not verified"); red states are taken from today's notes (red-triage.md, narrow-check-test-results.md, central-round.md, chain-experiment-3.md, g1-proof-bisect.md).
---

# Pre-briefs: R4 to R12

Every symbol below was located with `rg` and read at its defn. "Callers" means every reference in `src`, `test`, `script`, `bin` and `resources` (tasks, schemas). Words are Clojure, Datahike and git's.

## Cross-phase facts that change all of them

1. **`src/seon/test/runner.clj` is claimed by lane `reach-incr`** (with `test/seon/test/reach_test.clj`, `test/seon/test/runner_test.clj`, `test/seon/test_reaching_test.clj`). R4, R5, R6, R8, R9 and R12 all edit it, and R11 is that lane's own phase. Nothing in R4 to R12 can be claimed until `reach-incr` releases or hands over its hunks.
2. **`src/seon/test.clj` and `src/seon/turn.clj` carry uncommitted edits by lane `root-drift`** (`behavior-changed`, `request-changes` in `seon.test`; the `test-step` and `record-broken!` area in `seon.turn`). R4, R5 touch `seon.test`; R6, R7 touch `seon.turn`. Lane `root-drift` also holds `test/seon/turn_test.clj`, `test/seon/request_cost_test.clj`, `resources/seon/schemas/seon.turn.loop.edn`, `resources/seon/tasks/seon.turn.edn` and `resources/seon/tasks/seon.test.edn`.
3. **Lane `m5` holds `test/seon/test_test.clj` (R5's proof file), `resources/seon/schemas/seon.agent.edn` (R6 deletes two keys there), `resources/seon/tasks/seon.cluster.squash.edn` (R8's task) and `src/seon/task.clj`.**
4. **M7 has not landed** (`hybrid-done-and-merge.md` §6 row M7 is not in any commit). R8 is blocked on it, and R6 and R7 must land before M7 so its gate inherits `:seon.context/unknown` (hybrid §6 order note).
5. **The `seon.test.run` schema has no config digest yet** (`resources/seon/schemas/seon.test.run.edn` has `:seon.test.run/input-digest` and `:seon.test.run/program-digest`, no `config-digest`). R4 adds it; its one effect is that every stored result lacks it and reruns once (owner ruling 3, hybrid §7).

---

## R4 — configuration is in the key

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.test.runner/provenance` | adds `:seon.test.run/config-digest` (`seon.id/id` of `seon.config/effective` for the run's cluster on the tested database, computed once per run) | `seon.test.platform` (`:seon.test.run/provenance` at its use), `seon.test/admit-run` (selects `:seon.test.run/...` keys from the run; the new key must be added to its `select-keys` list or it is dropped), `test/seon/test/runner_test` (lines build a provenance), `seon.test.runner` `commit-results!` path (`:seon.test.run/provenance`) |
| `seon.test/select` | compares the config digest beside `:seon.test.run/input-digest` when it decides whether a result runs | itself; `test/seon/test/selection_test` (its `named-selection-reuses-green-members-by-reachable-content`); task `seon.test/select` in `seon.test.edn` as must-read |
| `resources/seon/schemas/seon.test.run.edn` | new attribute `:seon.test.run/config-digest` (`:seon.source/digest`-like, identity false) | `admit-run`'s selector list, `provenance`'s schema |
| `seon.config/effective` | read only; no change | `seon.config` consumers (not changed) |

Not in R4's list but affected: `seon.test/admit-run` builds its `entity` by `select-keys` on the run (`:seon.test.run/...` names). A key missing from that list is silently dropped at admission, so the proof must read the stored run, not the return of `provenance`.

### 2. Tests reaching them, and red state

- `test/seon/test/selection_test` (ns `seon.test.selection-test`): the proof is to be added there. It is **absent**: `rg` finds no `a-changed-dial-reruns-a-test-that-reads-it` in `test/`. The task `seon.test/a-changed-dial-reruns-a-test-that-reads-it` is filed in `resources/seon/tasks/seon.test.edn` with `:must-read` `seon.test.runner/provenance seon.test/select seon.config/effective seon.id/id`.
- `selection_test` already uses `with-redefs` over `seon.test.runner/reach-digests` in `named-selection-reuses-green-members-by-reachable-content`. That breaks AGENTS.md (never redefine a Var) and is a separate filed task `seon.test/the-selection-test-observes-reach-digests-without-redefining-it`. R4 edits the same file; land that task's fix in the same commit or name it as a known exception to the file's green state.
- Red state: no proof exists, so no current red. The proof must be written red first: `support/apply-config!` changing one dial, then `select` must list the test. Today `select` compares input digest, evaluator and reach only (hybrid §2.2).

### 3. Filed task and proof

- Task `seon.test/a-changed-dial-reruns-a-test-that-reads-it`: filed, `:kind :function`.
- Proof deftest: **not written**. The task names no proof yet; it cannot be assigned until one exists (AGENTS: assigned only with a proof).

### 4. Prior attempts, rulings, gotchas

- Owner ruling 3 (hybrid §7): R4 accepted; every stored result reruns once. Say so in the landing note.
- Hybrid §2.2: a configuration fact is read by `seon.config/effective` in the run; the key must use the run's cluster and tested database, not the live one.
- A dial read *before* `config/apply!` in the same request keeps the old digest. Compute the digest once per run in `provenance`, not per member.

### 5. Collisions and riskiest step

- Files: `src/seon/test/runner.clj` (CLAIMED `reach-incr`), `src/seon/test.clj` (uncommitted `root-drift`), `resources/seon/schemas/seon.test.run.edn` (free), `test/seon/test/selection_test.clj` (free; carries the `with-redefs` task).
- Riskiest step: `admit-run`'s `select-keys` list. If the new key is not added, admission writes runs without a config digest and `select` reruns every test on every request (the proof would not catch it unless it reads the stored run). Keep HEAD working: the config digest is nil-tolerant only for absent *stored* runs; the schema must accept the key as optional in the first commit or the existing runs fail validation at read.

---

## R5 — a base run is kept on the agent's branch

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.test/admit-run` | admits a run whose tested commit is the destination branch's base (`:seon.test.run/tested-branch` the child) | `seon.test.platform` (admission), `seon.cluster.source` (write-back path), `test/seon/test/one_request_test`, `test/seon/test/interrupted_request_test`, `test/seon/test/selection_test`, `test/seon/test/admission_digest_test`, `test/seon/test_test`, `test/seon/turn_test` (`admit-run` mention), `resources/seon/tasks/seon.cluster.squash.edn`, `resources/seon/tasks/seon.context.edn`, `resources/seon/tasks/seon.test.edn` |
| `seon.test/fork-point-red` | records its one run on the execution's connection instead of releasing it with the child | called by `seon.turn/record-broken!` through `@test-fork-point-red` (`src/seon/turn.clj`), and by `seon.test/change-reds` (same file); `test/seon/test_test.clj` (through `change-reds`); task `seon.turn.edn`, `seon.context.edn` |
| `seon.test.runner/record-latest-tx` | already takes `tested-database`; R5 relies on it, no change planned | `commit-results!` path, `seon.test/admit-run` |
| `seon.turn/fork-commit` | read; no change | `seon.context/base-results` (through `turn/fork-commit`), `seon.turn/record-broken!`, `seon.test/fork-point-red` |

Words to retire inside this phase: none in code; the child's lost run is the behavior it removes ("the child's lost run: `fork-point-red` recording on the released child", hybrid §5).

### 2. Tests reaching them, and red state

- `test/seon/test_test.clj` (ns `seon.test-test`). Today's red: narrow check claim 13 measured 5 runs before and 5 after a second `fork-point-red` on one red (the child's run is released). That is the stored-era red, not a deftest result.
- Proof deftest `seon.test-test/a-base-run-is-read-at-the-next-step-without-running`: **absent** (`rg` finds no such name in `test/`). Filed as task `seon.test/a-base-run-is-kept-on-the-agents-branch` (`resources/seon/tasks/seon.test.edn`), must-read `seon.test/admit-run seon.test/fork-point-red seon.test.runner/record-latest-tx seon.turn/fork-commit`.
- `test/seon/test_test.clj` is claimed by lane `m5`. The file already holds `change-reds`'s test (long-marked), which R5 must keep green.

### 3. Filed task and proof

- Task filed. Proof: not written. Red-on-HEAD expectation (from the hybrid): second call runs a body; the child's run count stays at 5.

### 4. Prior attempts, rulings, gotchas

- Owner ruling Q4 (hybrid §7): the base run is kept and reused by its key; the stored judgment is deleted in R6.
- `seon.turn/record-broken!` (private) is the only caller of `fork-point-red` on the agent path. Its body runs `fork-point-red` under `limit-ms` and writes `:seon.agent/broken`/`new-red`; R5 changes where the run lives, not that write, so R5 is safe to land before R6.
- The merge gate's `change-reds` (called from `seon.cluster.source` at its test evidence step) also calls `fork-point-red` on main; R5 must keep main's read unrecorded on main (hybrid R8 is what makes main record it, after M7).

### 5. Collisions and riskiest step

- Files: `src/seon/test.clj` (`root-drift`, uncommitted), `src/seon/test/runner.clj` (`reach-incr`), `test/seon/test_test.clj` (`m5`). All three are held, so R5 cannot start without releases.
- Riskiest step: moving the run from a released child to the agent's branch. If the admission writes it under the child's `tested-branch` but the next step reads the agent's branch, the first red is judged `unknown` and every step with a red stops being done (R6 turns a missing base into unknown). R5 must land and be read on the next step before R6 lands. HEAD stays working: until R6 lands, `record-broken!` still writes the old judgment and nothing reads the kept run.

---

## R6 — one status map, unknown its own set, the judgment derived

### 1. Symbols changed or deleted, with all callers

| Symbol / key | Change | Callers and mentions |
|---|---|---|
| `seon.test.runner/result-at-reach` | generalized into `seon.test.runner/results-at` [databases keys] (each test's newest completed result at its key in any of `databases`) | `seon.context/base-results` (`src/seon/context.clj`, private), `test/seon/test/runner_test`, `resources/seon/schemas/seon.context.edn` (mentions in its `:seon.test/...` description), `resources/seon/tasks/seon.context.edn` (must-read names it), `resources/seon/tasks/seon.cluster.squash.edn` (mention), `docs/seon/issues/test-results-persistence-can-time-out-during-development-adoption.md` (prose only) |
| `seon.context/base-results` (private) | converted to `results-at` at the base keys | `seon.context/step-observation` only |
| `seon.context/step-status` | derives `:seon.context/unknown`; takes proof statuses, not booleans | `seon.turn` (`context-step-status` delay and `record-broken!`), `seon.context/step-verdict`, `test/seon/context_test` (`each-step-kind-is-done-by-its-one-rule` at ~310 and others), `test/seon/test_failure_facts_test`, `resources/seon/tasks/seon.cluster.squash.edn` and `seon.turn.edn`, `seon.context.edn` (must-read) |
| `seon.context/step-observation` | `:seon.context/fork-point` becomes `test → :seon.context/proof-status` | `seon.turn` (`context-step-observation` delay; `record-broken!`'s call with `{:seon.context/broken #{} …}`), `seon.context/step-verdict`, `seon.render.walk` (`:seon.render.walk/step-observation` in `resources/seon/schemas/seon.render.walk.edn`), `resources/seon/schemas/seon.context.edn` (`:fork-point` boolean schema, and `:step-observation` schema) |
| `seon.context/fork-point` schema key | `[:map-of :seon.test/sym :boolean]` becomes a status map | `resources/seon/schemas/seon.context.edn`, `test/seon/context_test`, `test/seon/test_failure_facts_test` |
| `seon.turn/record-broken!` (private) | runs only the bases nobody holds (R5), writes nothing | `seon.turn/test-step` only |
| `:seon.agent/broken`, `:seon.agent/new-red` | **deleted** (schema keys in `resources/seon/schemas/seon.agent.edn`; their writes in `record-broken!`; their read in `step-observation`'s `row` pull) | `src/seon/context.clj` (`step-observation`), `src/seon/turn.clj` (`record-broken!`), `test/seon/turn_test` (asserts `:seon.agent/broken`), task intent in `resources/seon/tasks/seon.turn.edn`, `seon.context.edn` (the `:seon.context/new-red` task's text), `resources/seon/schemas/seon.turn.loop.edn` (the `:seon.turn.loop/broken` description names `:seon.agent/broken`) |

Also named by the hybrid's deletion table: `seon.agent/new-red` (schema `seon.agent.edn`), `seon.turn-test` assertion. The hybrid says `:seon.context/unknown` is new; check `resources/seon/schemas/seon.context.edn` for it before adding (none found in this pass).

### 2. Tests reaching them, and red state

- `test/seon/context_test` (ns `seon.context-test`): `each-step-kind-is-done-by-its-one-rule` **exists** (the table to convert; red today only where its new `:unknown` rows are added). `broken-tests-show-with-their-failures` exists (declared long, 30 s).
- `test/seon/test/runner_test` (ns `seon.test.runner-test`): `results-at-reads-each-tests-newest-result-at-its-key` **absent**.
- Proof for `seon.context/a-red-with-no-base-result-is-unknown` (task in `resources/seon/tasks/seon.context.edn`): the new rows of `each-step-kind-is-done-by-its-one-rule` are not yet written.
- `seon.turn-test` is claimed by root-drift (`test/seon/turn_test.clj`).

### 3. Filed tasks and proofs

- `seon.context/a-red-with-no-base-result-is-unknown` (filed, `:needs` `seon.test/a-base-run-is-kept-on-the-agents-branch` and `seon.context/a-proof-green-before-the-work-is-not-done`).
- Proof rows: the table in `context_test` (partly present), and the runner's `results-at` test (absent).

### 4. Prior attempts, rulings, gotchas

- H2 (chain-experiment-3) is a step proof read on the head and keyed by `:seon.test/unchanged`. R6 does not fix it; see §"H2" below.
- The `:seon.context/fork-point` boolean is what `step-status` tests with `(false? (get fork-point %))` today. Converting it is the owner-named change (hybrid §3 "the boolean fork point" row).
- Hybrid §5 lists `seon.context/step-observation`, `seon.turn/record-broken!` and `seon.turn-test` as the three readers of the two deleted keys. A reader added after this brief (`rg` again before the phase) is a conversion the phase must include.

### 5. Collisions and riskiest step

- Files: `src/seon/context.clj` (**free**; M7 and M14 will touch it later), `src/seon/turn.clj` (`root-drift`), `src/seon/test/runner.clj` (`reach-incr`), `resources/seon/schemas/seon.agent.edn` (**`m5`**), `resources/seon/schemas/seon.turn.loop.edn` (`root-drift`), `resources/seon/schemas/seon.context.edn` (free), `resources/seon/tasks/seon.turn.edn` (`root-drift`), `resources/seon/tasks/seon.context.edn` (free), `test/seon/context_test.clj` (free), `test/seon/test_failure_facts_test.clj` (free), `test/seon/test/runner_test.clj` (`reach-incr`), `test/seon/turn_test.clj` (`root-drift`).
- **Riskiest step.** Without R5 the base result is absent for every red, so `step-status` derives `:seon.context/unknown` for every red and no step with a red can be `:done`. Land R6 only after R5 is landed *and* read on the next step (the step's base run exists on the agent's branch). Second risk: the schema change of `:seon.context/fork-point` breaks the render walk (`seon.render.walk/step-observation`) if the keys are not converted in the same commit. HEAD stays working: `results-at` is added beside `result-at-reach` first (no caller removed in the same commit), and `result-at-reach` is deleted in the commit that converts its last caller.

---

## R7 — every agent sees where its reds came from

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.context/step-observation` | accepts an absent task (obligations empty, head = `seon.context/head-entries` over the reached tests) | `seon.turn/record-broken!`, `seon.context/step-verdict`, the render walk |
| `seon.context/head-entries` (private) | read; used with reached tests | `seon.context/step-observation` |
| `seon.turn/test-step` | sets `:seon.turn.loop/broken` for every agent (not only task holders) | called from `seon.turn` pass; `test/seon/context_test`, `test/seon/turn_test`, `test/seon/cluster/turn_test`, `test/seon/request_cost_test` (the last two are claimed by `root-drift`) |
| `seon.context/step-section` | counts broken tests (`test-counts`) | `src/my/task.clj` (its consumer), `seon.context/current-state` path |
| `my.test/reds` | new function reading base-green reds | `src/my/test.clj` (free; the namespace is the MCP surface) |

### 2. Tests reaching them, and red state

- `test/seon/context_test`: `broken-tests-show-with-their-failures` **exists** (long). R7 adds a no-task agent case inside it. Red today: the block holds only `:seon.context/turns` (hybrid §8 P3). Stored-era evidence: narrow check claim 9.
- `my.test/reds` has no test.

### 3. Filed task and proof

- Task `seon.context/every-agent-sees-where-its-reds-came-from` (filed, `:needs` `seon.context/a-red-with-no-base-result-is-unknown`). The proof is the extended `broken-tests-show-with-their-failures`; it is not yet changed.

### 4. Prior attempts, rulings, gotchas

- Hybrid §6 says `test-step`, not `summary`, sets `:seon.turn.loop/broken` (narrow check F6). Keep that.
- A no-task agent has no proof obligations, so its head is the reached tests and `step-status` is not the judge; R7 must not call `step-status` with an empty `step`.

### 5. Collisions and riskiest step

- Files: `src/seon/context.clj` (free), `src/seon/turn.clj` (`root-drift`), `src/my/test.clj` (free), `resources/seon/schemas/seon.turn.loop.edn`, `seon.context.edn`, `my.test.edn` (`root-drift` on the first; others free), `test/seon/context_test.clj` (free).
- **Riskiest step.** Every agent now runs the step's base reads (`results-at` across the agent's branch and main head), so the block read cost rises for every agent, not only task holders. Measure against the hybrid's bound (< 20 ms warm). HEAD stays working: the no-task branch is added beside the task branch, not in place of it.

---

## R8 — (after M7) root reads branches; the merge gate's answers stay on main

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.cluster.source/accept-merge!` (the owner M7 names today) | records the gate's runs on main through `admit-run` with the merge branch as the tested branch | `seon.cluster.source` (its own `land!` path), `test/seon/cluster/merge_test` (`main-keeps-the-merge-results-after-the-branch-is-deleted` is its proof, absent), `test/seon/cluster/write_back_test`, `test/seon/cluster/source_test`, `resources/seon/tasks/seon.cluster.source.edn`, `seon.cluster.squash.edn`, `my.root.edn`, `seon.db.edn`, `seon.batch.edn`, `seon.repl.edn`, `seon.test.edn` |
| `seon.test/change-reds` | called by the gate (`seon.cluster.source`) with main's head; R8 changes what main answers from | `seon.cluster.source`, `test/seon/cluster/merge_test` (comments), `test/seon/test_test` |
| `seon.test.runner/results-at` | root's databases become main and every live branch head | defined by R6 |

### 2. Tests reaching them, and red state

- `test/seon/cluster/merge_test` exists; its proof `main-keeps-the-merge-results-after-the-branch-is-deleted` is **absent**. Red-on-HEAD expectation (hybrid §6): `:seon.data` runs stay on the deleted branch.
- Filed as `seon.cluster.squash/main-keeps-the-merge-results-after-the-branch-is-deleted` (`resources/seon/tasks/seon.cluster.squash.edn`, `:needs` `seon.cluster.squash/a-merge-whose-reached-definition-moved-writes-nothing` and `seon.test/a-base-run-is-kept-on-the-agents-branch`). The task's name refers to the squash owner, which M6 renames to `seon.merge`. Settle the owner before filing the proof.

### 3. Filed task and proof

- Task filed (under the old `seon.cluster.squash` ns); proof absent.

### 4. Prior attempts, rulings, gotchas

- Owner ruling 1 (hybrid §7): root records its merge gate's answers on main through R5's admission.
- The gate's run is root's own observation, not a merge of the branch's runs (`seon.test/admit-run` keeps the run's tested branch as the merge branch).

### 5. Collisions and riskiest step

- Files: `src/seon/test/runner.clj` (`reach-incr`), `src/seon/test.clj` (`root-drift`), `src/seon/cluster/source.clj` (free; M7 owner, must be claimed when M7 runs), `test/seon/cluster/merge_test.clj` (free).
- **Riskiest step.** M7 and M6 both rename or reshape the merge owner, so R8 is the phase most likely to be filed against a deleted name. Do not start R8 before M7 lands. HEAD stays working because R8 adds recording on main and deletes nothing.

---

## R9 — `--branch-evidence` reads the same answer; the accepted list goes

### 1. Symbols changed or deleted, with all callers

| Symbol / key | Change | Callers and mentions |
|---|---|---|
| `seon.cluster.branch-evidence/test-evidence` (private) | sorts reaching reds by `seon.test.runner/results-at` at the cluster head under `:interpreted`; passes the reached set once | `seon.cluster.branch-evidence/branch-evidence` only |
| `seon.cluster.branch-evidence/verdict` (private) | `:seon.source/broken` refuses; red-before proves; unknown names the form; **drops the `accepted` argument** | `branch-evidence` only |
| `seon.test.runner/red-at-current-reach?` | **deleted** (its last caller is `test-evidence`) | `src/seon/cluster/branch_evidence.clj`, `src/seon/test/runner.clj` (definition), `resources/seon/tasks/seon.turn.edn`, `seon.cluster.source.edn`, `seon.cluster.branch-evidence.edn`, `docs/seon/schedule/cards/ROOT-AGENT.md` |
| `--accept-red-before` in `bin/lane` | **deleted**: its parser, its `accepted` array (its parser and its list), the `--branch-evidence` call, the trailer `Branch-evidence-red-before-accepted:` and `bin/lane evidence … -- TEST` (usage text and the refusal text) | `bin/lane` only; docs: `docs/seon/schedule/cards/RF-2.md`, `docs/seon/schedule/agent-loop-ledger.md`, `docs/research/agent-platform/2026-10-08/lane-templates/repl-slice.md` (F2) |
| `:seon.source/accepted-red-before` | **deleted** (schema `resources/seon/schemas/seon.source.edn`) | `src/seon/cluster/branch_evidence.clj` (`verdict` and `branch-evidence`'s `paths`/`accepted` destructuring), `bin/lane` (the Clojure form that reads the list), `test/seon/cluster/branch_evidence_test.clj` |
| task `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head` | retired with the list (R10 names the other side) | `resources/seon/tasks/seon.operator.edn` |

### 2. Tests reaching them, and red state

- `test/seon/cluster/branch_evidence_test.clj` (ns `seon.cluster.branch-evidence-test`): `a-branch-proves-its-files-only-with-current-green-evidence` **exists** (its testing block). Its "red on H too" testing asserts `proven?` true with an empty accepted set (narrow check T3); that is the red today, and it is the proof R9 must make green.
- The proof named in the task (`…-with-current-green-evidence`) exists under that exact name; the R9 intent names it as the proof.
- Red on HEAD (narrow check claim 22): `verdict` requires `(every? accepted red-before)`.

### 3. Filed task and proof

- Task `seon.cluster.branch-evidence/a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key` (filed, `:needs` `seon.context/a-red-with-no-base-result-is-unknown`). Its proof is the existing `a-branch-proves-its-files-only-with-current-green-evidence` (tagged by the task) and a new `testing` row for "green at H, red on the branch refuses".

### 4. Prior attempts, rulings, gotchas

- Owner rule (hybrid §7 and the two-phase note): no accepted list; a red that the base holds is red before, judged by the same read as the step (R6).
- `bin/lane evidence … -- TEST` exists only for the accepted list; deleting it removes an operator path, so the cards (`RF-2.md`, `ROOT-AGENT.md`) and the ledger must change in the same commit (AGENTS: a moved or deleted name converts every use in one commit).

### 5. Collisions and riskiest step

- Files: `src/seon/cluster/branch_evidence.clj` (free), `bin/lane` (free), `resources/seon/schemas/seon.source.edn` (free), `test/seon/cluster/branch_evidence_test.clj` (free), `src/seon/test/runner.clj` (`reach-incr`), `resources/seon/tasks/seon.cluster.branch-evidence.edn` (free), the cards and ledger (free).
- **Riskiest step.** `bin/lane` is a host-bound slice (`bin/`), so it commits through `bin/lane commit … --test seon.cluster.branch-evidence-test` and its `bin/seon check` run. A deleted parser that a lane still passes `--accept-red-before` to will refuse the lane's commit; the lane template (F2) must change in the same commit. HEAD stays working: delete the accepted list only in the commit that also converts `verdict`'s every caller (only `branch-evidence`).

---

## R10 — `bin/seon check` judges its reds against main

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.operator/check-verdict` | **new**, pure: the throwaway's result file and main's `seon.test.runner/results-at` under `:compiled` | new; called from `seon.operator/check!` |
| `seon.operator/check!` (`script/seon/operator.clj`) | sets `SEON_TEST_RESULT_FILE` and asks the live root over the prepl | dispatched by the operator's `:check` case; `bin/seon` (its help) |
| `bin/test-check` | writes members under `SEON_TEST_RESULT_FILE` (a mention in `resources/seon/tasks/seon.operator.edn`) | `check!` |

### 2. Tests reaching them, and red state

- `test/seon/operator_test.clj` (ns `seon.operator-test`) **exists** (its `deftest`s cover the boot result line and the start summary); the proof `a-check-red-main-holds-red-does-not-refuse` is **absent** (`rg` finds none). Narrow check T4: no `check-verdict` exists (red by absence).

### 3. Filed task and proof

- Task `seon.operator/a-check-red-main-holds-red-does-not-refuse` (filed, `:needs` `seon.context/a-red-with-no-base-result-is-unknown`). Proof: table over `check-verdict` (main red → not refused, red-before; main green → refused; absent → refused, unknown).

### 4. Prior attempts, rulings, gotchas

- `check!` already refuses a red with no reference to main. The chain-2 `bin/seon check` runs (G1 bisect, `g1-proof-bisect.md`) used `bin/seon check --at <commit> --test …` and reported 3/3 green per round, so the throwaway path is usable for the proof.
- Haiku rule: `bin/seon check` runs a throwaway JVM, not root's SCI; the proof's check must be `check-verdict` as data (pure), not a live check.

### 5. Collisions and riskiest step

- Files: `script/seon/operator.clj` (free), `test/seon/operator_test.clj` (free), `resources/seon/tasks/seon.operator.edn` (free).
- **Riskiest step.** `check!` asks the live root over the prepl for main's `results-at`. A root that cannot answer (boot in progress) must refuse with a diagnostic, not fall back to "no reds known" (which would accept a red). HEAD stays working: `check-verdict` is added beside the current refusal first; the refusal is removed in the commit that makes `check!` call the verdict.

---

## R12 — two outcomes at one key are a problem of the test

### 1. Symbols changed, with all callers

| Symbol | Change | Callers and mentions |
|---|---|---|
| `seon.problems/families` | adds `:seon.problems/nondeterministic-tests` (a family derived from members at one key with different outcomes, both run ids) | `seon.problems/problems` (the `families` loop); `test/seon/problems_test.clj` (`an-error-two-agents-hit-is-one-problem-with-two-agents` is in the central round, alone red); `resources/seon/schemas/seon.problems.edn`; `resources/seon/tasks/seon.problems.edn` |
| `seon.problems/failed-tests` (private, in `src/seon/problems.clj`) | read; the new family derives beside it, not inside it | `seon.problems/families` |
| `seon.test.runner/results-at` | answers the newest of two outcomes at one key (defined by R6) | R6's callers |

### 2. Tests reaching them, and red state

- `test/seon/problems_test.clj` **exists**. Proof `seon.problems-test/two-outcomes-at-one-key-are-a-nondeterministic-test` **absent**. Red by absence today (no family).
- Central round (`central-round.md`): `problems-test/an-error-two-agents-hit-is-one-problem-with-two-agents` is alone red. Unrelated to R12; note it so the R12 landing does not claim it.

### 3. Filed task and proof

- Task `seon.problems/two-outcomes-at-one-key-are-a-nondeterministic-test` (filed, `resources/seon/tasks/seon.problems.edn`). Proof: two fixture runs of one test at one key, one green one red (second with `:seon.test/rerun?`, reading a value the fixture changes), → one entry with both run ids.

### 4. Prior attempts, rulings, gotchas

- Owner ruling Q3 (hybrid §7): a flake is its own task, derived by R12; the newest result answers meanwhile.
- Ruling Q2 (hybrid §7): cluster data a test reads and did not write (tasks, agents, messages) is not keyed; it surfaces through R12 only if a rerun happens at that key. **H2 is not such a case**, see the next section.

### 5. Collisions and riskiest step

- Files: `src/seon/problems.clj` (free), `src/seon/test/runner.clj` (`reach-incr`), `resources/seon/schemas/seon.problems.edn` (free), `test/seon/problems_test.clj` (free), `resources/seon/tasks/seon.problems.edn` (free).
- **Riskiest step.** The family reads every run at each key on every `problems` call. That is a whole-run read; the hybrid bound is "< 10 ms added inside `failed-tests`' read". Measure it on default before landing. HEAD stays working: a new family with an empty result is inert.

---

## H2 and G1 (chain-experiment-3)

- **H2** (`chain-experiment-3.md` row H2): a Prove step's step proof reads its branch's tags with `(seon.task/task-edges (seon.db/db) …)`, and `seon.test/select` answers `:seon.test/unchanged true` with the earlier red. Two attempts capped on it.
- **Filed**: `seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged` in `resources/seon/tasks/seon.test.edn` (`:must-read` `seon.test/select seon.test.runner/reach-digests seon.task/task-edges seon.context/step-status`). Its red proof **exists**: `seon.test.redefined-callee-test/a-test-reading-task-tags-reruns-when-a-test-is-tagged` (`test/seon/test/redefined_callee_test.clj`), red in chain-3's branch run (4.9 s, executed 0, `passed?` false). The task names three producer options for root to choose; none is chosen.
- **Is it moot after G1?** Partly. G1 (`6a687ac17`, "a fresh attempt of a task naming no proof is work", chain-2 G1) is not about tags or keys; it changed when a Prove step gets its first turn. `chain-experiment-3.md` says: with G1 merged, the Prove step needs no step proof that reads the database. So the Prove-step case that surfaced H2 can be avoided by not writing such a proof. The class is not moot: any test that reads program facts it does not execute (a tag, a task edge) keeps a red at an unchanged key.
- **Does R1 to R12 cover it?** No. R2 keys by executed definitions, not by facts read. R12 answers only when two outcomes exist at one key, and a stale red is never rerun. Q2 excludes "tasks, agents, messages" from the key, but a tag is "a program fact on the test entity" (the H2 note). **Open ruling for root**: is a tag program (keyed, so the producer options apply) or cluster data (unkeyed, so the step proof must not read it)?
- **G1 bisect** (`g1-proof-bisect.md`): `a-prove-step-admitted-by-its-successor-opens-a-model-turn` is green 9 of 9 alone at 6a687ac17, 767551c7b and three HEAD commits. The stored red in `red-triage.md` row 4 (21 s over a 5 s bound) is not reproduced in isolation; cause open (load from concurrent JVMs is the leading candidate in that note). Not a reason to change R-phases.

## Summary of collisions and the order

| Lane | Holds (today) | Phases that need the path |
|---|---|---|
| `reach-incr` | `src/seon/test/runner.clj`, `test/seon/test/runner_test.clj`, `test/seon/test/reach_test.clj`, `test/seon/test_reaching_test.clj` | R4, R5, R6, R8, R9, R12, and R11 (its own) |
| `root-drift` | `src/seon/test.clj`, `src/seon/turn.clj`, `test/seon/turn_test.clj`, `test/seon/request_cost_test.clj`, `test/seon/cluster/turn_test.clj`, `resources/seon/schemas/seon.turn.loop.edn`, `resources/seon/tasks/seon.turn.edn`, `resources/seon/tasks/seon.test.edn` | R4, R5, R6, R7 |
| `m5` | `resources/seon/schemas/seon.agent.edn`, `test/seon/test_test.clj`, `resources/seon/tasks/seon.cluster.squash.edn`, `src/seon/task.clj` | R5 (proof), R6 (keys), R8 |
| free now | `src/seon/context.clj`, `src/seon/problems.clj`, `src/seon/cluster/branch_evidence.clj`, `script/seon/operator.clj`, `src/my/test.clj`, `bin/lane`, `test/seon/context_test.clj`, `test/seon/operator_test.clj`, `test/seon/problems_test.clj`, `test/seon/cluster/branch_evidence_test.clj`, `test/seon/test/selection_test.clj`, `resources/seon/schemas/seon.test.run.edn`, `resources/seon/schemas/seon.source.edn` | R4 (schema, selection), R9, R10, R12 (family), R6 (`context.clj`, `context_test.clj`) |

Order that avoids the held files: R10 and R9 (free files, host-bound `bin/` slice) can start once a lane claims them, but both need `seon.context/a-red-with-no-base-result-is-unknown` (R6) to exist first (their `:needs`). R4's schema and selection files are free, its `runner.clj` and `test.clj` hunks need `reach-incr` and `root-drift` released. R5 then R6 then R7, as the hybrid orders them. R8 after M7. R12 after R1 (done) and after R11's runner hunk.

## Three biggest risks

1. **R6 without R5 makes every red `unknown`.** The status `unknown` for a red with no base result turns each step with a red into not done, until R5's kept base run is read by the next step. Land R5 and read it, then R6.
2. **Four phases edit `src/seon/test/runner.clj` while `reach-incr` holds it, and `src/seon/test.clj` and `src/seon/turn.clj` carry `root-drift`'s uncommitted hunks.** A lane that commits its file while the hunks are unclaimed, or deletes a symbol another phase still calls, breaks HEAD for every later phase. Each phase must delete only the symbols whose last caller it converts.
3. **H2 stays open after R1 to R12.** R12 cannot see a stale red that is never rerun, and the Q2 ruling leaves tags undecided. Root must rule whether a tag is program (keyed) or cluster data (unkeyed) before the step proof reading tags is written again.

## Not verified

- No stored-run query on default in this pass. The red states above come from today's notes (`red-triage.md`, `narrow-check-test-results.md`, `central-round.md`, `g1-proof-bisect.md`, `chain-experiment-3.md`). Proofs for R4, R5, R6 (runner), R8, R9's new `testing` row, R10, R12 do not exist yet, so none is red as a deftest; each is red by absence, as the hybrid's §6 rows state.
- `bin/lane status` (no argument) omitted lane `reach-incr`; `bin/lane status reach-incr` shows its claims. The ledger listing should be checked by root.
- The R11 proof file (`test/seon/test/reach_test.clj`) is held by `reach-incr`; it was not read in this pass.
- `red-at-current-reach?`'s caller count in `resources/seon/tasks/*.edn` is by mention, not by code path.
