# Context judges: who decides done, accepted, and what happens after an attempt ends

Read-only research, 2026-10-13, branch refactor/agent-platform. Evidence is the source at HEAD (fdc34a9ba) as read
by symbol; history from `git log`. Items marked (inferred) are reasoning from code shape, not a measured or run result.

## (A) READ THIS, in order

1. `seon.cluster.source/results-verdict`: the one results judgment (candidate, main, judged) → refusals. Reads only.
2. `seon.cluster.source/merge-verdict`: the merge's run-level checks, then calls `results-verdict` over the run's members.
3. `seon.cluster.source/prepare-merge!`: builds the candidate branch, runs its proofs, returns the prepared merge.
4. `seon.cluster.source/accept-merge!`: re-judges with `merge-verdict`, then writes the merge in one transaction.
5. `seon.cluster.source/tested-task-basis` and `task-basis-held-tx`: the task's proof selection, and the writer's recheck of it.
6. `seon.context/step-observation` → `seon.context/step-status` → `seon.context/step-verdict`: the agent's preview of the same judgment on its branch.
7. `seon.turn/task-end`, `seon.turn/end-task!`, `seon.turn/end-task-call`: how an agent's task ends (propose only).
8. `seon.turn/step-proved?`, `seon.turn/prove-turn`, `seon.turn/test-step`: the tests the pass runs before a model call.
9. `seon.merge/merge-onto` (private), `seon.merge/merge!`, `seon.merge/merge-tx`: root's merge, the three paths.
10. `seon.cluster.agent/lifecycle-facts` → `next-actions` → `assignment-actions`: derives what follows an end.
11. `seon.cluster.agent/run-pass!`, `failed-pass!`, `apply-lifecycle!`, `close!`, `write-attempt!`: runs and applies those actions.
12. `seon.cluster.agent/plan` and `plan-facts`: starts attempts from task facts.
13. `seon.task/end-kind` (table `end-kinds`), `ready-rules`, `ready`, `done?`, `resolution`: task readiness and completion.
14. `seon.test.runner/red-result?`, `designed-red?`, `seon.test/fork-point-red`, `seon.test.runner/program-written-since?`: test-judging predicates.
15. `seon.merge/publish!` (checkout write-back) and `seon.cluster.source/publish!` (a different thing, see F).
16. `my.root/merge!`, `my.root/close!`, `seon.task/close!`: the root tools that reach the above.
17. Doc anchors: `docs/seon/schedule/cards/TASK-CHAIN.md` §2 "Rules and owners" (bullets "Accept once", "React once"); §Interfaces A2 (verdict and writer basis), A6 (assignment actions), and the "Ruling (option a, 2026-10-13, slice 4b)" and "Correction (slice 4a)" paragraphs inside A2.
18. Commits, each one line:
   - 251d40805: one `merge-verdict`; `prepare-merge!` takes the task symbol; writer's task-basis check first in `accept-merge!` (phase 4a).
   - 25973cd04: one `results-verdict`; `step-status` previews the merge with it (phase 4b).
   - 6548fa208: deletes `:satisfied`; only a merge completes a leaf; `chain-acceptance` retires (phase 4c).
   - 595f8f7de: one end-to-action owner, `assignment-actions` over `seon.task/end-kind` (phase 5a, A6).
   - ea800d326: done means the merge would accept, for an implement step; a refused merge relaunches with its reason.
   - 7eddc79aa: overlay enters namespace rows; an empty merge delta judges no write-back root (chain blockers).
   - a45f6a96b: a merge refuses an unjustified `:any`/`:some` slot as a missing contract (`seon.fn/contract-findings`).

## (B) Function table

Callers are rg hits for the fully qualified symbol and the alias forms (`source/`, `merge/`, `context/`, `turn/`, `task/`, `agent/`).

