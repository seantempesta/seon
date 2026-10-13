---
title: "Plan (Opus): the step lifecycle, one judge, stage templates, handoff proofs"
type: research
status: independent plan for the design round of 2026-10-13; the hybrid decides
model: Claude Opus 5.5
basis: "HEAD 62eae1983 plus run-24.md (7454efe88); JVM-mode reads on default at basis t 536871322 (no SCI as root, no write, no restart)"
---

# Plan (Opus): the step lifecycle

**Broken first.** Default reads `:faulted`: one open fault, `seon.cluster.source/write-back!`, from Run 24. Run 24's
Implement attempt `8667f9088300` ended `:error` (write-back `:not-equivalent`, all 21 declarations of
`src/seon/edit.clj` offending, no relaunch). Its branch `:attempt-8667f9088300` is still on default. On that branch,
`seon.context/step-verdict` answers **`:done`, `unmet {}`** in 9–15 ms. The merge's static half, run by hand on the same
branch against main's head (below), answers the **same `:not-equivalent` refusal in 120–340 ms**. So the night's gap
reproduces on live data: the verdict said done, and the merge refused. Run 24 also shows the cause. The agent's REPL
`(require 'seon.edit)` and repeated `(require 'seon.sci.reader)` accreted into the namespace entity as a self-require and
duplicates. About 25 of its 39 calls went to looking for the ns form, and `my.program/source 'seon.edit` does not show
it.

## 0. What the code does today, and the one change

`seon.context/step-status` judges by `seon.context/unmet`: six clauses copied from a subset of what
`seon.cluster.source/prepare-merge!` and `accept-merge!` check, and judged against the fork point. The merge judges
against main's head, with the file proof, lint, dangling references and uncovered replacements. Every special case added
last night covers the difference between the two: `fixable-refusals`, `refused-merge-relaunch`, `:satisfied` and its
branch rule, and the fork-point proxy. **The change: one judge is extracted from the merge and called by both. A step is
`:done` exactly when that judge returns no refusal against main's head. The merge then re-runs only the writer's basis
checks and the test run on S.**

## 1. One judge (question 1)

**Measured on Run 24's branch, JVM mode, warm (3 runs):** `compare-branch` 8–10 ms, `replacement` 2–7 ms,
`seon.db/with` 35–70 ms, `proven-files` 54–84 ms, `contract-gaps` 0.6–1.2 ms, `seon.task/candidate-dangling` 0.3 ms.
`lint-errors` was 22 ms for `src/seon/edit.clj` and 52 ms for it plus `test/seon/edit_test.clj`. That totals about
**125–225 ms and ~175 MB per verdict**, against today's verdict at 9–15 ms and `prepare-merge!` at 1.7–4 s. The first
cold run was 340 ms. That is sub-second, but 10× too much to pay on every model call.

**The split.**

- **Static half: `seon.cluster.source/merge-refusals`** `[store branch head root proofs] → [:vector :seon.error/value]`. It
  holds what `prepare-merge!` does before `candidate-gate!`: `compare-branch` (conflict, empty delta), `replacement`,
  `db/with`, `proven-files`, `lint-errors`. It also takes `contract-gaps`/`uncontracted` and `candidate-dangling` out of
  `accept-merge!`, because neither needs a test run.
- **Test half: `seon.cluster.source/test-refusals`** `[database results proofs product head-commit] → refusals`. This is
  `accept-merge!`'s membership check (`unproven`), `designed` (`seon.test.runner/designed-red?`), `red-test-verdicts`
  with `seon.test/fork-point-red` at **main's head commit**, and `uncovered`. It takes a vector of recorded results,
  either S's run (merge) or the branch's latest results at reach (step).
- **`seon.cluster.source/merge-verdict`** is the two concatenated. This is the one judge. `prepare-merge!` calls
  `merge-refusals`, then runs the gate. `accept-merge!` calls `test-refusals` on S's run and keeps only the writer's
  basis checks: run on the tested branch, `program-written-since?` and `declarations-held-tx`. These are "decide at the
  authority": the writer validates the pre-read.
