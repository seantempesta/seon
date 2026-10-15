---
title: "Recovery step dig: the merge candidate, write-back, step templates, rulings"
type: research
status: evidence for the design lane; no judgement
created: 2026-10-15
lane: recovery-dig (Haiku)
---

# Recovery step dig (merge step)

Naming: per root's ruling, the step is the **merge step** (`seon.step.merge`). Note that no
`resources/seon/tasks/seon.step.merge.edn` exists today; the only merge-named task resource is
`resources/seon/tasks/seon.merge.edn`. The three step templates present are `seon.step.design.edn`,
`seon.step.prove.edn` and `seon.step.implement.edn`.

Symbols are cited without line numbers. Quotes are at most two lines.

## 1. How the merge candidate works today

**Creation.** `seon.cluster.source/prepare-merge!` (`src/seon/cluster/source.clj`) builds the candidate name
`(keyword (str "merge-" (id/id)))` inside its `:else` branch, after all refusals before it (root-error, lint,
empty proofs). The candidate is created by `seon.cluster/candidate-gate!`, which resets or creates a branch at
main's head with `registry/branch!` and writes the change through `write!`. `write!` transacts `merging` plus
the three-way `tx` (`replacement branch head delta`). Then `reaching-run` runs the tests reaching the identities.
The candidate's own writes and runs happen under `db/with-branch`, so the candidate is the only branch written.

**What is applied to it.** The delta is `whole` (changed-on-branch plus added plus retracted) or the explicit
`:selection`. The merge list is `merge-list` over `merge-seeds` (task proofs plus product), with the stays set
recorded as `:seon.merge/stays`. The `:merging` transaction data (the facts from `seon.merge/merge-tx`) is
written into the candidate in the same transaction. Tasks named in the delta are proven on the candidate.

**Which tests run there.** The seeds' proofs (`proofs` = owed proofs plus any Prove product), the tests
reaching the identities that `write!` touched (`fn/report-identities`), and the proofs themselves. The run is
`reaching-run` through the candidate's agent acquisition (`cluster.agent/acquire-context!` with
`{:seon.agent/branch branch}`), so it is a fork of the agent's SCI context on the candidate branch.

**Judgement.** `prepare-merge!` computes `red` from the gate's `:seon.test/results` (`red-result?`) and passes
the prepared value to `merge-verdict` (`source.clj`). `merge-verdict` reads the tested branch's stored run
(`seon.test.runner/run-results`, `latest-results`), checks `program-written-since?`, checks
`task-basis-refusal`, and runs `results-verdict` (the one results judgement shared with `done-refusals`).
`accept-merge!` calls `merge-verdict` again against main's head, then transacts
`[:db.fn/call declarations-held-tx expected]`, `[:db.fn/call task-basis-held-tx ...]`, the `merging` data and the
replacement, with `:parents (conj merged e-id)`. The merge commit is a merge commit of the candidate and the
branch's commit id.

**Deletion and retirement.** There are two separate retirements:

- `seon.merge/merge!` (`src/seon/merge.clj`, the `:else` path around line 334):
  `(source/accept-merge! ...)` then `(registry/retire-branch! {... :seon.source/candidate (:seon.source/candidate prepared)})`.
  This runs whenever `prepared` carries a candidate, **whether or not `accept-merge!` accepted**. So a refused
  accept deletes the candidate branch in the same call. This contradicts the premise that a refused candidate
  is kept; see Section 6 and the findings below.
- `seon.cluster.source/publish!` and `seon.cluster/candidate-gate!` also call `registry/retire-branch!`
  (`source.clj` around line 405, and `cluster.clj` around line 2734, only for a previous candidate with the same name).

**Gap found.** When `prepare-merge!` refuses after `candidate-gate!` created the branch (a red gate run,
`on-main` red, or `:seon.error/at run`), `prepared` is nil, so `merge!` has no candidate to retire. Nothing in
`prepare-merge!` retires `s` on those paths (its `finally` only calls `release!` on `[base branch]`, the tested
pair). The candidate branch is therefore left behind on those refusals. This is data for the design lane to
confirm on a fixture; it was not run against default.

**What a refusal returns.** Each refusal is an error map (`seon.error.refusal/error`), returned as data.
Variants named in code:

- `:seon.source/write-back-error` (`write-back-refusal`): reasons `:root-refused`, `:commit-absent`, `:row-changed`,
  `:file-changed`, `:one-file`, `:no-replacement`, `:not-analyzable`, `:conflict`, `:span-mismatch`,
  `:install-failed`, `:file-absent`.