| Symbol | Purpose | Inputs | Decides | Callers |
|---|---|---|---|---|
| `seon.cluster.source/results-verdict` | The one results judgment | candidate db, main db, `:seon.source/judged` (members, results, proofs, product, red-on-target, identities, run id, branch) | every refusal: proof membership, red members (designed-red excepted), unknown members, contract gaps, uncovered replaced functions, dangling task refs | `merge-verdict`; `seon.context/step-status`. Reads only. |
| `seon.cluster.source/merge-verdict` | Run-level checks, then results judgment over the run | main, candidate, base, `prepared` | run on the tested branch; run complete; program unwritten since the run (`seon.test.runner/program-written-since?`); product results; `task-basis-refusal`; then `results-verdict` | `prepare-merge!` (for `:seon.source/red`), `accept-merge!`. Tests: `seon.cluster.merge-test`. |
| `seon.cluster.source/prepare-merge!` | Test the candidate on a fresh branch of head through the save gate; classify reds; return the prepared merge | source, held store, candidate, task sym, options (`:selection`, `:root`) | conflicts; selection/reach refusals; lint (`lint-errors`, merge side); proofs empty; gate run; red-on-target via `seon.test/fork-point-red` | `seon.merge/merge-onto`; `seon.cluster.merge-test`. Also docstring in `seon.cluster`. |
| `seon.cluster.source/accept-merge!` | Re-judge, then write the merge and its task completion in one transaction | source, store, prepared, optional merging tx | `merge-verdict` refusals; `declarations-held-tx` and `task-basis-held-tx` inside the writer | `seon.merge/merge-onto` only. |
| `seon.cluster.source/tested-task-basis` | The task's proof selection and declarations, as a basis | main, candidate, base, task sym | what the run selected (obligations and per-task declarations) | `prepare-merge!`, `task-basis-refusal`. |
| `seon.cluster.source/task-basis-held-tx` | Writer guard: refuse if the basis moved since the run | database, tested basis, candidate commit, base commit | `[]` or a `:seon.source/test-evidence-error` throw | Only `accept-merge!`, as the first op of its transaction. |
| `seon.cluster.source/contract-gaps` | Contract findings for functions of a candidate | database, identities | missing contract or unjustified `:any`/`:some` slot, per position | `results-verdict`; `uncontracted`; `seon.turn.batch` (defined-function measures). |
| `seon.context/step-status` | Preview: done or working, with refusals | step observation | `:done` iff `results-verdict` refuses nothing and a judged input exists | `seon.context/step-verdict`; tests. |
| `seon.context/step-observation` | Builds the judgment's input from the agent's branch | database, agent id, task sym | proofs + product + reaching tests (`seon.fn/gate-sets`), current results, red-on-main via `held-red`, run id | `seon.context/step-verdict`. |
| `seon.context/step-verdict` | Observation with its status | database, agent id | `step-status` over `step-observation` | `turn/task-end` (through `@context-step-verdict`), `turn/step-reds`, `context/task-done?`, `seon.context/step-section`. |
| `seon.context/task-done?` | Whether the step is done on the branch | database, agent id | `(= :done …step-verdict)` | `turn/proved-unanswered?`, `turn/next-agent-work` (both via `@context-task-done?`). |
| `seon.context/current-results` | Recorded result of each test at its current reach | database, tests | the result if its reach digest equals the test's current one | `step-observation`, `held-red`, `turn/step-proved?` (via `@context-current-results`). |
| `seon.turn/task-end` | Proposes how the held task ended, with no turn open | database, agent id | `:done` (step verdict), `:sent-back`, `:given-back`, `:capped`, `:stalled`; nil otherwise | `end-task!`. Docstring cites `seon.cluster.agent/next-actions`. |
| `seon.turn/end-task!` | Records the end in the agent's own world | cluster, agent id | writes summary, then `end-task-call`; a fault is recorded beside the pass | Called from the turn proc once a pass closes its turn (`:seon.turn.loop/outcome :closed`). |
| `seon.turn/end-task-call` | Transaction: admission closed, attempt end, commit, target, reason | database, agent id, end, commit, target | a no-op once admission is closed | `end-task!`. (Lives in `seon.turn`, not `seon.cluster.agent`.) |
| `seon.turn/step-proved?` | Every test the step is judged by has a result at its reach | database, agent id | results present (red or green) for the step's tests | `next-agent-work` (via `attempt-running?` gate), `proved-unanswered?`. |
| `seon.turn/prove-turn` | Run the step's proofs with no model call | cluster, work | the run; a refusal parks the turn | `next-agent-work`'s `:prove` situation. |
| `seon.turn/test-step` | Pass's automatic test step: proofs, then reaching tests | cluster, agent id, recording | runs proofs, then `seon.test/request-changes` | End of a proc pass that evaluated a form. |
| `seon.merge/merge-tx` | Writer-side facts merge for an assignment: base moved, outcome set | database, assignment, commit, projection, fork | `:merged` (done), `:closed` (sent-back), or nothing; no judgment | `merge-onto` (inside `:db.fn/call`), `accept-merge!` (as `merging`). Arity 5 and 4 only; the six-arity in TASK-CHAIN A2 does not exist. |
| `seon.merge/merge!` | Root's merge of an assignment's branch | execution, assignment key, optional root | three paths (see C); answers merge, conflict, or first refusal | `my.root/merge!`; `seon.cluster.agent/apply-lifecycle!` (`:merge`). |
| `seon.merge/publish!` | Writes merged declarations and task files into the checkout, one git commit | execution, assignment key | due work only; never a judge | `seon.cluster.agent/apply-lifecycle!` (`:publish`). |
| `seon.cluster.agent/plan` | Start, pause, stop, launch attempts from task facts | facts, registry, max running | attempts for startable leaves; `:start-flow`, `:pause`, `:resume`, `:stop`, `:cancel`, invalid-task problems | `run-pass!`. |
| `seon.cluster.agent/lifecycle-facts` | Observed facts per assignment and taskless agent | database, store, registry | the held rows (end on main or branch, refused, work-left, allowance, kept?) | `run-pass!` (`lifecycle!`), tests. |
| `seon.cluster.agent/assignment-actions` | Ordered actions for one ended assignment | one held row | `:stop`, `:relaunch`, `:problem` + `:close`, `:merge-base`, `:merge`, `:close`, `:publish`, `:delete-branch` | `next-actions` only. |
| `seon.cluster.agent/next-actions` | Maps `assignment-actions` / `taskless-actions` over held rows | held | the full action list | `run-pass!`; tests (`db_flow_test`). |
| `seon.cluster.agent/close!` (private) | Writes the end and optional relaunch on main, from the branch head | connection, action | ONE transaction: end facts plus `:relaunch`; `:cancelled` for cancel | `apply-lifecycle!` (`:close`, `:relaunch`). |
| `seon.cluster.agent/failed-pass!` (private) | Seon's own fault: ends `:error`, writes root's problem, records fault | handle, thrown failure | only throws; a returned merge refusal is not here | `run-pass!`'s catch, via `run-pass!` caller at agent line ~2429. |
| `seon.cluster.agent/run-pass!` (private) | One db<->flow pass: lifecycle (up to three derivations), then plan, then start | handle, routing | derivation order: stop, re-derive, merge, re-derive | Called in the db<->flow proc. |
| `seon.cluster.agent/write-attempt!` (private) | Writes an `:attempt` row (and agent creation) on main | handle, action | `task/attempt-tx`, one transaction | `apply-plan!`'s `:attempt` branch. |
| `seon.task/end-kind` | Maps an attempt end to what follows | end keyword | `:merge`, `:relaunch`, `:close`, `:cancel` from the `end-kinds` table | `assignment-actions`, `lifecycle-facts`, `blocking-ends`. |
| `seon.task/ready` | Leaves that should run now | database (main) | leaves with `:running` execution, not `leaf-done`, not blocked, with no non-relaunched non-merge end | `seon.cluster.agent/plan-facts`. |
| `seon.task/ready-rules` | Datalog rules: `leaf-done`, `execution`, `blocked`, `needs*`, `under` | none | the stratified definitions behind ready, done, resolution | `ready`, `done?`, `execution`, `tested-task-basis`, `seon.cluster.message`. |
| `seon.task/done?` | Task completion from main | database, handle | a leaf: `leaf-done`; a parent: no open leaf under it | `seon.task/resolution`; tests. Not verdict-based. |
| `my.root/merge!` | Root tool: merge an open assignment | request with assignment key | `open-assignment`, then `seon.merge/merge!` | Root tool surface. |
| `my.root/close!` | Root tool: decline an open assignment | request with key and reason | `open-assignment`, then `seon.task/close!` | Root tool surface. |
| `seon.task/close!` | Writes outcome `:closed` on the assignment, deletes its branch | context, key, outcome, reason | a refused delete is answered beside the closed assignment | `my.root/close!`. |