- **`seon.context/step-status`** becomes `(if (empty? (merge-verdict …)) :done :working)`. `unmet` becomes the
  refusals grouped by `:seon.error/type`. The step section shows the merge's own messages, path:row:col included. An
  agent can no longer read "done" while a refusal waits.

**How often.**

- The static half runs once per eval request whose recording wrote program rows. This is the same trigger as
  `seon.turn/step-due?`, inside `seon.turn/test-step`.
- It runs again at `seon.turn/task-end` when main's head commit has moved.
- Model calls read it from `seon.cache` and never recompute it. The key is content: the `compare-branch` digest maps
  (base, branch, head) of the changed identities plus the file digests of the paths `change-path` names. The 8 ms
  compare is paid per read; the rest is a hit.
- Run 24's 39-call attempt wrote program in about 6 requests, so it costs about 1.2 s total, against 180 wasted calls in
  Run 21.

**Thrown or returned, decided at the producer, never by a list.** A refusal of the candidate (lint, contract gap,
unrequired namespace, not-analyzable, dangling, red) is a declared variant that `merge-refusals` returns, and it is the
agent's to fix. Seon's own broken invariant is thrown as Seon's error and recorded once with its task, per CLAUDE.md
"nothing crashes". Write-back's reindex disagreeing with the program it just proved is such an invariant. The step
cannot be judged, so it stays `:working` with that error beside it, and the agent waits.
`seon.cluster.source/proven-file`'s `:not-equivalent` branch therefore throws. It also names the cause: the reindexed
ns form beside the expected one, and which identities the reindex lost, instead of all 21 by digest.

**Deleted.**

- `unmet`'s six clauses.
- `seon.context/reds` and the private `seon.context/designed-red?`. One predicate remains, the runner's.
- The private `seon.context/contract-gaps`, and the fork-point proxy in `step-status` and `step-observation`.
- `seon.cluster.agent/fixable-refusals` and `refused-merge-relaunch`.
- `failed-pass!`'s `fixable?` branch is replaced by one rule. A `:merge` that returns a declared `seon.merge/merge!`
  refusal now happens only when main moved between verdict and merge. While the dial lasts it relaunches with the
  refusal as reason, else root's problem task. Anything thrown is a fault plus a problem task.
- `relaunch-reason` and the dial stay, since `close!` uses them for `:capped`/`:stalled`.

**`:satisfied` goes.** A candidate that changes nothing main lacks is "already up to date", git's success, not a refusal.
`prepare-merge!`'s empty-delta refusal becomes an accepted no-op merge (`:seon.task/outcome :merged`, merge commit =
head). That deletes:

- the `:satisfied` arms of `step-status`, `seon.turn/task-end` and `close!`;
- the second `leaf-done` rule in `seon.task/ready-rules`;
- be25d0dfb's branch rule. A merged branch is already deleted.

**The namespace form (Run 24), fixed at its producer.**

- The ns entity's requires are a set keyed by namespace. A `require` of the namespace itself is a load, not a
  dependency, and is never recorded.
- The written ns form is derived from the base file's requires plus what the declarations use (F67's auto-require
  analysis), so REPL exploration cannot corrupt the file.
- `my.program/source` of a namespace symbol answers the ns form exactly as it will be written, beside its declarations.
  Correcting it is evaluating `(ns …)` in REPL terms.

## 2. Stage templates as data (question 2)