- `:seon.program/conflict` is not an error variant in `merge!`; `merge!` wraps it as `:seon.source/conflict-error`
  (`refusal/error :seon.source/conflict-error` with `:seon.program/conflict` and `:seon.program/conflict-digests`).
  The conflicting declarations come from `seon.program/three-way`.
- `:seon.source/lint-error` (from `prepare-merge!`).
- `:seon.source/test-evidence-error` (`merge-verdict` refusals: run not on tested branch, program written since,
  task basis moved, results verdict).
- `:seon.program/scope` (the `refuse` helper inside `prepare-merge!`: a selected declaration not changed, or a
  selected declaration reaching an unselected change).
- The red members are not an error variant: `prepare-merge!` puts them in `:seon.source/red` on the prepared value,
  and `merge-verdict` names them through the results verdict.

**Where refusals are recorded and delivered.**

- `seon.merge/merge!` on a conflict calls `conflict-task!` on the **source branch** for each conflicting
  declaration (`run!` over `:seon.merge/conflicts`). On success-with-result it does the same for the merge result.
- The merge's refusal is returned to root's caller (`my.root/merge!`), and `seon.cluster.db-flow` (per the
  F33 commit `00cc97460`) delivers one problem task for a `:merge` refusal to root on main. The assignment is left
  open for a red gate.
- Red tests on the target are kept as data: `:seon.source/red-on-target`, set in `prepare-merge!`. Commit
  `8be1aba4f` (F14) notes that the refusal's red tests do not survive the merge branch's deletion as data on main.
  It is filed as a task, not built.

## 2. Write-back: where "does not analyze cleanly" runs

The check is `proven-file` in `src/seon/cluster/source.clj`, refusal `:not-analyzable`. It has two messages:
"The file as it is now does not analyze cleanly." (the current file's own analysis is refused,
`:seon.fn/index-refused ours`) and "The candidate file does not analyze cleanly; its namespace form may lack a
require the accepted declarations use." (the reindexed candidate bytes give no analysis, `(not actual)`).

It is reached through `proven-files` (same file), called from two places:

1. **Before the gate, in `prepare-merge!`**, only when `:root` is passed:
   `proof (when (and root (nil? root-error) (seq tx)) ... (proven-files head merged delta root))`. It runs on the
   merged value (`db/with head {:tx-data tx}`) **before** `candidate-gate!`. A refusal here is returned before any
   test runs, so no candidate branch is created. This is the earliest point.
2. **After acceptance, in `write-back-commits`** (via `write-back!`), on the main commit the merge produced
   (`before`, `after`). This is after `accept-merge!` and after the candidate gate. A refusal here means main has
   already accepted the merge. Write-back is downstream of the merge commit.

So the analysis check runs **before** the candidate gate in `prepare-merge!`, and again after acceptance in
`write-back!`. Only the first is a candidate-stage check. The second can refuse after main has moved.

## 3. Step templates and how chains are built

**Templates present.** Each is a vector of one map under `:seon.task/name "template"`, with `:seon.task/instructions`
and `:seon.task/proofs` (no `plan`, which is supplied per chain):

- `seon.step.design.edn`: instructions "## Work": make the plan say what the problem is, search with
  `my.program/search`, add `## What exists` and `## Approach` through `my.task/update!`. Done: "Problem is unchanged,
  What exists names the producer ...". Proofs `#{seon.step.design-test/handoff}`.
- `seon.step.prove.edn`: "## Work": write deftests that fail now, and write their symbols into the next task's
  proofs with `(my.task/update! ... :seon.task/proofs (fnil into #{}) ...)`. Done: "The next task has proofs, each
  ran red for the right reason ...". Proofs `#{seon.step.prove-test/handoff}`.
- `seon.step.implement.edn`: "## Work": change the producer the Approach names; a send-back is
  `my.task/send-back!`. Done: "Every proof is green, no test that is green on main is red here, and every function
  you changed has a complete contract." No proofs key is visible in the first 40 lines; check the file end before
  quoting.

A merge step template would follow the same shape: instructions plus proofs. The recovery proofs would be the
merge gate's own tests (the chain's proofs and the reaching tests). That is the design lane's decision.

**Building a chain.** `seon.task/template` (`src/seon/task.clj`) returns a task's owned members (`:seon.task/owned`)
for a symbol, `:unknown-task` refused otherwise. `seon.task/chain!` writes a vector of maps as one linked chain on
branch `chain-<handle>`, forked from main, with `:seon.chain/from` optional. Only the first map supplies identity;
later tasks are named `"<first name>-<index>"`, and `:seon.task/next` in the maps is ignored and written by `chain!`.
`chain!` fills schema defaults.

**Appending to an existing chain.** No function appends a task to an existing chain. `chain!` writes a whole
vector on a new branch, and the chain fact is written on main once. Adding a task later would need a new
writer; the existing `seon.task/adopt-tasks-tx` reconciles filed tasks, not chain tasks. This is a gap for the
design lane to name.