## (C) End-to-merge path: from "agent ends its turn" to "main has the merge and the next step is ready"

Notation: BR = agent's branch; MAIN = main's head; STORE = branch store; REG = Flow registry.

1. Agent's turn closes (`:seon.turn.loop/outcome :closed`, reads BR). The turn proc runs `seon.turn/test-step` (proofs and reaching tests on BR, via `seon.test/run`, `seon.test/request-changes`). Then `seon.turn/end-task!` (cluster).
2. `end-task!` calls `seon.turn/task-end` on BR. It asks `seon.context/step-verdict` on BR (step observation then step status, which calls `seon.cluster.source/results-verdict` with BR as candidate, MAIN as main, and BR's recorded results). `:done` is a proposal here.
3. `end-task!` writes `seon.context/end-summary-tx` and then `end-task-call` on BR: admission closed, `:seon.task.attempt/end` set, commit set, plus target and reason for `:sent-back`. Nothing reaches MAIN here.
4. The end datom on BR wakes db<->flow through the branch's listen route. `seon.cluster.agent/run-pass!` runs. It reads MAIN and the branch heads through `lifecycle-facts`. For each assignment, `branch-end` reads BR's end when MAIN holds none.
5. `next-actions` → `assignment-actions` on each row. `end-kind` `:merge` (for `:done` or `:sent-back`) with no refusal yields `:merge-base` (if needed), `:merge`, `:publish`, `:delete-branch`.
6. `apply-lifecycle!` `:merge` → `seon.merge/merge!` (`merge-onto`). Path (a) `:done` or changed-declarations end: `seon.cluster.source/prepare-merge!` tests the candidate on a fresh branch S (reads MAIN, BR, merge base; writes S and its run), then `seon.cluster.source/accept-merge!` calls `merge-verdict` (reads MAIN, candidate, base, and S's run) and transacts `declarations-held-tx`, `task-basis-held-tx`, `advanced`, and `merge-tx` on MAIN. Path (b) `:sent-back` or unmerged-free ended source: `merge-call` written as facts only, `merge-tx` closes the assignment `:closed`, no judgment. Path (c) base already at commit: nothing.
7. A returned refusal from `merge!` is written as `:seon.task/refusal` on the assignment (`apply-lifecycle!`). A thrown exception goes to `failed-pass!` (end `:error`, root problem, fault).
8. `run-pass!` re-derives lifecycle after a `:stop` and after a `:merge`, so a refused merge relaunches or closes before `plan` reads it.
9. `apply-lifecycle!` `:publish` → `seon.merge/publish!` writes files into the checkout. `:delete-branch` is last.
10. Now MAIN has the merge (`:seon.task/outcome :merged` on the assignment; `leaf-done` true). `plan-facts` reads `task/ready` on MAIN; `plan` launches the next step's attempt (`:attempt` via `write-attempt!`, then `:start-flow`), forked from MAIN.

Which database each reads: steps 1–3 read BR only. Step 4 reads MAIN and BR heads. Step 6 reads MAIN, S, BR, the merge base, and writes MAIN. Step 10 reads MAIN only.

## (D) Test-judging rules, and where each lives

| Judgment | Rule | Location(s) | Computed in more than one place? |
|---|---|---|---|
| Green | pass-count > 0, fail = 0, error = 0 (with no error at) | `seon.context/proof-status` (also requires reach digest equal); `seon.cluster.source/results-verdict` local `green?` (no reach check, relies on results being at reach); run totals `:seon.test/passed?` in `seon.test` | Yes: proof-status and results-verdict `green?` are the same predicate, written twice. |
| Red | pass-count present and fail + error > 0 | `seon.test.runner/red-result?` | No (one definition; used by results-verdict, step-observation, prepare-merge!, step-reds). |
| Designed red | red-result? and at least one failure report | `seon.test.runner/designed-red?`; applied only to product members in `results-verdict` | No. |
| Unknown | no result, or result at another reach | `results-verdict` via `red-test-verdicts` (`:unknown`); `context/proof-status` `:pending` | Two names for one absence (`:pending` on the agent side, `:unknown` on the merge side). |
| Red on target | red on the candidate, and red on main under the same evaluator | Merge side: `seon.test/fork-point-red` in `prepare-merge!` (may RUN a test on a fork). Preview side: `seon.context/held-red` (reads main's stored result at reach, runs nothing). | YES, two different computations. A preview can name a test red-on-target that the merge does not, or the reverse. |
| Step proved | every test the step is judged by has a result at its reach (red or green) | `seon.turn/step-proved?` over `step-tests`. Note `step-tests` recomputes the same proofs and product set that `context/step-observation` builds into `members`. | YES: the test set is derived twice (turn `step-tests` and context `step-observation`). |
| Judged set | proofs, plus product for prove, plus tests reaching the changed functions | merge side: `merge-verdict` members from the run; preview side: `seon.context/step-observation` via `seon.fn/gate-sets` | Two input builders (by design: run versus branch), one judge. |
| Replaced function covered | reached by a green member or designed red | `results-verdict` `uncovered` | No. |
| Contract gap | missing or unjustified contract | `seon.cluster.source/contract-gaps` via `seon.fn/contract-findings` | No (also used by `seon.turn.batch`). |
| Dangling task reference | task names not admitted on main | `seon.task/candidate-dangling` from `results-verdict` | No. |
| Program unwritten since run | `seon.test.runner/program-written-since?` | merge side only | No. |
| Task-basis moved | `task-basis-refusal` (writer recheck) | `merge-verdict` and `accept-merge!` via `task-basis-held-tx` | Intentional: recomputed at verdict and at the writer by design (A2). |

## (E) Spec versus code

What matches:
- A2 `merge-verdict` signature `[main candidate base prepared]` and `results-verdict` `[candidate main judged]`: match.
- A2 correction (slice 4a): `task-basis-held-tx` runs first in `accept-merge!`'s transaction, `merge-tx` keeps its existing arities: match. `merge-tx` has arities 4 and 5 only, as the correction says.
- A2 ruling (option A): `prepare-merge!` takes the task symbol and calls `tested-task-basis` itself: match.
- A2 ruling (option a): one `results-verdict` judge; `step-status` previews it: match. The preview runs no test: match (`held-red` reads stored results).
- A6: `assignment-actions` takes `held-assignment` with `allowance-left`, `exited?`, `work-left?`, `refused?`: match. Ops `:stop :merge-base :merge :publish :close :delete-branch :problem :relaunch`: match. No `:reopen`: match.
- A6 exit ruling: `run-pass!` re-derives after a `:stop` and after a `:merge`: match.
- A6 refusal-versus-fault ruling: returned refusal is recorded as `:seon.task/refusal`; thrown is `failed-pass!`: match. `fixable-refusals` gone: match (no hit).
- "React once": `plan` starts from task facts; `test-step` and `prove-turn` acquire evidence; `assignment-actions` derives end actions; `task/ready` excludes unrelaunched ends: match.

What differs:
- TASK-CHAIN §2 "Accept once" still says the tested task basis and proof inputs are carried "into `seon.merge/merge-tx`'s writer comparison". The A2 correction says that is wrong (Datahike evaluates each `:db.fn/call` after earlier ops) and moved it to `task-basis-held-tx` in `accept-merge!`. Code follows the correction; §2 is stale. (Observed.)
- TASK-CHAIN §2 "Accept once" says "`seon.merge/merge-tx` consumes acceptance; only `:merged` completes a leaf." Code: `merge-tx` writes `:merged` only inside `accept-merge!`'s transaction, because `merge-onto` passes `merge-tx` in `merging`. Match in effect; the wording of who calls merge-tx is loose.
- TASK-CHAIN §2 "Accept once": "`seon.turn/task-end` proposes `:done` when it refuses nothing, never successful completion." Code matches.
- TASK-CHAIN §2 "React once": "Root reviews costs after `seon.task/done?`." `task/done?` is a leaf-merged-on-main rule, not the verdict. So "done" in §2 means "merged", while `step-status` `:done` means "would merge". Two meanings of done (see G).
- A6 says `close!` decisions move out of `close!`. `seon.cluster.agent/close!` still handles `:cancelled` ends and relaunch writes; that is a writer for its action, not a decision. Match in intent (inferred).
- A2 Test: "transact an ordinary task refinement ... call `accept-merge!` with the unchanged prepared value." That test is `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task`, status red at HEAD per §6 phase status. Code has the held-tx guard; I did not run the test (read-only).
- The spec names `seon.task/end-kind` table `end-kinds` as "the one table". Code: one table, yes; but `seon.task/blocking-ends` derives from it. Match.

## (F) Open questions

1. Is `:done` (step-status, from preview) allowed to disagree with the merge's `red-on-target`? Preview (`held-red`) reads stored results; merge (`fork-point-red`) may run. Which should win for a `:done` proposal: the merge's answer is the only one that completes. Decide whether the preview should drop `on-target` (it then over-refuses, not under-refuses) or call `fork-point-red` (it then runs tests on every turn end, which costs).
2. Two `publish!` names: `seon.cluster.source/publish!` (whole-source publication, schema reload, used by `seon.cluster` at line ~1885) and `seon.merge/publish!` (checkout write-back for a merged assignment). They are unrelated; a grep for "publish" lands on both. Rename one.
3. Three `close!` names: `seon.cluster.agent/close!` (writes end and relaunch), `seon.task/close!` (root decline, writes `:closed` and deletes branch), `my.root/close!` (tool). Is `seon.cluster.agent/close!` the only writer of `:cancelled`? (It is, per its docstring.)
4. Does `seon.turn/prove-turn` plus `test-step` run the same proofs twice in one pass? `prove-turn` runs `run-proofs`, and `test-step` runs proofs again over `task-proofs` on the same branch. Reuse by reach digest may make the second run free; not measured.
5. `end-task!` is called only after `:closed`. If a pass ends with `:capped` or `:stalled` set on the turn, is `task-end` still the only proposer? (Inferred yes; not traced in full.)
6. The `seon.context/task-done?` shortcut is `(= :done …step-verdict)`, a full observation per call, called per agent pass in `next-agent-work`. Cost not measured.

## (G) Overengineered paths

Every item is observed in code unless marked (inferred). Lines removable are rough estimates and inferred from the surrounding code.

- G1. `seon.cluster.source/prepare-merge!` judges twice in one merge. It calls `merge-verdict` to fill `:seon.source/red`, then `accept-merge!` calls `merge-verdict` again. Simpler: `prepare-merge!` returns the prepared value without `red`, and `accept-merge!` is the only judge that reports refusals. Removable: about 6 lines (inferred).
- G2. `seon.cluster.source/results-verdict` local `green?` duplicates `seon.context/proof-status`'s green rule. Simpler: one `seon.test.runner/green-result?` used by both. Removable: about 4 lines (inferred).
- G3. Red-on-target has two computations (`seon.test/fork-point-red` in `prepare-merge!`; `seon.context/held-red` in `step-observation`). Simpler: keep one, and decide per F1. Removable: about 15 lines (inferred).
- G4. The step's test set is built twice: `seon.turn/step-tests` (for `step-proved?` and `run-proofs`) and `seon.context/step-observation` (members). Simpler: `step-proved?` reads `:seon.source/members` from the observation. Removable: about 12 lines (inferred).
- G5. `seon.turn` has about twelve `(delay (requiring-resolve …))` indirections, e.g. `context-step-verdict`, `context-task-done?`, `context-current-results`, `context-end-summary-tx`, `test-run`, `test-latest-results`. These exist to avoid namespace cycles (inferred cause). Simpler: remove the cycle (move the shared function down into a namespace both can require) and call the vars directly. Removable: about 30 lines across turn.clj (inferred).
- G6. `seon.context/step-status` is a thin wrapper that adds `:seon.task/status` to `results-verdict`'s output. Simpler: call `results-verdict` in `step-observation`'s caller, or merge the status into `step-verdict`. Removable: about 6 lines (inferred).
- G7. `seon.context/task-done?` is `(= :done (:seon.task/status (step-verdict …)))`, which builds the whole observation and two test reads just to answer a boolean that `next-agent-work` asks every pass. Simpler: a cheap precheck on stored results, with the full verdict only at end. Cost not measured (inferred).
- G8. `seon.cluster.agent/run-pass!` derives the lifecycle up to three times per pass (`lifecycle!`), and each derivation rereads every assignment and calls `source/unmerged-count` (a branch compare) per judged row. Whole-program work per pass, not per changed assignment. Simpler: derive only the changed rows after an applied action (inferred cost; not measured).
- G9. Three "unfinished" problem texts for the same class `:unfinished-task`: `seon.cluster.agent/unfinished-intent` (in `assignment-actions`) and the inline text in `failed-pass!`. Simpler: one constructor for the problem text, called from both. Removable: about 8 lines (inferred).
- G10. `seon.cluster.agent/assignment-actions` special-cases `copied?` (an end main holds with no merge base) and emits `:merge-base` only for that. Simpler: copy merge base when the end is written, and drop the special case. Removable: about 6 lines (inferred). Needs a check that old copied ends do not exist in the live store (not checked).
- G11. `seon.merge/merge-onto` has three merge paths with a duplicated `answer` helper and `merged` called twice (once for `:conflicts` in `answer`). Simpler: compute `:conflicts` once. Removable: about 6 lines (inferred).
- G12. `seon.task/done?` and `seon.task/ready` both encode "leaf merged on main" (`leaf-done`), and `done?` restates the open-leaf query that `ready` already has. Simpler: keep `leaf-done` and `open`, drop the second copy. Removable: about 5 lines (inferred).
- G13. `seon.task/end-kind` plus `blocking-ends` plus `ready`'s not-join clause and `assignment-actions`' kind tests each test end kinds. Simpler: the `end-kinds` table only, with `blocking-ends` as its one derived set. Mostly already so (inferred, low gain).
- G14. Two `(:seon.task/outcome …)` writers: `seon.merge/merge-tx` (`:merged`, `:closed`) and `seon.task/close!` (`:closed`), plus `seon.cluster.agent/close!` (`:closed` for cancelled). Three writers of one fact. Simpler: one writer per outcome (inferred; each is correct for its path).

## Verification limits

- Read-only. I did not run any test, the REPL, or `bin/seon`. The "red at HEAD" statuses come from TASK-CHAIN §6 and the commit messages, not from a run.
- The body of `seon.cluster.agent/plan` and `plan-facts` was read only at the docstring and top level.
- `seon.test/passed?` (run totals) and `seon.test.runner/latest-results` were not traced in full.
- Line-count removals in G are estimates.