**Where.** `resources/seon/tasks/seon.step.edn`, with ordinary task entities `seon.step/prove`,
`seon.step/implement` and later `seon.step/design`. Each declares `:seon.task/step`, default `:seon.task/instructions`
(today's help text per kind, moved out of code) and no execution, so `seon.task/ready` never runs one. Its proofs are
the deftests tagged with it (§3). **Worth building now: Prove and Implement**, which are built, run, and failed on
lifecycle defects, not on missing stages. Design comes next, once a mined subject needs interface work. That changes D2,
since the owner now calls root's hand-written subject the design stage. Mine, Land and Retrospective stay root's or
`db<->flow`'s.

**`seon.task/effective`** merges one lineage. The step's kind finds its template by derived identity
(`seon.step/<kind>`), then the subject's ancestors down `:children`, then each predecessor along `:needs` that main
merged, in order, then the step. Each key composes by a property on its attribute schema in `seon.task.edn`
(`:seon.task/compose`), never a hand list:

| Key | Compose | Meaning |
|---|---|---|
| `:seon.task/instructions` | `:own` | the template's, overwritten by the step's own; never carried from a predecessor |
| `:seon.task/intent` | `:accumulate` | the subject's goal, then each predecessor's and the step's own refinement, joined in order |
| `:seon.task/product` | `:accumulate` | set union; obligations already join the successor-tagged tests |
| `:seon.task/references` | derived | `seon.task/task-delta` over the effective intent and instructions, never authored |

`my.task/refine!` writes the held step's own `:seon.task/intent`, never the subject's. Its write is the step's tracked
fact, so it reaches main through the step's merge (F63) and accumulates into every successor. That replaces D3's
cross-task write.

**Subjects declare their stages.** Ingestion (`seon.task/tasks-tx`, the owner that mints identities) expands
`:seon.task/chain [:prove :implement]` into children `prove-<name>`, `implement-<name>`, each needing the one before.
A filed defect becomes a chain by adding one key. For example, `seon.turn/a-send-inside-a-do-form-leaves-a-message`:

```clojure
#:seon.task{:name "a-send-inside-a-do-form-leaves-a-message"
            :chain [:prove :implement]
            :intent "`seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` finds `message-tx` nil … fix the producer that drops it."}
;; effective prove step:     intent as above; instructions = seon.step/prove's; proofs = seon.step/prove's handoff test
;; effective implement step: intent + Prove's refinement; instructions = seon.step/implement's;
;;                           proofs = the tagged deftest (already red) + seon.step/implement's handoff test
```

Its proof already exists red. Prove adds the reported input row, or finds nothing to add, and merges as already up to
date. That is the §1 no-op path, exercised.

## 3. Handoff proofs (question 3)

Each template carries one deftest, tagged with the template, that reads **task data** for the step under test. The
step is passed as an explicit input of the run (TASK-CHAIN §3b). `seon.turn/step-tests` names the step it judges in the
run request, and the runner binds it for the body (`binding` of `seon.test/*task*` to the effective step and the branch
database value), never by asking which task the agent holds. The reach digest of a template test includes the effective
task's content digest, so a refinement re-runs it and nothing else.

| Template | Its handoff deftest asserts |
|---|---|
| `seon.step/prove` | product nonempty; each product test on the branch, tagged to the successor, red by a failing report (`seon.test.runner/designed-red?`); the successor's `seon.task/obligations` proofs nonempty (F68); every reference in the successor's effective intent and instructions resolves on the branch (`seon.ref/references`, no problem) |
| `seon.step/implement` | proofs nonempty and green on recorded results at reach; every symbol the effective intent names as replaced is unmapped or has no caller; references resolve |
| `seon.step/design` (later) | each symbol the intent names as created resolves with no contract gap; each schema key resolves and its sample validates; the successor's instructions present |

`seon.task/obligations` returns the step's tagged proofs plus its template's. Being done reads them like any proof:
they are in the merge's `proofs`, so `test-refusals` refuses a red one. The merge carries the handoff, and readiness
(`leaf-done`, `:merged`) needs no further rule.

## 4. Phases

Each phase converts all callers, leaves HEAD booting, and has a red proof. P1–P4 (source, context, agent, merge, turn)
and P5–P6 (task, schema, task files, runner binding) are file-disjoint and run in parallel.

| Phase | Change | Red proof |
|---|---|---|
| P1 | Extract `merge-refusals` and `test-refusals`. `prepare-merge!` and `accept-merge!` call them; behavior unchanged | `seon.cluster.merge-test/the-judge-answers-what-prepare-merge-refuses` (table: lint, unrequired ns, contract gap, dangling, conflict). Red: the function does not exist |
| P2 | `step-status` = `merge-verdict` against main's head, cached in `seon.cache`; `unmet`, `reds`, the context `designed-red?`/`contract-gaps` and the fork-point proxy deleted. `seon.context-test` cases at 482, 503 and 623 convert to refusal types | `seon.context-test/a-step-is-done-only-when-its-merge-would-accept`, rows from Runs 22–24 (scratch helper, unrequired ns, corrupted ns form). Red today: verdict `:done` |
| P3 | Ns requires are a set, self-require never recorded, the written form is derived; `my.program/source 'ns` shows it; `:not-equivalent` throws with the ns-form cause | `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form` (Run 24's six forms). Red today |
| P4 | Already up to date = accepted no-op merge; `:satisfied` deleted (step-status, task-end, close!, `leaf-done`, be25d0dfb rule); `fixable-refusals`/`refused-merge-relaunch` deleted; failed-pass! uses one rule. db_flow_test 379, 1252 and 1292 convert | `seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-up-to-date` and `…/a-merge-refused-after-main-moved-relaunches-with-its-refusal`. Red today |
| P5 | `seon.step.edn` templates; `:seon.task/compose` on attributes; `effective` merges template, ancestors, merged predecessors and step; `:seon.task/chain` expansion; `refine!` writes the step | `seon.task-test/a-step-reads-its-templates-instructions-and-its-predecessors-refinements`. Red today |
| P6 | `seon.test/*task*` explicit input; template handoff deftests; task digest in reach | `seon.step-test/prove-leaves-its-successor-red-proofs` on Run 23's F68 fixture (successor with no proofs). Red today |

Plan doc updates go into the same commits: plan.md §0's phase table, TASK-CHAIN lines 14/51/166/191 and plan.md:196
(templates are now data), and the stale "lane f66-satisfied in flight".

**Run 25 (after P1–P4 restart batch).** Relaunch chain 5's Implement from `8667f9088300`'s end commit: the branch
that broke Run 24. Expect:

- the step section shows the derived ns form, or, before P3, the thrown write-back error beside a `:working` step;
- the agent corrects the form and the step reaches `:done` only when `merge-refusals` is empty;
- the merge is accepted with no refusal and 0 hand acts.

Count calls spent on the ns form; the target is under 3. **Run 26 (after P5–P6):** two filed turn defects with
`:seon.task/chain [:prove :implement]` launched together. That is the first concurrency run and the first template
run. Record each handoff test's result and whether a refinement reached the Implement step's effective intent on main.

## Answers in brief

1. One judge: `seon.cluster.source/merge-verdict` = `merge-refusals` (static, 125–225 ms warm, measured) + `test-refusals`
   (over recorded results, main's head). It runs per program-writing eval request and at task-end, and is cached by
   content for model calls. Delete `unmet`'s clauses, `fixable-refusals`, `refused-merge-relaunch`, `:satisfied` and the
   fork-point proxy. Seon's own invariant failures throw; candidate refusals return.
2. Templates are tasks in `seon.step.edn` (Prove, Implement now; Design next). `effective` composes per attribute
   property: instructions `:own`, intent and product `:accumulate`, references derived. Subjects use
   `:seon.task/chain`.
3. One handoff deftest per template reads the step bound as an explicit run input (`seon.test/*task*`). Obligations
   include it, so the one judge and the merge refuse a red handoff.
4. P1–P4 ∥ P5–P6, each with the red proof above; Run 25 relaunches Run 24's Implement; Run 26 is two templated turn
   defects at once.