**Running and ending.** `my.root/run!` calls `task/run-held!` with `(select-keys context [:seon.db/connection
:seon.store/store])`. It is one transaction on the chain branch that changes `:held` to `:running`, then a wake
(`:seon.wake/starts`). It does not start an agent; root's next pass does, with a new attempt number. `my.root/close!`
either holds a chain attempt (`task/hold!`, which writes `:held` on the chain branch and keeps the branch) or closes
a plain assignment (`task/close!`, `:closed`, deleting its branch). `my.root/ended` maps the end messages in root's
inbox through `context/ended`. The recovery task would be created through `chain!`-style data or a new writer, then
run with `my.root/run!`.

## 4. TASK-CHAIN rules that govern this

Source: `docs/seon/schedule/cards/TASK-CHAIN.md`. Quotes at most two lines each.

- **§2.1 Create.** `chain!` writes the maps in one transaction on the chain branch, forked from main, with
  `:seon.chain/from` naming a different start branch; it still merges toward main.
- **§2.4 Done.** One rule, `seon.cluster.source/done-refusals`, judges every task. "The merge judges ..."
  is at §2.6. The final merge is the strict check: a new red test refuses, an uncovered stub refuses. Mid-chain
  the block never shows merge refusals.
- **§2.6 Finish.** A done task with no `next`: root merges the chain branch to main once, the terminal attempt
  as source. Merge owner: `seon.merge/merge!`, then `prepare-merge!`, then `accept-merge!`. The candidate carries
  the task entities too. The filed task's entry is deleted at this merge (installed by S3f-1, partly).
- **§2.7 Bail or fail.** Every end that is not done bails to root once, with a problem task on main. Root "decides
  by writing data": change the held task's plan or proofs and run it again (`my.root/run!`), or abandon. A refused
  final merge is named as a bail. "No automatic relaunch" is the governing sentence: nothing relaunches by itself.
- **Held tasks.** `execution` is `:held` by default; root is its only writer on a chain. A chain to inspect first
  is created `:held` and run by hand.
- **Root repair.** TASK-CHAIN does not describe a root repair step. The only repair language in the card is the
  bail and the send-back.

**AGENTS.md, "Merge is a root task."** Quote, second and third lines: "Conflicts stay unmerged on the merge branch,
which root repairs at the ordinary REPL holding both sides (the merge branch forked from main and a fork of the
agent's SCI context on the source branch; ours is the target, theirs the source)". The paragraph also says `merge!`
"commits only when it is green", and "a test already red on main stops it for root".

**Branch model rulings** (`docs/research/agent-platform/2026-10-15/branch-model-rulings.md`):

- Section "Rulings" 5, merge by reach closure: the merge carries the seeds' closure; "Branch additions nothing
  reaches ... stay on the branch and are listed in the merge report."
- Outcome line near the top: "Not clean: nothing reaches main; the candidate stays as the merge branch with its
  conflicts and reds as data." Then "then `my.root/merge!`." This is the closest existing ruling to the recovery
  design, and it states the kept-branch premise that `merge!`'s retirement contradicts (Section 1).

**One-merge-base** (`docs/research/agent-platform/2026-10-15/one-merge-base.md`, Phase 2b): "the chain's final merge
lands on main's latest head; program entities three-way from the graph merge base; cluster facts (tasks, attempts)
... three-way from the chain's **fork point**". The fork point is `seon.cluster.registry/fork-point`.

## 5. Context a recovery agent would need

- `seon.context/current-state` (`src/seon/context.clj`, around line 763) returns a block: the held task's sections
  (`task-sections`), turns left and status (`:done`, `step-verdict`, `:capped`, `:working`), the agent's namespace,
  unread messages (`:seon.context/unread`), and branch costs (`:seon.context/cost`). Sections are absent when empty.
  Keys named in `current-state-ai`: `:seon.context/task`, `:seon.context/chain`, `:seon.context/proofs`,
  `:seon.context/step`, `:seon.context/send-backs`, `:seon.context/attempts`, `:seon.context/changes`,
  `:seon.context/must-reads`, `:seon.context/cost`, `:seon.context/unread`.
- Merge sections: `seon.context/merged` (line ~205 and ~247) and its refs `:seon.context/stays` (line ~218–230):
  each change "no seed reaches" is shown with the form that removes it, split as its merge would carry it,
  using `seon.cluster.source/merge-list`. A merge-step block would need a `merge-refusal` section; none exists today.
- Branch checkout: `seon.cluster.agent/acquire-context!` (line ~1090) takes `options` and `{:seon.agent/branch ...}`.
  The ordinary arity "reads the agent's explicit branch", and an isolation request allocates a fresh branch off a
  commit. So an agent's context can name a branch other than its chain branch through options. Whether a chain
  agent is allowed to do so is a policy question for the design lane; `candidate-gate!` already does it for root.

## 6. Prior attempts and docs

Commits found by `git log --grep` (case-insensitive) and `-S`:

- `3ef018918` "A chain's refused final merge bails to root once, the refusal its reason (TASK-CHAIN §2.7)": the
  current bail-to-root rule.
- `00cc97460` "F33 ... a merge db<->flow refuses ends its assignment :error and now also delivers root's one problem
  task": delivery of merge refusals.
- `8be1aba4f` "Run 12 frictions filed as tasks: F14 ... the refusal's red tests and run survive the merge branch's
  deletion as data on main, not only in the printed :seon.error/data-edn": a stated gap, not built.
- `99c770a4a` "merge-repair: the pathless seon.ref/section fix as a patch ... file
  my.root/a-conflicted-merge-request-has-no-repair-path": an earlier attempt at a conflicted-merge repair path,
  filed as a task, not built.
- `86d30df01` "Hybrid (round 5) ... root auto-merges the rule-trusted parts of the merge request": an earlier
  design where root merges parts automatically.
- `7acc950de` "Merge words become git's and root's tools get one namespace": the rename to `merge!`, `close!`,
  `merge-requests`.
- `b4c2d0a12` "redo-merge: merge repair and acceptance redesign": S always created as root's repair world (custody by
  merge task). Earlier repair shape; the current model stays on the merge branch.
