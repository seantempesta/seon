# Pre-brief: TASK-CHAIN slices S3a-2, S3a-3, S3f-2

Dated evidence, 2026-10-14, about 09:40-09:50 UTC. Checkout HEAD `8f192ba62` with a dirty working tree. `src/seon/turn.clj`
carries uncommitted edits (`git diff --stat`: 1 file, +66/-39), held by lane `s2b-1`. Gathering only: no source edits, no
tests run. Counts are `rg -F` over `src test script resources`. Stored-run reads were JVM-mode, read-only, on cluster `default`.

Legend: VERIFIED = read or counted in this session. UNVERIFIED = not checked, with the reason stated.

## 0. Headline facts

- **No §7 red proof exists for any of the three slices.** The S3a-2 row names no deftest. `seon.cluster.db-flow-test/an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl` (S3f-2) and `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call` (S3a-3) are ABSENT (`rg -F`, zero hits in `test`, `src`, `resources`).
- **Four card statements contradict the code** (§5 below): `attempt-running?` reads main's head, not the branch; `proved-unanswered?` and `step-proved?` read tag-derived obligations, not only the proofs field; `seon.context/chain` walks the tree (`children`, `needs`), not `next`; `seon.task/advance-tx` does not exist (its nearest neighbour, `advance-assignments-tx`, moves revisions).
- **`:seon.task/model` does not exist** anywhere (ABSENT, zero hits). No task-level model selection or `:outside` gate exists.
- **No stored result exists for the slice tests.** The default store holds 2 test runs (`:seon.test.run/at` 2026-10-14T08:36:29Z with 0 identities, and 08:41:11Z with 2 identities; both policy `:named`, branch `:cluster-default`). `seon.test.runner/latest-results` over the 7 deftests in §3 returns 0 entries, so neither a red nor a green stored result exists for them (VERIFIED). This is absence of evidence, not a pass: reds are UNVERIFIED.
- **Two lanes hold overlapping files**: `s2b-1` holds `src/seon/turn.clj` (S3a-2 and S3f-2 both edit it) and `s3b` holds `resources/seon/tasks/seon.cluster.agent.edn` (S3a-3's plan and task-file edits go there). See §4.

## 1. S3a-2: turn authority and context

Card row (TASK-CHAIN §7 line 575): "`attempt-running?` on the branch, `proved-unanswered?`/`step-proved?` on the field, `seon.context/chain` through `next`". Owner Opus.

| Name | Where (HEAD) | Kind | Callers (HEAD, file: count) | Status |
|---|---|---|---|---|
| `seon.turn/attempt-running?` | `src/seon/turn.clj` 2228 (`defn-`) | reads `(task/execution (or (task/main-head database) database) handle)`, main's head | `src/seon/turn.clj` 4 (defn 2228; uses 2245, 2313, 2321) | VERIFIED |
| `seon.turn/proved-unanswered?` | `src/seon/turn.clj` 2236 (`defn-`) | `attempt-running?` + `step-proved?` + `latest-answering-turn-t` + `task-done?` | `src/seon/turn.clj` 3 (2236 def, 2264 doc, 2326 call); `resources/seon/tasks/seon.turn.edn` 1 (plan text) | VERIFIED |
| `seon.turn/step-proved?` | `src/seon/turn.clj` 5263 (public) | every test of `step-tests` has a current result | `src/seon/turn.clj` 6; `test/seon/turn_test.clj` 2 (2072, 2155); `resources/seon/tasks/seon.test.runner.edn` 1, `seon.turn.edn` 1 (plan text) | VERIFIED |
| `seon.turn/task-obligations` (`defn-`, 5208) | calls `seon.task/assigned-obligations` | | `src/seon/turn.clj` 5 | VERIFIED |
| `seon.turn/task-proofs` (`defn-`, 5217) | wraps `task-obligations` | | `src/seon/turn.clj` 4; `resources/seon/tasks/seon.task.edn` 2 (plan text) | VERIFIED |
| `seon.turn/step-tests` (`defn-`, 5225) | wraps `task-obligations` | | `src/seon/turn.clj` 5 | VERIFIED |
| `seon.task/assigned-obligations` | `src/seon/task.clj` 614 | pins `obligations` to main's head basis | `src/seon/task.clj` 1, `src/seon/turn.clj` 2, `src/seon/context.clj` 4 | VERIFIED |
| `seon.task/obligations` | `src/seon/task.clj` ~566 | proofs = `tagged` (`:seon.task/tasks` tags, lines 586-588) ∪ `:seon.task/proofs` field (line 595) | `src/seon/task.clj` 3 (code); `test/seon/task_test.clj` 5 and others (`rg -l` shows 20+ files) | VERIFIED |
| `seon.context/chain` (`defn-`) | `src/seon/context.clj` 231 | walks `:seon.task/children` (subject), `:seon.task/needs` (before), successors via `needs` (after); no `seon.task/next` read in its body | `src/seon/context.clj` 3 (incl. 585); `test/seon/context_test.clj` 3 (incl. deftest `a-steps-block-names-its-chain` at 181); `test/seon/help_test.clj` 2; `test/seon/cluster/db_flow_test.clj` 1 (1288); `resources/seon/schemas/seon.context.edn` 4; `resources/seon/help.md` 1 | VERIFIED |
| `seon.context/task-done?` | `src/seon/context.clj` 373 (public) | `:done` of `step-verdict` | `src/seon/turn.clj` 3 (incl. defonce 68-69); `test/seon/context_test.clj` 4; `resources/seon/tasks/*` plan text (count UNVERIFIED) | VERIFIED |
| `seon.context/step-status` | `src/seon/context.clj` 160 | calls `source/results-verdict` over `judged` and returns `:seon.source/refusals` | `src/seon/context.clj` 4; `test/seon/test_failure_facts_test.clj` 2; `test/seon/context_test.clj` 1; `src/seon/cluster/source.clj` 2 (docs) | VERIFIED |
| `seon.context/step-section` | `src/seon/context.clj` 356 | red tests come from `:seon.source/red-tests` of refusals; proofs also listed in `task-sections` (559) | UNVERIFIED (not counted) | VERIFIED |
| `seon.context/current-state` | `src/seon/context.clj` 595 | block build; reads `task-sections` | UNVERIFIED (not counted; `my.task` and render callers exist) | VERIFIED (definition); ordering vs the test step UNVERIFIED |

Other relevant readers in the path: `seon.turn/next-agent-work` (turn.clj 2250) is where the first-turn decision happens: the
`:prove` branch at 2313 uses `attempt-running?` and `step-proved?`; the `:held` branch at 2321 and `proved-unanswered?` at 2326.

§7 red proof for S3a-2: the row names no deftest. ABSENT (TASK-CHAIN §7 form unmet).

Context-sanity defects this row names (`docs/research/agent-platform/2026-10-14/context-sanity.md` lines 84-125):
- Defect 2 (phantom merge refusal, red proofs repeated): mechanism VERIFIED. `step-status` labels `source/results-verdict` output as refusals and `step-section` emits them under the step. Defect 2 says the error map comes from `seon.cluster.source/accept-merge!`; the code path in `step-status` calls `results-verdict`, not `accept-merge!` (VERIFIED at context.clj 160-172). Whether a pre-work block triggers it: UNVERIFIED.
- Defect 3 (block stale within its reply): the block is built by `current-state` (595); the position of that build relative to the `seon.turn.loop` test step is UNVERIFIED (not read in the loop).

## 2. S3f-2: per-task model

Card row (TASK-CHAIN §7 line 582): "owned `:seon.task/model`, `:outside` starts the agent with no model turns". Owner Opus (after S3a).

| Name | Where (HEAD) | Status |
|---|---|---|
| `:seon.task/model` (schema key) | none | ABSENT (zero hits in `src`, `test`, `resources`) |
| `:outside` as a task value | none. `:outside` exists only as the eval origin (`:seon.cluster.eval/origin`, turn.clj 719, 2870) and `:my.turn/delivered-to` (`resources/seon/schemas/my.turn.edn`) | VERIFIED (unrelated meaning) |
| `:seon.config.ai/model` readers | `src/seon/agent.clj` 152 (`effective-settings`), `src/seon/eval/drive.clj` 337, `src/seon/problems.clj` 416, 421, 595, 602, `src/seon/config.clj` 59, 75; `resources/seon/schemas/seon.ai.model.edn` 2, `seon.eval.drive.edn` 36 | VERIFIED (literal key; `ai/settings` in turn.clj 3601 reads it through `config/effective`, not literally) |
| Model turn path | `next-agent-work` (turn.clj 2250-2335) decides whether a model turn opens (`:open` situation at 2330); `call-turn` (turn.clj 3901, `defn-`) runs the provider call; `provider-targets` (turn.clj 3588, `defn-`) reads `ai/settings` (3601) and `ai/agent-overlay` (3602); the injected completion is `:seon.turn.loop/complete`, defaulting to `seon.ai/complete` (`src/seon/cluster.clj` 3468; `src/seon/ai.clj` 1404) | VERIFIED |
| The gate point for `:outside` | `next-agent-work` `:held` branch (turn.clj 2321: "A :held TASK OPENS NO MODEL TURN") is the existing per-task gate; no `:outside` clause exists beside it | VERIFIED |
| Where a model is named per task | no per-task field; `ai/settings` takes `defaults overrides` (turn.clj 3601) | VERIFIED |

§7 red proof for S3f-2: `seon.cluster.db-flow-test/an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl` ABSENT.

## 3. S3a-3: advance

Card row (TASK-CHAIN §7 line 576): proof `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call`. Owner Opus.

| Name | Where (HEAD) | Kind | Callers (HEAD, file: count) | Status |
|---|---|---|---|---|
| `seon.task/advance-tx` | none | card's new writer (§2.5) | none | ABSENT |
| `seon.task/advance-assignments-tx` | `src/seon/task.clj` 698 | moves each assignment's `:seon.task/revision` to its declaration's revision; not the card's advance | UNVERIFIED (not counted) | VERIFIED (name is a different function) |
| `seon.cluster.agent/run-pass!` (`defn-`) | `src/seon/cluster/agent.clj` 2423 | lifecycle (`next-actions` re-derived after `:stop` and after `:merge`, lines 2431-2440), then `launch-facts`/`launch-actions` (plan phase, 2454-2456), then `agents-to-start` | `src/seon/cluster/agent.clj` 2 (defn and db<->flow call at 2549) | VERIFIED |
| `seon.cluster.agent/chain-facts` (S3a-1, done) | `src/seon/cluster/agent.clj` 1775 | reads each chain fact's branch head, walks `:seon.task/next` (1771-1773) | `src/seon/cluster/agent.clj` 3; `resources/seon/schemas/seon.flow.actions.edn` 1 | VERIFIED |
| Per-step lifecycle rows that §6 deletes for tasks with a `next` | `next-actions` emits `:merge-base`, `:merge`, `:publish`, `:close`, `:relaunch`, `:delete-branch` (agent.clj 2279-2296); `:relaunch` row at 2269; dispatch at 2087, `apply-lifecycle!` 2377-2401 | | | VERIFIED |
| `seon.task/relaunched` and `:seon.flow.actions/relaunch` | used by `launch-facts` (agent.clj ~1816) | §6 deletes them | UNVERIFIED (not counted) | VERIFIED (present) |

§7 red proof for S3a-3: `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call` ABSENT. Its neighbour `seon.cluster.db-flow-test/root-starts-a-chains-first-task-on-its-branch` (S3a-1 proof) exists at `test/seon/cluster/db_flow_test.clj` 393.

## 4. Stored runs and reach

- Default store: 2 `:seon.test.run` entities (see §0). VERIFIED.
- Tests that name the slice symbols directly (not the full reach set, which was NOT computed: UNVERIFIED):
  - `seon.turn-test/a-fresh-attempt-of-a-task-naming-no-proof-is-work` (turn_test.clj, uses `step-proved?` at 2072 region)
  - `seon.turn-test/a-prove-attempts-first-product-test-run-after-its-eval-is-its-proof-run` (turn_test.clj 2155 region)
  - `seon.context-test/a-steps-block-names-its-chain` (context_test.clj 181, 200, 212)
  - `seon.context-test/current-state-shows-the-current-task-declaration`
  - `seon.context-test/a-step-is-done-only-when-its-merge-would-accept` (context_test.clj 509 region)
  - `seon.cluster.db-flow-test/root-starts-a-chains-first-task-on-its-branch` (db_flow_test.clj 393)
  - `seon.test-failure-facts-test/an-unimplemented-stub-is-a-prove-steps-designed-red`
  - Stored results for all 7: none (VERIFIED). Reds among the full reach: UNVERIFIED (no stored runs; not run, per instructions).

## 5. Card vs code

1. **S3a-2 `attempt-running?`.** Card §2.2 step 3: "`seon.turn/attempt-running?` changes from main's head to the branch: root is the only writer of a chain task's execution, on the chain branch." Code, turn.clj 2228-2234: "True when the task `handle` ... runs under execution :running at its authority, main's head seen from `database`, the agent's branch ... Root decides execution on main; the branch's copy is never read." Code: `(= :running (task/execution (or (task/main-head database) database) handle))`. The card asks for the reverse direction.
2. **S3a-2 `proved-unanswered?` / `step-proved?` "on the field".** Card §2.3: "`seon.turn/proved-unanswered?` and `seon.turn/step-proved?` stay, reading the task's `proofs` field". Code: `step-tests` → `task-obligations` → `task/assigned-obligations` → `task/obligations`, which builds proofs from `tagged` (`:seon.task/tasks` tags, task.clj 586-588) unioned with the field (task.clj 595). The field is not the sole source at HEAD, and §6 (S2c-1/S2c-2) has not run.
3. **S3a-2 `seon.context/chain` through `next`.** Card §7 row S3a-2: "`seon.context/chain` through `next`". Code, context.clj 231-250: "the subject (the task whose `:seon.task/children` holds it), the steps before it (its `:seon.task/needs`) and the steps after it (the tasks that need it)". No `seon.task/next` read in the function. §6 says the `children`/`needs` version is deleted in S3e, so S3a-2 and S3e touch the same function.
4. **S3a-3 advance.** Card §2.5: "`seon.task/advance-tx`, new". Absent (§3). Card §2.5 also says the plan phase "re-derives after `:stop` and `:merge`", which matches `run-pass!` (agent.clj 2431-2440): consistent.
5. **S3f-2 model per task.** Card §2.2: "owned `:seon.task/model`, a model name (absent: the cluster's `:seon.config.ai/model`) or `:outside`". Code: no `:seon.task/model`; the cluster model is read through `ai/settings` (turn.clj 3601). Absence, not contradiction.
6. **Context-sanity defect 2 names `accept-merge!`.** `docs/research/agent-platform/2026-10-14/context-sanity.md` line 88: "an error map from `seon.cluster.source/accept-merge!`". Code: `step-status` (context.clj 165-172) calls `source/results-verdict`. The source of the text is UNVERIFIED beyond that call.

## 6. File collisions

Lane claims at 09:40 (`bin/lane status`; `bin/lane status <lane>` for each):
- `s2a` (9 paths): `src/seon/test/runner.clj`, `src/seon/step.clj`, `test/seon/test_support.clj`, `test/seon/task_test.clj`, `resources/seon/schemas/seon.test.edn`, `test/seon/step_test.clj`, `test/seon/design_prototype_test.clj`, `docs/research/agent-platform/2026-10-14/s2a-test-clj.patch`, `resources/seon/tasks/seon.test.runner.edn`. No overlap with the slice files of this brief (S3a-2/3, S3f-2) except `test/seon/task_test.clj` if S3a-3 touches the task writer's tests (UNVERIFIED).
- `s2b-1` (5 paths): `src/seon/test.clj`, `src/seon/turn.clj`, `resources/seon/schemas/seon.turn.loop.edn`, `test/seon/test_test.clj`, `resources/seon/tasks/seon.turn.edn`. **Collision:** `src/seon/turn.clj` is edited by S3a-2 and S3f-2, and `turn.clj` has uncommitted edits (+66/-39) from this lane at 09:44. Serial after `s2b-1`, or as a patch.
- `s3b` (6 paths): `src/seon/merge.clj`, `src/seon/cluster/source.clj`, `test/seon/cluster/merge_test.clj`, `resources/seon/tasks/seon.merge.edn`, `resources/seon/tasks/seon.cluster.agent.edn`, `docs/seon/schedule/cards/TASK-CHAIN.md`. **Collision:** `resources/seon/tasks/seon.cluster.agent.edn` for S3a-3 (plan and task-file edits in `cluster/agent.clj`'s task file), and TASK-CHAIN.md for every slice's card corrections (§5 above).
- Lanes `s2b-2`, `s2c-1`, `s2c-2`, `s3a`, `s3c`, `s3d-2`, `s3e`, `s3f`, `b1-durable`, `writeback-fix`, `s1d-2`, `boot-dup-analysis`, `overlay-p3`, `teststep-verdict`, `projections`, `check-class-race`, `reconcile-writer`: hold no claims (VERIFIED).
- Between the slices: S3a-2 and S3f-2 both edit `src/seon/turn.clj` (`next-agent-work` at 2250; `attempt-running?` and `proved-unanswered?` at 2228-2236). Serial: S3a-2 first (its card says S3f-2 is "after S3a").
- `src/seon/cluster/agent.clj`: S3a-3 (`run-pass!`, `:relaunch` rows) and S3f-2 (`agents-to-start`, the start gate) both edit it; no lane claims it (VERIFIED). Serial.
- `src/seon/context.clj`: S3a-2 (`chain`) and S3e (delete `chain`'s tree readers). Unclaimed (VERIFIED). Serial.
- `src/seon/task.clj`: S3a-3 (`advance-tx`). Unclaimed (VERIFIED).

## 7. Landed today that these slices build on

`git log --oneline 5fddc2bfd..HEAD -- src/seon/turn.clj src/seon/context.clj src/seon/cluster/agent.clj src/seon/task.clj`:

- `7064853fc` The test step's reaching verdict states what ran: n nearest of m reaching, and claims no pass over the rest
- `99997d214` TASK-CHAIN S4a-0: file admission accepts a task without a plan (the `:no-plan` refusal goes)
- `d8332722c` TASK-CHAIN S3a-1: root reads chains and starts a chain's first task on its branch (`chain-facts`, `run-pass!`)
- `831df5f72` TASK-CHAIN S1c-2: `seon.task/chain!` writes a linked chain on its own branch, then the chain fact on main

Card status for the prerequisites: S3a-1 is marked done (TASK-CHAIN §7 line 28), with its proof `root-starts-a-chains-first-task-on-its-branch` present. S2a's proof and S2b-2's `done-refusals` are not in the code (per `docs/research/agent-platform/2026-10-14/prebrief-s2.md`, unchanged here).

## 8. Stoppers

1. No §7 red proof exists for S3a-2 (row names none), S3a-3 (`a-two-task-chain-reaches-its-second-model-call`) or S3f-2 (`an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl`).
2. `seon.task/advance-tx` absent (S3a-3's core writer).
3. `:seon.task/model` absent (S3f-2's whole change).
4. `attempt-running?` reads main's head; the card says the branch (§5.1).
5. `proved-unanswered?` and `step-proved?` read tag-derived obligations, not only the field (§5.2).
6. `seon.context/chain` walks the tree, not `next` (§5.3); S3a-2 and S3e touch it.
7. `src/seon/turn.clj` is claimed by `s2b-1` and dirty (+66/-39): S3a-2 and S3f-2 both wait.
8. `resources/seon/tasks/seon.cluster.agent.edn` is claimed by `s3b`: S3a-3 waits or takes a patch.
9. No stored red or green for any slice test; reds for the full reach are UNVERIFIED.
10. Context-sanity defect 2 names `accept-merge!`; the code path is `results-verdict` (§5.6). Defect 3's ordering is UNVERIFIED.