- `8af89b195` "merge spec: root reviews and takes agent branches (diff value, take/drop on a kept merge branch,
  acceptance...)": the kept-merge-branch take/drop model, which the recovery design resembles.
- `3f69939e4`, `22910a03a`: git words for branches and merging (GV-1).

`-S":merge-"` finds `4ca233c04`, `331eec6ec`, `071458426`, `fdcadc002` (the phase commits that removed the stored merge
base). `-S"Conflicts stay unmerged"` finds `3f69939e4` and `22910a03a`. `-S"root repairs"` finds `6f0680a9e`, `ec5418750`
(working-state docs).

Docs: `docs/research/agent-platform/2026-10-15/merge-closure-plan.md` and `branch-model-rulings.md` (kept merge branch
for conflicts and reds). `docs/research/agent-platform/2026-10-14/design-prototype-run-1.md` D1 and D7: a Design
prototype's merge refused `:red-on-target` because the candidate omitted task refinements.

## 7. The S5 refusal today (assignment 7870bd278124, attempt 11)

Not found in the repository. A search of `docs/` and `src/` for `7870bd278124` returned nothing. The S5 runs are
recorded in `docs/research/agent-platform/2026-10-14/s5-run-1.md`, `s5-run-2.md` and `s5-run-3.md`; none names this
assignment. The recorded merge refusal of the Design prototype is in `design-prototype-run-1.md` (`:red-on-target`).

Where a refusal is stored today, from code:

- The refusal is returned as data by `seon.merge/merge!` to `my.root/merge!`.
- A `:merge` refusal is delivered by `seon.cluster.db-flow` as one problem task on main (commit `00cc97460`).
- The assignment stays open (the red gate case); the chain attempt is closed or held by `my.root/close!`
  (`task/hold!`), which writes the reason on the chain branch.
- Forms that read it (not run): `my.root/ended` (through `context/ended`), `my.root/merge-requests`, and
  `seon.task/attempt-html` (`src/seon/task.clj`, around line 1551), whose render shows the attempt's number, its
  end, its outcome, and a link to the agent's page. `attempt-html` shows root's outcome and reasons.

To read the actual stored refusal for 7870bd278124, the design lane needs a read on default (`my.root/ended` or a
`seon.task/attempt-html` render for that attempt), which this lane did not run per instructions.

## Findings for the design lane

1. The candidate branch is retired on accept refusal (`seon.merge/merge!`, around line 334), which contradicts
   the premise that a refused candidate is kept.
2. A candidate created by `candidate-gate!` is not retired when `prepare-merge!` refuses on a red gate or red
   `on-main`. Confirm with a fixture before designing the retirement rule.
3. The write-back "does not analyze cleanly" check runs before the gate only when `:root` is given (on the merged
   value), and again after acceptance in `write-back!`. The second refusal comes after main moved.
4. No function appends a task to an existing chain. `chain!` writes a whole chain on a new branch.
5. `seon.step.merge.edn` does not exist. Only `seon.merge.edn` is present.
6. No chain agent has a merge-refusal section in its block. `seon.context/merged` is the nearest.
